package me.weishu.kernelsu.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Author
import me.weishu.kernelsu.data.model.RepoModule
import me.weishu.kernelsu.data.model.RepoModuleDetail
import me.weishu.kernelsu.data.model.RepoModuleList
import me.weishu.kernelsu.data.model.RepoRelease
import me.weishu.kernelsu.data.registry.ApiAuthor
import me.weishu.kernelsu.data.registry.ApiModuleDetail
import me.weishu.kernelsu.data.registry.ApiModuleSummary
import me.weishu.kernelsu.data.registry.ApiVersionRef
import me.weishu.kernelsu.data.registry.FileRegistryCache
import me.weishu.kernelsu.data.registry.RegistryClient
import me.weishu.kernelsu.data.registry.RegistryFetcher
import me.weishu.kernelsu.data.registry.RegistryKind
import me.weishu.kernelsu.data.registry.RegistryLocale
import me.weishu.kernelsu.data.registry.RegistryUpdatePolicy
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.data.registry.UpdateVerification
import me.weishu.kernelsu.ksuApp
import okhttp3.CacheControl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** 基于 [RegistryClient] 的模块仓库实现；网络访问统一走 [ksuApp] 的 OkHttp 客户端。 */
class ModuleRepoRepositoryImpl : ModuleRepoRepository {

    companion object {
        /** 日志标签。 */
        private const val TAG = "ModuleRepoRepository"

        /** Site root of KernelSU Modules; every API path is resolved against it. */
        const val REGISTRY_BASE_URL = RegistryUrls.DEFAULT_BASE_URL

        /** Upper bound for a single JSON / HTML document. */
        private const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024

        // One client per process so the list and detail screens share the same catalog.
        private val client: RegistryClient by lazy {
            RegistryClient(
                baseUrl = REGISTRY_BASE_URL,
                fetcher = OkHttpRegistryFetcher,
                cache = FileRegistryCache(File(ksuApp.filesDir, "module_registry")),
            )
        }

        /** 当前设备的 UI 语言，用于拼出站点链接。 */
        private fun requestedLocale(): String = Locale.getDefault().toLanguageTag()
    }

