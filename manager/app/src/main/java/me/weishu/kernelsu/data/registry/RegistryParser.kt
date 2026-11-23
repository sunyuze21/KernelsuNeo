package me.weishu.kernelsu.data.registry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 注册表协议相关异常的基类，便于调用方统一捕获。 */
open class RegistryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The document is not valid JSON or violates the protocol. */
class RegistryFormatException(message: String, cause: Throwable? = null) : RegistryException(message, cause)

/** The document declares a schemaVersion this client does not understand. */
class UnsupportedSchemaException(val schemaVersion: Int?) :
    RegistryException("Unsupported registry schemaVersion: ${schemaVersion ?: "missing"}")

/** Documents of two different builds were observed even after re-reading the index. */
class BuildMismatchException(message: String) : RegistryException(message)

/** The module is not part of the current index (e.g. suspended or removed). */
class ModuleNotInIndexException(val moduleId: String) : RegistryException("Module $moduleId is not in the registry index")

/**
 * Parses and validates registry documents. Unknown fields are ignored, unknown schema
 * versions are rejected, and entries that fail validation are dropped instead of failing the
 * whole document where that is safe (single modules in the list, single releases).
 */
class RegistryParser(private val json: Json = DEFAULT_JSON) {

    companion object {
        /** 宽松解析：忽略未知字段，但拒绝字符串化的数字/布尔（避免类型歧义）。 */
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
        }

        /** buildId 为 16~64 位小写十六进制。 */
        private val BUILD_ID = Regex("^[0-9a-f]{16,64}$")
        private val MODULE_ID = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val GIT_SHA = Regex("^[0-9a-f]{40}$")
        private val FULL_NAME = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        /** GitHub "owner/name"; it becomes a link, so "." / ".." names are refused. */
        private val STARS_REPOSITORY = Regex("^[A-Za-z0-9-]+/(?!\\.\\.?$)[A-Za-z0-9._-]+$")

        /** 模块 id 长度 2~64，且以字母开头。 */
        fun isValidModuleId(id: String): Boolean = id.length in 2..64 && MODULE_ID.matches(id)
        /** 校验 buildId 格式；index 与 modules 的 buildId 必须都通过。 */
        fun isValidBuildId(id: String): Boolean = BUILD_ID.matches(id)

