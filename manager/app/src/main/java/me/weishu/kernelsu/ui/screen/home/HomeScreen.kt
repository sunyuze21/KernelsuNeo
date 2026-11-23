package me.weishu.kernelsu.ui.screen.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.magica.MagicaService
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.HomeViewModel
import kotlin.time.Duration.Companion.milliseconds

/**
 * 首页 Pager 的容器：负责装载首页状态、处理回到前台时的刷新时机，
 * 并按当前 UI 模式分发到 Miuix 或 Material 两套布局。
 * 只有页面首次成为当前页或从后台恢复时才刷新，避免切换页签时重复查询内核。
 */
@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    // 首页状态保存在 ViewModel 中，重组或配置变更时不会丢失已读取的内核信息。
    val viewModel = viewModel<HomeViewModel>()
    // 使用 lifecycle-aware 收集：页面不可见时停止订阅，减少无效重组。
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    // 越狱激活是异步且可能失败的操作，用加载框覆盖整个等待过程。
    val loadingDialog = rememberLoadingDialog()
    // 用于超时兜底计时，避免在组合函数内直接阻塞等待。
    val scope = rememberCoroutineScope()
    // 生命周期回调中读取最新的页签状态，防止闭包捕获过期的 isCurrentPage。
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    // 标记首次 onResume 已处理，避免冷启动时与 LaunchedEffect 重复刷新。
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    // 记录首页是否已经激活过；配置变更后保留，防止重复拉取状态。
    var hasActivated by rememberSaveable { mutableStateOf(false) }
    // 仅首次成为当前页时刷新，后续切换由生命周期回调负责。
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasActivated) {
            hasActivated = true
            viewModel.refresh()
        }
    }

    // 从后台回到前台且仍是当前页时刷新，确保内核版本与 root 状态不是旧值。
    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    // 集中构造回调，两套 UI 只消费动作，不直接依赖 Navigator 或 Service。
    val actions = HomeActions(
        onInstallClick = { navigator.push(Route.Install) },
        onOpenUrl = uriHandler::openUri,
        onJailbreakClick = {
            loadingDialog.showLoading()
            context.startService(Intent(context, MagicaService::class.java))
            // Manager will be force-stopped and restarted by late-load on success.
            // If that doesn't happen within timeout, jailbreak likely failed.
            scope.launch(Dispatchers.IO) {
                delay(30_000.milliseconds)
                withContext(Dispatchers.Main) {
                    loadingDialog.hide()
                    Toast.makeText(context, R.string.jailbreak_timeout, Toast.LENGTH_LONG).show()
                }
            }
        },
    )

    // 根据用户选择的 UI 模式渲染不同设计语言，状态与动作保持同一份。
    when (LocalUiMode.current) {
        UiMode.Miuix -> HomePagerMiuix(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> HomePagerMaterial(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}
