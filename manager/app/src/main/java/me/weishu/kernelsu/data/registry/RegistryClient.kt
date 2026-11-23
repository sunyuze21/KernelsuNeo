package me.weishu.kernelsu.data.registry

import kotlinx.serialization.Serializable
import java.io.IOException

/** Fetches a document; throws [IOException] on network / HTTP errors. */
fun interface RegistryFetcher {
    /** 拉取完整文本文档；实现方不应缓存，缓存策略由 [RegistryClient] 决定。 */
    @Throws(IOException::class)
    fun fetch(url: String): String
}

/** Small key/value store for the last complete good copies. Keys are file-name safe. */
interface RegistryCache {
    /** 读取缓存；不存在时返回 null，内容损坏由上层解析时忽略。 */
    fun read(key: String): String?
    /** 覆盖写入缓存；实现应保证并发写入不会产生半截文件。 */
    fun write(key: String, value: String)
}

/**
 * Registry protocol client: index -> module list -> detail on demand.
 *
 * - Documents from different builds are never combined: when a detail's buildId differs from
 *   the list's, the index and list are re-read once; if they still disagree the load fails.
 * - The last complete, consistent copy is kept in [cache] and used when the network or
 *   parsing fails.
 *
 * Blocking; call from a background thread.
 */
class RegistryClient(
    baseUrl: String = RegistryUrls.DEFAULT_BASE_URL,
    private val fetcher: RegistryFetcher,
    private val cache: RegistryCache,
    private val parser: RegistryParser = RegistryParser(),
) {
    /** 规范化后的站点根 URL（保证以 '/' 结尾），所有相对路径都基于它。 */
    val baseUrl: String = RegistryUrls.normalizeBaseUrl(baseUrl)

    /** 最近一次成功加载的完整 catalog；供列表与详情共享，避免重复请求。 */
    @Volatile
    private var current: RegistryCatalog? = null
    /** 串行化网络加载与写缓存，防止 index/list 来自不同 build。 */
    private val lock = Any()

    /** catalog 加载结果；[error] 非空表示网络失败并回退到了缓存。 */
    data class CatalogLoad(
        val catalog: RegistryCatalog,
        /** Non-null when the network copy could not be used and a cached copy was returned. */
        val error: Throwable? = null,
    )

    /** 详情加载结果；[catalog] 可能已刷新，[fromCache] 表示详情来自磁盘缓存。 */
    data class DetailLoad(
        /** Catalog the detail belongs to; may be newer than the one the caller held. */
        val catalog: RegistryCatalog,
        val detail: ApiModuleDetail,
        val fromCache: Boolean = false,
        val error: Throwable? = null,
    )

    /** README HTML 片段；仅包含已清洗的正文。 */
    data class Readme(val html: String)

    /** 磁盘缓存格式：原始 index 与 modules 文本，读取时重新解析以校验一致性。 */
    @Serializable
    private data class CachedCatalog(val index: String, val modules: String)

    /** 把文档相对路径解析成完整 URL；非法路径会抛 [RegistryFormatException]。 */
    fun resolve(path: String): String = RegistryUrls.resolve(baseUrl, path)

    /** 返回内存中的 catalog（可能为 null），不会触发网络请求。 */
    fun currentCatalog(): RegistryCatalog? = current

    /** Reads index + list from the network, falling back to the last complete good copy. */
    fun loadCatalog(): CatalogLoad = synchronized(lock) {
        try {
            CatalogLoad(fetchCatalog())
        } catch (e: Exception) {
            // 网络失败时优先用内存副本，其次用磁盘缓存；都没有就把异常抛给调用方。
            val fallback = current ?: readCachedCatalog()
            if (fallback != null) {
                current = fallback
                CatalogLoad(fallback, e)
            } else {
                throw e
            }
        }
    }

    /** Returns the catalog in memory, or loads one (network, then cache). */
    fun catalog(): RegistryCatalog = current ?: loadCatalog().catalog

    /**
     * 读取 index 与 modules，只有 buildId 一致时才接受并写缓存。
     * 不一致时重试一次，仍不一致则抛 [BuildMismatchException]。
     */
    private fun fetchCatalog(): RegistryCatalog {
        var lastError: Exception? = null
        repeat(2) {
            val indexText = fetcher.fetch(resolve(RegistryUrls.INDEX_PATH))
            val index = parser.parseIndex(indexText)
            val modulesText = fetcher.fetch(resolve(index.modules))
            val modules = parser.parseModules(modulesText)
            if (modules.buildId == index.buildId) {
                val catalog = RegistryCatalog(index, modules.modules, fromCache = false)
                current = catalog
                // 缓存写入失败不影响本次加载结果。
                runCatching {
                    cache.write(
                        CATALOG_KEY,
                        RegistryParser.DEFAULT_JSON.encodeToString(CachedCatalog.serializer(), CachedCatalog(indexText, modulesText))
                    )
                }
                return catalog
            }
            lastError = BuildMismatchException("index ${index.buildId} != modules ${modules.buildId}")
        }
        throw lastError!!
    }

    /** 从磁盘缓存恢复 catalog；内容损坏或 build 不一致时返回 null。 */
    private fun readCachedCatalog(): RegistryCatalog? = runCatching {
        val text = cache.read(CATALOG_KEY) ?: return null
        val cached = RegistryParser.DEFAULT_JSON.decodeFromString(CachedCatalog.serializer(), text)
        val index = parser.parseIndex(cached.index)
        val modules = parser.parseModules(cached.modules)
        if (modules.buildId != index.buildId) return null
        RegistryCatalog(index, modules.modules, fromCache = true)
    }.getOrNull()

    /** 拉取并解析单个模块详情，同时确保详情 id 与索引中的 id 一致。 */
    private fun fetchDetail(summary: ApiModuleSummary): Pair<String, ApiModuleDetail> {
        val text = fetcher.fetch(resolve(summary.detail))
        val detail = parser.parseDetail(text)
        if (detail.id != summary.id) throw RegistryFormatException("Detail id ${detail.id} != ${summary.id}")
        return text to detail
    }

    /**
     * Loads a module detail consistent with the catalog. Throws [ModuleNotInIndexException]
     * when the module is not part of the current index.
     */
    fun loadDetail(id: String): DetailLoad {
        // A catalog loaded right here is as fresh as a re-read would be.
        var refreshed = current == null
        val catalog = catalog()
        val summary = catalog.find(id) ?: throw ModuleNotInIndexException(id)
        try {
            val (text, detail) = fetchDetail(summary)
            if (detail.buildId == catalog.buildId) {
                writeDetail(id, text)
                return DetailLoad(catalog, detail)
            }
            // The site was redeployed in between: re-read index + list once, never mix builds.
            refreshed = true
            val fresh = synchronized(lock) { fetchCatalog() }
            val freshSummary = fresh.find(id) ?: throw ModuleNotInIndexException(id)
            val (freshText, freshDetail) =
                if (detail.buildId == fresh.buildId) text to detail else fetchDetail(freshSummary)
            if (freshDetail.buildId != fresh.buildId) {
                throw BuildMismatchException("detail ${freshDetail.buildId} != modules ${fresh.buildId}")
            }
            writeDetail(id, freshText)
            return DetailLoad(fresh, freshDetail)
        } catch (e: ModuleNotInIndexException) {
            throw e
        } catch (e: Exception) {
            // The detail may be gone because the module was removed in a newer build: re-read
            // index + list once. Offline, the cached fallback below still applies.
            if (!refreshed) {
                val fresh = runCatching { synchronized(lock) { fetchCatalog() } }.getOrNull()
                if (fresh != null) {
                    val freshSummary = fresh.find(id) ?: throw ModuleNotInIndexException(id)
                    if (fresh.buildId != catalog.buildId) {
                        runCatching { fetchDetail(freshSummary) }.getOrNull()
                            ?.takeIf { (_, detail) -> detail.buildId == fresh.buildId }
                            ?.let { (text, detail) ->
                                writeDetail(id, text)
                                return DetailLoad(fresh, detail)
                            }
                    }
                }
            }
            // A refresh above may already have advanced the catalog: only fall back to a cached
            // detail of the build the client is on now, for a module that is still listed there.
            val latest = current ?: catalog
            if (latest.find(id) == null) throw e
            val cached = readCachedDetail(id)?.takeIf { it.buildId == latest.buildId && it.id == id }
                ?: throw e
            return DetailLoad(latest, cached, fromCache = true, error = e)
        }
    }

    /** Loads the README HTML, or null if the module has none. Falls back to a cached copy of the same build. */
    fun loadReadme(detail: ApiModuleDetail): Readme? {
        val path = detail.readme ?: return null
        // 缓存 key 按模块 id 区分，内容前缀记录 buildId 以便失效。
        val key = "readme-${detail.id}"
        return try {
            val html = fetcher.fetch(resolve(path))
            runCatching { cache.write(key, detail.buildId + "\n" + html) }
            Readme(html)
        } catch (e: Exception) {
            val cached = runCatching { cache.read(key) }.getOrNull() ?: throw e
            val buildId = cached.substringBefore('\n')
            if (buildId != detail.buildId) throw e
            Readme(cached.substringAfter('\n'))
        }
    }

    /** 缓存详情原文；失败不影响调用方，下次仍会走网络。 */
    private fun writeDetail(id: String, text: String) {
        runCatching { cache.write("detail-$id", text) }
    }

    /** 读取并解析缓存的详情；内容损坏时返回 null。 */
    private fun readCachedDetail(id: String): ApiModuleDetail? = runCatching {
        cache.read("detail-$id")?.let(parser::parseDetail)
    }.getOrNull()

    private companion object {
        /** 完整 catalog 的缓存 key。 */
        const val CATALOG_KEY = "catalog"
    }
}
