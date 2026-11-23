package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.viewmodel.AppSortConfig

/**
 * 以 UID 聚合后的应用分组：同一 UID 可对应多个包（共享 UID/多开），
 * 授权与 profile 作用于 UID，因此列表以组为单位展示。
 */
@Immutable
data class GroupedApps(
    val uid: Int,
    val apps: List<AppInfo>,
    val primary: AppInfo,
    /** 组内任一应用已获得 root 授权时展示 ROOT 标签。 */
    val anyAllowSu: Boolean,
    /** 组内任一应用存在自定义 profile。 */
    val anyCustom: Boolean,
    /** 该 UID 的 profile 要求卸载模块，展示 UMOUNT 标签。 */
    val shouldUmount: Boolean,
    val ownerName: String? = null,
    /** 搜索命中的包标识，用于高亮具体应用。 */
    val matchedIdentifiers: Set<String> = emptySet(),
)

/**
 * 超级用户页状态：同时保存主列表、搜索列表和“最近安装”分组。
 * [userIds] 用于判断是否存在多用户，从而决定是否展示“仅主用户应用”过滤项。
 */
@Stable
data class SuperUserUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val groupedApps: List<GroupedApps> = emptyList(),
    val recentlyInstalledResults: List<GroupedApps> = emptyList(),
    val userIds: List<Int> = emptyList(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<GroupedApps> = emptyList(),
    val showSystemApps: Boolean = false,
    val showOnlyPrimaryUserApps: Boolean = false,
    val sortConfig: AppSortConfig = AppSortConfig(),
    val error: Throwable? = null
)

/** 超级用户页回调集合，供两套主题实现复用。 */
@Immutable
data class SuperUserActions(
    val onRefresh: () -> Unit,
    val onOpenSulog: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onClearSearch: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleShowOnlyPrimaryUserApps: () -> Unit,
    val onUpdateSortConfig: (AppSortConfig) -> Unit,
    val onOpenProfile: (GroupedApps) -> Unit,
)

/** 状态标签的文案与配色；做成数据类便于按主题分别构造。 */
@Immutable
data class StatusMeta(
    val label: String,
    val bg: Color,
    val fg: Color
)
