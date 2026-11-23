package me.weishu.kernelsu.ui.screen.modulerepo

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.RepoModule
import me.weishu.kernelsu.data.model.RepoModuleDetail
import me.weishu.kernelsu.data.registry.UnsupportedSchemaException
import me.weishu.kernelsu.data.script.ScriptRunRecord
import me.weishu.kernelsu.ui.component.SearchStatus
import java.text.Collator
import java.util.Locale

/** 仓库列表支持的排序方式；顺序变化会影响旧版本序号的迁移，勿随意调整。 */
enum class RepoSort {
    UPDATED,
    NAME,
    STARS;

    /** Returns [modules] in this order; ties (and unknown star counts) fall back to newest release first. */
    fun sort(modules: List<RepoModule>, locale: Locale = Locale.getDefault()): List<RepoModule> {
        if (modules.isEmpty()) return modules
        return when (this) {
            UPDATED -> modules.sortedByDescending { it.latestReleaseEpoch }
            NAME -> {
                // 按系统语言排序，保证非英文模块名符合本地阅读习惯
                val collator = Collator.getInstance(locale)
                modules.sortedWith(compareBy(collator) { it.moduleName })
            }
            // Unknown counts (null) sort after every known count, including 0.
            STARS -> modules.sortedWith(
                compareByDescending<RepoModule> { it.stars ?: -1 }.thenByDescending { it.latestReleaseEpoch }
            )
        }
    }

    companion object {
        /**
         * Restores the saved order. New versions store the enum name; older ones stored the
         * ordinal of `UPDATED, CREATED, NAME, STARS`, which is migrated here (CREATED no
         * longer exists and falls back to UPDATED).
         */
        fun fromStored(name: String?, legacyOrdinal: Int?): RepoSort {
            // 优先按名称匹配，兼容后续新增排序项；名称缺失时再按旧序号迁移
            name?.let { n -> entries.firstOrNull { it.name == n }?.let { return it } }
            return when (legacyOrdinal) {
                2 -> NAME
                3 -> STARS
                else -> UPDATED
            }
        }
    }
}

/** 仓库页的 UI 快照；列表与搜索状态相互独立，便于退出搜索后恢复。 */
data class ModuleRepoUiState(
    val isRefreshing: Boolean = false,
    /** A module list (possibly empty) has been loaded at least once. */
    val hasLoaded: Boolean = false,
    val sortOrder: RepoSort = RepoSort.UPDATED,
    val offline: Boolean = false,
    val modules: List<RepoModule> = emptyList(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<RepoModule> = emptyList(),
    val error: Throwable? = null,
    /** The list shown is the last good cached copy (network or parsing failed). */
    val stale: Boolean = false,
    /** Installed module id -> versionCode, for "installed" / "update" badges. */
    val installedVersions: Map<String, Int> = emptyMap(),
) {
    /** Message to show instead of the list when it is empty or could not be loaded, or null while loading. */
    val emptyStateMessage: Int?
        get() = when {
            // 已有内容或正在首次刷新时不显示空态/错误文案，避免闪烁
            modules.isNotEmpty() || isRefreshing && !offline -> null
            error is UnsupportedSchemaException -> R.string.module_repo_unsupported_schema
            offline -> R.string.network_offline
            error != null -> R.string.module_repo_load_failed
            hasLoaded -> R.string.module_repo_empty
            else -> null
        }
}

/**
 * Compact star count for list rows: "999", "1.2k", "12.3k", "123k", "1.2M". Integer arithmetic
 * (rounded down) keeps it independent of the device locale, like GitHub's own counters.
 */
fun formatStarCount(count: Int): String {
    /** 以千/M 为单位压缩，tenths 为保留一位小数的整数表示，避免浮点误差。 */
    fun compact(value: Int, unit: Int, suffix: String): String {
        if (value >= unit * 100) return "${value / unit}$suffix"
        val tenths = value / (unit / 10)
        return if (tenths % 10 == 0) "${tenths / 10}$suffix" else "${tenths / 10}.${tenths % 10}$suffix"
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count, 1_000, "k")
        else -> compact(count, 1_000_000, "M")
    }
}

/** GitHub stargazers page of an "owner/name" repository (validated by the registry parser). */
fun starsUrl(starsRepository: String?): String? =
    starsRepository?.let { "https://github.com/$it/stargazers" }

/** Installed state of a registry module; updates are detected by versionCode only. */
enum class RepoInstallState { NONE, INSTALLED, UPDATABLE }

/** Scripts are never installed, so they have no installed / update state. */
fun repoInstallStateOf(module: RepoModule, installedVersions: Map<String, Int>): RepoInstallState =
    if (module.isScript) RepoInstallState.NONE else repoInstallState(installedVersions[module.moduleId], module.latestVersionCode)

/** 由本地已装版本号与仓库最新版本号推导徽标状态；版本号相同即视为已是最新。 */
fun repoInstallState(installedVersionCode: Int?, latestVersionCode: Int): RepoInstallState = when {
    installedVersionCode == null -> RepoInstallState.NONE
    latestVersionCode > installedVersionCode -> RepoInstallState.UPDATABLE
    else -> RepoInstallState.INSTALLED
}

/** 仓库列表页的回调集合；Material/Miuix 两套 UI 共用。 */
@Immutable
data class ModuleRepoActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onSetSortOrder: (RepoSort) -> Unit,
    val onOpenRepoDetail: (RepoModule) -> Unit,
)

/** 仓库详情页的 UI 状态；detail 为空表示仍在加载或加载失败。 */
@Immutable
data class ModuleRepoDetailUiState(
    val module: RepoModuleArg,
    val detail: RepoModuleDetail?,
    val loaded: Boolean,
    /** The module is not in the current index (e.g. suspended): no install entry is offered. */
    val notInIndex: Boolean,
    val loadFailed: Boolean,
    val installedVersionCode: Int?,
    /** Last local run, for scripts. */
    val lastRun: ScriptRunRecord? = null,
) {
    /** 仓库提供的外部主页；未加载完成时为空串。 */
    val webUrl: String get() = detail?.webUrl.orEmpty()

    /** One-shot script: releases are run, not installed. */
    val isScript: Boolean get() = detail?.module?.isScript ?: module.script
    /** 只有详情加载成功且模块仍在索引中时才允许安装，避免安装下架模块。 */
    val canInstall: Boolean get() = detail != null && !notInIndex
}

/** 仓库详情页的回调集合。 */
@Immutable
data class ModuleRepoDetailActions(
    val onBack: () -> Unit,
    val onOpenWebUrl: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    /** Installs a downloaded module ZIP, or opens the run page for a script. */
    val onInstallModule: (android.net.Uri) -> Unit,
    val onRetry: () -> Unit,
)
