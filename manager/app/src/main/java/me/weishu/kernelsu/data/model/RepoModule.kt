package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable

/** 模块作者；[link] 为空字符串表示没有可点击的主页。 */
@Immutable
data class Author(
    val name: String,
    val link: String,
)

/** A module of the registry index, already localized for the current UI locale. */
@Immutable
data class RepoModule(
    /** 注册表中的稳定模块 id，也是 LazyColumn 的 key。 */
    val moduleId: String,
    val moduleName: String,
    /** 作者名拼接后的字符串，仅用于展示。 */
    val authors: String,
    /** 结构化的作者列表，用于渲染可点击链接。 */
    val authorList: List<Author>,
    val summary: String,
    val tags: List<String>,
    val metamodule: Boolean,
    /** A one-shot script (registry `kind: script`): run by the manager, never installed. */
    val isScript: Boolean = false,
    /** 图标完整 URL；null 表示注册表未提供。 */
    val iconUrl: String?,
    val latestVersion: String,
    val latestVersionCode: Int,
    /** Formatted publish date of the stable release. */
    val latestReleaseTime: String,
    /** Epoch millis of the stable release, used for sorting. */
    val latestReleaseEpoch: Long,
    /** GitHub stargazer count, or null when the registry doesn't know it. */
    val stars: Int? = null,
    /** "owner/name" of the repository [stars] belongs to. */
    val starsRepository: String? = null,
)

/** 模块列表加载结果；[stale] 为 true 时展示的是上次成功缓存，UI 需提示可能过期。 */
@Immutable
data class RepoModuleList(
    val modules: List<RepoModule>,
    /** 注册表构建 id；列表与详情不属于同一 build 时不能混用。 */
    val buildId: String,
    /** True when the list is the last good cached copy because the network copy failed. */
    val stale: Boolean,
    /** 网络/解析失败原因；成功时为 null。 */
    val error: Throwable?,
)

/** A stable (non-prerelease), immutable release; its ZIP is verified after download. */
@Immutable
data class RepoRelease(
    val tag: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val publishedAt: String,
    val changelogHtml: String?,
    /** 发布 ZIP 的固定地址（https，受下载白名单约束）。 */
    val zipUrl: String,
    val zipName: String,
    val zipSize: Long,
    /** 用于下载后完整性校验的 SHA-256（64 位小写十六进制）。 */
    val sha256: String,
    val tagCommit: String,
    /** https only (validated by the parser). */
    val releaseUrl: String,
    /** https only, or null. */
    val attestationUrl: String?,
    val immutable: Boolean,
)

/** 模块详情：基础信息 + README + 发布列表；详情可能与列表来自同一 build 的缓存。 */
@Immutable
data class RepoModuleDetail(
    val module: RepoModule,
    val license: String?,
    val homepage: String?,
    val support: String?,
    val sourceFullName: String,
    val sourceUrl: String,
    val sourceCommit: String,
    val readmeHtml: String?,
    /** The module has a README but it could not be loaded (show a retry, not "no README"). */
    val readmeFailed: Boolean,
    /** Base URL relative resources in the readme are resolved against (the site root). */
    val readmeBaseUrl: String,
    val releases: List<RepoRelease>,
    /** 注册表健康状态为 stale（上游数据未及时更新）。 */
    val healthStale: Boolean,
    /** 对外网页 URL，按当前 UI 语言拼接。 */
    val webUrl: String,
    /** 详情是否来自磁盘缓存（网络失败时的回退）。 */
    val fromCache: Boolean,
)
