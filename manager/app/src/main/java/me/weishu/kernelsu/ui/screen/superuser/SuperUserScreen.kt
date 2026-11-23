package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel

/**
 * 超级用户分页入口：首次可见时加载应用列表，离开时折叠搜索，
 * 返回前台后按需刷新（例如用户在应用详情页修改了授权）。
 */
@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<SuperUserViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // isCurrentPage 可能频繁变化，用 rememberUpdatedState 避免重启副作用
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    // 区分首次 onResume：首次进入由 LaunchedEffect 负责加载，避免重复请求
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            // 仅首次可见且没有正在进行的刷新时才主动加载
            val state = viewModel.uiState.value
            if (!state.hasLoaded && !state.isRefreshing) {
                viewModel.initializePreferences()
                viewModel.loadAppList()
            }
        } else if (!uiState.searchStatus.isCollapsed()) {
            // 切到其他分页时收起搜索，防止返回后残留旧搜索态
            viewModel.updateSearchStatus(uiState.searchStatus.copy(searchText = "", current = SearchStatus.Status.COLLAPSED))
        }
    }

    LifecycleResumeEffect(Unit) {
        // 从应用详情页返回时 UID 配置可能已变，需要按标记重新加载
        if (initialResumeHandled.value && latestIsCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.isRefreshing) {
                if (!state.hasLoaded) {
                    viewModel.initializePreferences()
                    viewModel.loadAppList()
                } else if (viewModel.isNeedRefresh) {
                    viewModel.loadAppList(resort = false)
                }
            }
        }
        initialResumeHandled.value = true
        onPauseOrDispose {}
    }

    val onSearchTextChange: (String) -> Unit = viewModel::updateSearchText
    val onToggleShowSystemApps: () -> Unit = {
        viewModel.toggleShowSystemApps()
    }
    val onToggleShowOnlyPrimaryUserApps: () -> Unit = {
        viewModel.toggleShowOnlyPrimaryUserApps()
    }
    // 按 UID 打开授权详情；先标记待刷新，返回时才能拿到最新 profile
    val onOpenProfile: (GroupedApps) -> Unit = { group ->
        navigator.push(Route.AppProfile(group.uid))
        viewModel.markNeedRefresh()
    }
    val actions = SuperUserActions(
        onRefresh = { viewModel.loadAppList(force = true) },
        onOpenSulog = { navigator.push(Route.Sulog) },
        onSearchTextChange = onSearchTextChange,
        onSearchStatusChange = viewModel::updateSearchStatus,
        onClearSearch = { onSearchTextChange("") },
        onToggleShowSystemApps = onToggleShowSystemApps,
        onToggleShowOnlyPrimaryUserApps = onToggleShowOnlyPrimaryUserApps,
        onUpdateSortConfig = { viewModel.updateSortConfig(it) },
        onOpenProfile = onOpenProfile,
    )

    // 主题切换只替换列表渲染，ViewModel 与加载逻辑保持不变
    when (LocalUiMode.current) {
        UiMode.Miuix -> SuperUserPagerMiuix(
            uiState = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> SuperUserPagerMaterial(
            uiState = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
