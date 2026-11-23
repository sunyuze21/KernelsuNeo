package me.weishu.kernelsu.data.registry

import kotlinx.serialization.Serializable

/*
 * Client protocol of KernelSU Modules ({baseUrl}/api/v1/...).
 *
 * Mirrors packages/schema/src/api.ts of KernelSU-Modules/tools. Every path inside these
 * documents is relative to the site root (baseUrl). Unknown fields are ignored; an unknown
 * schemaVersion is rejected (see [RegistryParser]).
 */

/** 当前客户端支持的注册表 API 版本；文档中的 schemaVersion 必须与之相等。 */
const val REGISTRY_API_SCHEMA_VERSION = 1

/** `kind` of a registry entry: an installable module or a one-shot script run by the manager. */
object RegistryKind {
    /** 可安装的模块。 */
    const val MODULE = "module"
    /** 由 Manager 直接运行的一次性脚本。 */
    const val SCRIPT = "script"

    /** Kinds this client understands; entries of any other kind are hidden. */
    fun isKnown(kind: String): Boolean = kind == MODULE || kind == SCRIPT
}

/** 索引文档：记录 buildId 以及模块列表文档的相对路径。 */
@Serializable
data class ApiIndex(
    /** 协议版本，不等于 [REGISTRY_API_SCHEMA_VERSION] 时整个文档被拒绝。 */
    val schemaVersion: Int,
    /** 同一次站点构建的标识；index 与 modules 必须一致才能组合使用。 */
    val buildId: String,
    val repoCommit: String,
    val generatedAt: String,
    /** "api/v1/modules.json" */
    val modules: String,
)

/** 版本引用：注册表首页只展示稳定版的版本号、versionCode 与发布时间。 */
@Serializable
data class ApiVersionRef(
    val version: String,
    val versionCode: Int,
    val publishedAt: String,
)

/** 作者信息；[url] 必须通过 https 校验后才会在 UI 中变成链接。 */
@Serializable
data class ApiAuthor(
    val name: String,
    val url: String? = null,
)

/** 模块列表项；字段是详情文档的摘要，足够渲染列表而无需拉详情。 */
@Serializable
data class ApiModuleSummary(
    val id: String,
    /** Module content is single-language; no locale fallback is applied. */
    val name: String = "",
    val summary: String = "",
    val authors: List<ApiAuthor> = emptyList(),
    val tags: List<String> = emptyList(),
    val metamodule: Boolean = false,
    /** [RegistryKind]; a missing value means a module. */
    val kind: String = RegistryKind.MODULE,
    /** Content-hashed icon, "assets/{hash}.{ext}". */
    val icon: String? = null,
    /** 当前稳定版；脚本也用它展示版本。 */
    val stable: ApiVersionRef,
    /** GitHub stargazer count of [starsRepository], or null when unknown. */
    val stars: Int? = null,
    /** "owner/name" of the repository [stars] belongs to (may differ from the source repository). */
    val starsRepository: String? = null,
    /** "api/v1/modules/{id}.json" */
    val detail: String,
    val updatedAt: String,
)

/** 模块列表文档；[modules] 中不合法或重复的条目已被解析器丢弃。 */
@Serializable
data class ApiModules(
    val schemaVersion: Int,
    val buildId: String,
    val modules: List<ApiModuleSummary>,
)

/** 发布 ZIP 的元数据；下载前用它固定 URL、大小与 SHA-256。 */
@Serializable
data class ApiZip(
    val url: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

/** 一次不可变发布；客户端只会展示并安装非 prerelease 的条目。 */
@Serializable
data class ApiRelease(
    val tag: String,
    val name: String? = null,
    val version: String,
    val versionCode: Int,
    val prerelease: Boolean,
    val publishedAt: String,
    /** Sanitized HTML of the release notes, or null. */
    val changelogHtml: String? = null,
    val zip: ApiZip,
    /** 发布 tag 指向的 git commit，用于来源校验。 */
    val tagCommit: String,
    val releaseUrl: String,
    val attestationUrl: String? = null,
    /** 仅不可变发布才允许安装（防止 tag 被重新指向）。 */
    val immutable: Boolean,
)

/** 模块声明的 Android API / 架构兼容范围；null 表示未限制。 */
@Serializable
data class ApiCompatibility(
    val androidMinApi: Int? = null,
    val androidMaxApi: Int? = null,
    val architectures: List<String>? = null,
)

/** 模块源码仓库信息；[url] 必须为 https，否则回退到根据 fullName 拼出的 GitHub 地址。 */
@Serializable
data class ApiSource(
    val host: String,
    val fullName: String,
    val url: String,
    val commit: String,
)

/** 上游数据健康状态；[status] 为 "stale" 时 UI 会提示数据可能过期。 */
@Serializable
data class ApiHealth(
    val status: String,
    val lastSuccessAt: String? = null,
)

/** 模块详情文档：列表字段 + license/readme/releases 等。 */
@Serializable
data class ApiModuleDetail(
    val schemaVersion: Int,
    val buildId: String,
    val id: String,
    val name: String = "",
    val summary: String = "",
    val authors: List<ApiAuthor> = emptyList(),
    val tags: List<String> = emptyList(),
    val metamodule: Boolean = false,
    /** [RegistryKind]; a missing value means a module. */
    val kind: String = RegistryKind.MODULE,
    val icon: String? = null,
    /** 稳定版引用，必须与 releases 中对应 versionCode 的条目一致。 */
    val stable: ApiVersionRef,
    val stars: Int? = null,
    val starsRepository: String? = null,
    val detail: String,
    val updatedAt: String,
    val license: String? = null,
    val homepage: String? = null,
    val support: String? = null,
    val compatibility: ApiCompatibility? = null,
    val source: ApiSource,
    /** "api/v1/modules/{id}/readme" (an HTML fragment), or null when the module has no README. */
    val readme: String? = null,
    /** Newest versionCode first; the first non-prerelease entry equals `stable`. */
    val releases: List<ApiRelease> = emptyList(),
    val health: ApiHealth? = null,
)

/** A consistent pair of index + module list taken from the same build. */
data class RegistryCatalog(
    val index: ApiIndex,
    val modules: List<ApiModuleSummary>,
    /** True when this copy came from the on-disk cache instead of the network. */
    val fromCache: Boolean,
) {
    /** 当前 catalog 的构建 id，详情必须与它一致。 */
    val buildId: String get() = index.buildId
    /** 按 id 建立索引，首次查找时惰性构建，减少线性扫描。 */
    private val byId: Map<String, ApiModuleSummary> by lazy { modules.associateBy { it.id } }
    /** 按模块 id 查找摘要；不存在返回 null。 */
    fun find(id: String): ApiModuleSummary? = byId[id]
}