    /** 以 no-cache 重新校验的拉取器，确保 index/list/detail 不会来自不同缓存年龄。 */
    private object OkHttpRegistryFetcher : RegistryFetcher {
        /** 执行请求并返回完整 UTF-8 文本；HTTP 错误或文档过大直接抛异常。 */
        override fun fetch(url: String): String {
            val request = Request.Builder()
                .url(url)
                // Revalidate so index, list and detail are never served from different cache ages.
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()
            ksuApp.okhttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body
                if (body.contentLength() > MAX_DOCUMENT_BYTES) throw IOException("Document too large: $url")
                // 有些响应没有 Content-Length，peek 一下确认不会无限读取。
                val source = body.source()
                if (source.request(MAX_DOCUMENT_BYTES + 1)) throw IOException("Document too large: $url")
                return source.readUtf8()
            }
        }
    }

    /** 拉取模块列表；网络失败时返回上次成功缓存的列表，并标记 stale。 */
    override suspend fun fetchModules(): Result<RepoModuleList> = withContext(Dispatchers.IO) {
        runCatching {
            val load = client.loadCatalog()
            load.error?.let { Log.w(TAG, "registry fetch failed, using last good copy", it) }
            RepoModuleList(
                modules = load.catalog.modules.map { it.toRepoModule() },
                buildId = load.catalog.buildId,
                stale = load.error != null || load.catalog.fromCache,
                error = load.error,
            )
        }
    }

    /** 拉取模块详情与 README；README 失败不影响详情本身，只标记 readmeFailed。 */
    override suspend fun fetchDetail(moduleId: String): Result<RepoModuleDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val load = client.loadDetail(moduleId)
            load.error?.let { Log.w(TAG, "detail fetch failed, using last good copy", it) }
            val readmeResult = runCatching { client.loadReadme(load.detail) }
                .onFailure { Log.w(TAG, "readme fetch failed", it) }
            val readme = readmeResult.getOrNull()
            load.detail.toRepoModuleDetail(
                siteLocale = RegistryLocale.siteLocale(requestedLocale()),
                readmeHtml = readme?.html,
                readmeFailed = readmeResult.isFailure,
                fromCache = load.fromCache,
            )
        }
    }

    /** 查找指定发布（或稳定版）的 changelog HTML；找不到时返回 null。 */
    override suspend fun fetchChangelogHtml(moduleId: String, tag: String?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val detail = client.loadDetail(moduleId).detail
            // changelog 只展示正式发布，预发布版本不提供安装。
            val releases = detail.releases.filter { !it.prerelease }
            val release = if (tag != null) {
                releases.firstOrNull { it.tag == tag || it.version == tag }
            } else {
                releases.firstOrNull { it.versionCode == detail.stable.versionCode } ?: releases.firstOrNull()
            }
            release?.changelogHtml?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 解析 updateJson 更新应如何校验；索引不可用时按“无法确认”处理而不是直接放行。 */
    override suspend fun resolveUpdate(
        moduleId: String,
        updateJsonUrl: String,
        zipUrl: String,
        versionCode: Int,
    ): UpdateVerification = withContext(Dispatchers.IO) {
        if (RegistryUpdatePolicy.isUnrelatedToRegistry(client.baseUrl, updateJsonUrl, zipUrl)) {
            return@withContext UpdateVerification.Legacy
        }
        // Use the index in memory as is; only load one (network, then cache) when there is none.
        val catalog = runCatching { client.catalog() }
            .onFailure { Log.w(TAG, "registry index unavailable for update check", it) }
            .getOrNull()
        // null 表示索引不可用，与“不在索引中”区分开。
        val inIndex = catalog?.let { it.find(moduleId) != null }
        val detail = if (inIndex == true) {
            runCatching { client.loadDetail(moduleId).detail }
                .onFailure { Log.w(TAG, "registry detail unavailable for update check", it) }
                .getOrNull()
        } else {
            null
        }
        RegistryUpdatePolicy.decide(client.baseUrl, updateJsonUrl, zipUrl, versionCode, inIndex, detail)
    }

    /** 判断 URL 是否与注册表站点同源（同源内容需使用受限渲染器）。 */
    override fun isRegistryUrl(url: String): Boolean = RegistryUrls.isSameOrigin(url, client.baseUrl)

    /** 拉取注册表托管的文本（如 Markdown）；非注册表 URL 或非 https 直接返回 null。 */
    override suspend fun fetchRegistryText(url: String): String? = withContext(Dispatchers.IO) {
        if (!isRegistryUrl(url) || !RegistryUrls.isHttpsUrl(url)) return@withContext null
        runCatching { OkHttpRegistryFetcher.fetch(url) }
            .onFailure { Log.w(TAG, "registry document fetch failed: $url", it) }
            .getOrNull()
    }

    /** 把 ISO 时间格式化为本地日期，并返回 (展示文本, epoch millis)；解析失败时回退原串/0。 */
    private fun formatDate(iso: String): Pair<String, Long> = runCatching {
        val time = OffsetDateTime.parse(iso)
        val text = time.atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        text to time.toInstant().toEpochMilli()
    }.getOrDefault(iso to 0L)

    /** 把 API 摘要映射为 UI 使用的 [RepoModule]。 */
    private fun ApiModuleSummary.toRepoModule(): RepoModule =
        buildRepoModule(id, name, summary, authors, tags, metamodule, kind, icon, stable, stars, starsRepository)

    /** 摘要与详情共用的映射逻辑；图标相对路径会解析成完整 URL。 */
    private fun buildRepoModule(
        id: String,
        name: String,
        summary: String,
        authors: List<ApiAuthor>,
        tags: List<String>,
        metamodule: Boolean,
        kind: String,
        icon: String?,
        stable: ApiVersionRef,
        stars: Int?,
        starsRepository: String?,
    ): RepoModule {
        val authorList = authors
            .filter { it.name.isNotBlank() }
            .map { Author(name = it.name.trim(), link = it.url.orEmpty()) }
        val (date, epoch) = formatDate(stable.publishedAt)
        return RepoModule(
            moduleId = id,
            moduleName = name.trim().ifEmpty { id },
            authors = authorList.joinToString(", ") { it.name },
            authorList = authorList,
            summary = summary.trim(),
            tags = tags,
            metamodule = metamodule,
            isScript = kind == RegistryKind.SCRIPT,
            iconUrl = icon?.let { client.resolve(it) },
            latestVersion = stable.version,
            latestVersionCode = stable.versionCode,
            latestReleaseTime = date,
            latestReleaseEpoch = epoch,
            stars = stars,
            starsRepository = starsRepository,
        )
    }

    /** 把详情映射为 [RepoModuleDetail]；预发布版本在这里被过滤，避免被安装。 */
    private fun ApiModuleDetail.toRepoModuleDetail(
        siteLocale: String,
        readmeHtml: String?,
        readmeFailed: Boolean,
        fromCache: Boolean,
    ): RepoModuleDetail {
        val module = buildRepoModule(id, name, summary, authors, tags, metamodule, kind, icon, stable, stars, starsRepository)
        return RepoModuleDetail(
            module = module,
            license = license,
            homepage = homepage,
            support = support,
            sourceFullName = source.fullName,
            sourceUrl = source.url,
            sourceCommit = source.commit,
            readmeHtml = readmeHtml,
            readmeFailed = readmeFailed,
            readmeBaseUrl = client.baseUrl,
            // Prereleases are recorded by the registry but never offered for installation.
            releases = releases.filter { !it.prerelease }.map { r ->
                RepoRelease(
                    tag = r.tag,
                    name = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
                    version = r.version,
                    versionCode = r.versionCode,
                    publishedAt = formatDate(r.publishedAt).first,
                    changelogHtml = r.changelogHtml?.takeIf { it.isNotBlank() },
                    zipUrl = r.zip.url,
                    zipName = r.zip.name.substringAfterLast('/').ifBlank { "$id-${r.versionCode}.zip" },
                    zipSize = r.zip.size,
                    sha256 = r.zip.sha256,
                    tagCommit = r.tagCommit,
                    releaseUrl = r.releaseUrl,
                    attestationUrl = r.attestationUrl,
                    immutable = r.immutable,
                )
            },
            healthStale = health?.status == "stale",
            // The public site only has UI locales, not the module's content locales.
            webUrl = "${client.baseUrl}$siteLocale/modules/$id/",
            fromCache = fromCache,
        )
    }
}
