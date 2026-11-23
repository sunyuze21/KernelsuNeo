package me.weishu.kernelsu.ui.screen.module

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.script.ScriptRepository
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.modulerepo.downloadFailureMessage
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.util.download
import me.weishu.kernelsu.ui.util.module.Shortcut
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel
import me.weishu.kernelsu.ui.webui.WebUIActivity

/**
 * 模块页入口：组装 ViewModel、生命周期刷新与全部回调，再按主题分发给 Material/Miuix 实现。
 *
 * 该 Composable 只做状态编排，不持有列表 UI；两套皮肤共享同一份 actions，
 * 避免逻辑分叉。isCurrentPage 用于区分当前可见页，避免在后台页触发刷新。
 */
@Composable
fun ModulePager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resource = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel = viewModel<ModuleViewModel>()
    val scope = rememberCoroutineScope()
    val rawUiState by viewModel.uiState.collectAsStateWithLifecycle()

    // WebUI 返回后模块自身状态可能被改写，因此回到页面时无条件重新读取列表
    val webUILauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { viewModel.fetchModuleList(resort = false) }

    // Request notification permission for download progress notifications
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Download works regardless of result */ }
    // 用 rememberUpdatedState 保证回调里读到的是最新页码状态，而不是首次组合时的值
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    // hasActivated 防止页面切回前台时重复初始化偏好与申请权限，只需一次
    var hasActivated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            if (!hasActivated) {
                hasActivated = true
                viewModel.refreshEnvironmentState()
                viewModel.initializePreferences()
                val state = viewModel.uiState.value
                if (!state.hasLoaded && !state.isRefreshing) {
                    viewModel.fetchModuleList()
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else if (!rawUiState.searchStatus.isCollapsed()) {
            // 离开页面时收起搜索，避免下次进入仍停留在过滤结果上
            viewModel.updateSearchStatus(rawUiState.searchStatus.copy(searchText = "", current = SearchStatus.Status.COLLAPSED))
        }
    }

    // 首次恢复只记录标记；之后的恢复才刷新，避免首帧重复请求
    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.isRefreshing) {
                viewModel.fetchModuleList(
                    checkUpdate = !state.hasLoaded || viewModel.isNeedRefresh,
                    resort = !state.hasLoaded || viewModel.isNeedRefresh,
                )
            }
        }
        initialResumeHandled.value = true
        onPauseOrDispose {}
    }

    val actions = ModuleActions(
        onRefresh = {
            viewModel.fetchModuleList(checkUpdate = true)
        },
        onSearchStatusChange = {
            viewModel.updateSearchStatus(it)
        },
        onSearchTextChange = { text ->
            viewModel.updateSearchText(text)
        },
        onClearSearch = {
            viewModel.updateSearchText("")
        },
        onRequestUpdateConfirmation = { module, updateInfo ->
            viewModel.requestUpdateConfirmation(module, updateInfo)
        },
        onRequestUninstallConfirmation = { module ->
            viewModel.requestUninstallConfirmation(module)
        },
        onDismissConfirmRequest = {
            viewModel.dismissConfirmRequest()
        },
        onConfirmUpdate = { request ->
            scope.launch {
                download(
                    url = request.downloadUrl,
                    fileName = request.fileName,
                    // Registry releases are size + SHA-256 checked, kept app-private and
                    // re-verified by the flash step; other updateJson sources are unchanged.
                    expected = request.expected,
                    onFailed = { failed ->
                        viewModel.emitEffect(
                            ModuleEffect.Toast(downloadFailureMessage(context, failed, request.fileName))
                        )
                    },
                    onDownloaded = { uri ->
                        // 页面已不在前台时不跳转刷写，否则会在错误页面弹出安装流程
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
                            viewModel.markNeedRefresh()
                        }
                    },
                    onDownloading = {
                        viewModel.emitEffect(
                            ModuleEffect.Toast(
                                resource.getString(R.string.module_downloading).format(request.module.name)
                            )
                        )
                    },
                )
            }
            viewModel.dismissConfirmRequest()
        },
        onOpenRepo = { navigator.push(Route.ModuleRepo) },
        onToggleSortActionFirst = {
            viewModel.toggleSortActionFirst()
        },
        onToggleSortEnabledFirst = {
            viewModel.toggleSortEnabledFirst()
        },
        onOpenWebUi = { module ->
            // WebUI 通过模块 id 构造私有 uri，由 WebUIActivity 解析出模块目录
            webUILauncher.launch(
                Intent(context, WebUIActivity::class.java)
                    .setData(
                        Shortcut.buildShortcutUri(module.id, ShortcutType.WebUI)
                    )
            )
        },
        onToggleModule = { module ->
            viewModel.toggleModule(module)
        },
        onUninstallModule = { module ->
            viewModel.uninstallModule(module)
        },
        onUndoUninstallModule = { module ->
            viewModel.undoUninstallModule(module)
        },
        onOpenFlash = { uris ->
            if (uris.size == 1) {
                // A one-shot script ZIP is offered to be run instead (or installed anyway).
                scope.launch {
                    val script = ScriptRepository.peekOneshot(uris.first())
                    if (script != null) {
                        navigator.push(
                            Route.ScriptRun(ScriptRunArg(uris.first(), offerInstall = true, displayName = script.name))
                        )
                    } else {
                        navigator.push(Route.Flash(FlashIt.FlashModules(uris)))
                    }
                    viewModel.markNeedRefresh()
                }
            } else if (uris.isNotEmpty()) {
                navigator.push(Route.Flash(FlashIt.FlashModules(uris)))
                viewModel.markNeedRefresh()
            }
        },
        onExecuteModuleAction = { module ->
            navigator.push(Route.ExecuteModuleAction(module.id))
            viewModel.markNeedRefresh()
        },
    )

    // 两套 UI 共用同一 uiState 与 actions，仅渲染层不同
    when (uiMode) {
        UiMode.Miuix -> ModulePagerMiuix(
            uiState = rawUiState,
            confirmDialogState = rawUiState.confirmDialogState,
            moduleEvent = viewModel.moduleEvent,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> ModulePagerMaterial(
            uiState = rawUiState,
            confirmDialogState = rawUiState.confirmDialogState,
            moduleEvent = viewModel.moduleEvent,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