        /** Unknown kinds are hidden; a script can never be a metamodule (a manifest error upstream). */
        fun isSupportedKind(kind: String, metamodule: Boolean): Boolean =
            RegistryKind.isKnown(kind) && !(kind == RegistryKind.SCRIPT && metamodule)
    }

    /** 解析为 JSON 对象并校验 schemaVersion；格式错误统一转成 [RegistryFormatException]。 */
    private fun parseObject(text: String): JsonObject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw RegistryFormatException("Malformed JSON", e)
        } catch (e: IllegalArgumentException) {
            throw RegistryFormatException("Malformed JSON", e)
        }
        val obj = element as? JsonObject ?: throw RegistryFormatException("Expected a JSON object")
        // schemaVersion 必须是数字；字符串形式视为无效。
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version != REGISTRY_API_SCHEMA_VERSION) throw UnsupportedSchemaException(version)
        return obj
    }

    /** 将 JSON 反序列化为目标类型，序列化错误统一转成 [RegistryFormatException]。 */
    private fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, element: JsonElement): T =
        try {
            json.decodeFromJsonElement(deserializer, element)
        } catch (e: SerializationException) {
            throw RegistryFormatException("Invalid registry document: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw RegistryFormatException("Invalid registry document: ${e.message}", e)
        }

    /** 校验 buildId；不合法直接拒绝整份文档。 */
    private fun requireBuildId(buildId: String) {
        if (!isValidBuildId(buildId)) throw RegistryFormatException("Invalid buildId: $buildId")
    }

    /** 解析索引文档，并校验模块列表路径相对且安全。 */
    fun parseIndex(text: String): ApiIndex {
        val index = decode(ApiIndex.serializer(), parseObject(text))
        requireBuildId(index.buildId)
        if (!RegistryUrls.isValidApiPath(index.modules)) throw RegistryFormatException("Invalid modules path")
        return index
    }

    /**
     * 解析模块列表：逐项校验，非法或重复 id 的条目被丢弃而不是失败整份文档。
     * 返回的 [ApiModules] 固定使用当前客户端支持的 schemaVersion。
     */
    fun parseModules(text: String): ApiModules {
        val obj = parseObject(text)
        val buildId = (obj["buildId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RegistryFormatException("Missing buildId")
        requireBuildId(buildId)
        val array = obj["modules"] as? JsonArray ?: throw RegistryFormatException("Missing modules array")
        val seen = HashSet<String>()
        val modules = array.mapNotNull { element ->
            val summary = runCatching { decode(ApiModuleSummary.serializer(), sanitizeStars(element)) }.getOrNull()
                ?.let(::validateSummary)
                ?: return@mapNotNull null
            // id 大小写不敏感地去重，防止同名模块出现两次。
            if (!seen.add(summary.id.lowercase())) return@mapNotNull null
            summary
        }
        return ApiModules(schemaVersion = REGISTRY_API_SCHEMA_VERSION, buildId = buildId, modules = modules)
    }

    /** 解析模块详情，并清洗图标、README、链接与发布列表；非法发布条目会被单独丢弃。 */
    fun parseDetail(text: String): ApiModuleDetail {
        val obj = parseObject(text)
        // Releases are decoded one by one so a single bad entry is dropped, not the whole detail.
        val releaseElements = when (val r = obj["releases"]) {
            null -> emptyList()
            is JsonArray -> r.toList()
            else -> throw RegistryFormatException("releases must be an array")
        }
        val detail = decode(
            ApiModuleDetail.serializer(),
            JsonObject(sanitizeStars(obj) + ("releases" to JsonArray(emptyList()))),
        )
        requireBuildId(detail.buildId)
        if (!isValidModuleId(detail.id)) throw RegistryFormatException("Invalid module id: ${detail.id}")
        if (!isSupportedKind(detail.kind, detail.metamodule)) throw RegistryFormatException("Unsupported kind: ${detail.kind}")
        if (detail.stable.versionCode < 1) throw RegistryFormatException("Invalid stable versionCode")
        if (detail.source.host != "github") throw RegistryFormatException("Unsupported source host: ${detail.source.host}")
        val fullName = detail.source.fullName
        if (!FULL_NAME.matches(fullName)) throw RegistryFormatException("Invalid source fullName: $fullName")
        val seenTags = HashSet<String>()
        val releases = releaseElements.mapNotNull { element ->
            val release = runCatching { decode(ApiRelease.serializer(), element) }.getOrNull()
                ?.let { sanitizeRelease(it) }
                ?: return@mapNotNull null
            // LazyColumn keys are tags: the first (newest) entry of a tag wins.
            if (!seenTags.add(release.tag)) return@mapNotNull null
            release
        }
        return detail.copy(
            icon = detail.icon?.takeIf(RegistryUrls::isValidApiPath),
            readme = detail.readme?.takeIf(RegistryUrls::isValidApiPath),
            releases = releases,
            homepage = detail.homepage?.takeIf(RegistryUrls::isWebUrl),
            support = detail.support?.takeIf(RegistryUrls::isWebUrl),
            authors = detail.authors.map { a -> a.copy(url = a.url?.takeIf(RegistryUrls::isHttpsUrl)) },
            // Never open a non-https source link; the GitHub URL is derivable from fullName.
            source = detail.source.copy(
                url = detail.source.url.takeIf(RegistryUrls::isHttpsUrl) ?: "https://github.com/$fullName"
            ),
        )
    }

    /**
     * `stars` / `starsRepository` are optional display data: a malformed value is dropped
     * instead of rejecting the module (decoding a wrongly typed field would fail the entry).
     */
    private fun sanitizeStars(element: JsonElement): JsonObject {
        val obj = element as? JsonObject ?: throw RegistryFormatException("Expected a JSON object")
        if ("stars" !in obj && "starsRepository" !in obj) return obj
        val stars = (obj["stars"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
        val repo = (obj["starsRepository"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { STARS_REPOSITORY.matches(it) }
        return JsonObject(
            obj - "stars" - "starsRepository" +
                listOfNotNull(stars?.let { "stars" to JsonPrimitive(it) }, repo?.let { "starsRepository" to JsonPrimitive(it) })
        )
    }

    /** 校验列表项；任何关键字段非法都返回 null（由调用方丢弃该条目）。 */
    private fun validateSummary(s: ApiModuleSummary): ApiModuleSummary? {
        if (!isValidModuleId(s.id)) return null
        if (!RegistryUrls.isValidApiPath(s.detail)) return null
        if (s.stable.versionCode < 1) return null
        if (!isSupportedKind(s.kind, s.metamodule)) return null
        return s.copy(
            icon = s.icon?.takeIf(RegistryUrls::isValidApiPath),
            authors = s.authors.map { a -> a.copy(url = a.url?.takeIf(RegistryUrls::isHttpsUrl)) },
        )
    }

    // The ZIP URL is not tied to source.fullName: it is fixed when the release is listed and
    // keeps the old owner/repo after a rename or transfer. The download is pinned by SHA-256.
    private fun sanitizeRelease(r: ApiRelease): ApiRelease? {
        // 只有不可变、带合法 SHA-256 且下载地址在白名单内的发布才允许安装。
        val valid = r.tag.isNotBlank() &&
            r.versionCode >= 1 &&
            r.immutable &&
            r.zip.size > 0 &&
            SHA256.matches(r.zip.sha256) &&
            GIT_SHA.matches(r.tagCommit) &&
            RegistryUrls.isHttpsUrl(r.releaseUrl) &&
            RegistryUrls.isAllowedReleaseDownloadUrl(r.zip.url)
        if (!valid) return null
        return r.copy(attestationUrl = r.attestationUrl?.takeIf(RegistryUrls::isHttpsUrl))
    }
}
