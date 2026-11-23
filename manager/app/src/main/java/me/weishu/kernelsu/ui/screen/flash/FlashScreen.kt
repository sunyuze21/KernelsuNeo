package me.weishu.kernelsu.ui.screen.flash

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.isSoftRebootPreferred
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.reboot

/**
 * 刷写页：展示实时日志，任务结束后提供重启或关闭操作。
 * 刷写进行中会禁用返回，并把该状态同步给外层导航容器。
 */
@Composable
fun FlashScreen(flashIt: FlashIt, onBackEnabledChange: (Boolean) -> Unit) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    // 日志单独用 StringBuilder 累积，避免每行输出都触发一次文本拼接的状态拷贝
    val logContent = remember { StringBuilder() }
    var showRebootAction by rememberSaveable { mutableStateOf(false) }
    var flashingStatus by rememberSaveable { mutableStateOf(FlashingStatus.FLASHING) }
    var actionInProgress by rememberSaveable { mutableStateOf(false) }
    // 延迟加载（越狱）模式下刷写 boot 可能破坏越狱环境，必须先给出警告
    val needJailbreakWarning = flashIt is FlashIt.FlashBoot && Natives.isLateLoadMode
    // Soft reboot keeps the jailbreak and still applies modules
    val softReboot = flashIt is FlashIt.FlashModules && isSoftRebootPreferred()
    // 需要警告时初始不启用刷写，等待用户确认后再置为 true
    var flashingEnabled by rememberSaveable { mutableStateOf(!needJailbreakWarning) }
    val uiMode = LocalUiMode.current
    val snackbarHost = remember { SnackbarHostState() }
    // 仅进行中禁用返回；成功/失败后允许离开
    val backEnabled = flashingStatus != FlashingStatus.FLASHING

    // 把可返回状态同步给外层，SideEffect 保证每次成功重组后都刷新
    SideEffect { onBackEnabledChange(backEnabled) }
    // 拦截系统返回手势，防止刷写中途退出导致进程被杀
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !backEnabled,
        onBackCompleted = {},
    )

    // Material 使用 Snackbar，Miuix 使用 Toast，二者互斥以免重复提示
    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 刷写副作用与 UI 解耦：这里只把输出和状态写回 Compose 状态
    FlashEffect(
        flashIt = flashIt,
        text = text,
        logContent = logContent,
        onTextUpdate = { text = it },
        onShowRebootChange = { showRebootAction = it },
        onFlashingStatusChange = { flashingStatus = it },
        enabled = flashingEnabled,
    )

    // 警告是否展示取决于“需要警告且尚未确认”
    val state = FlashUiState(
        text = text,
        showRebootAction = showRebootAction,
        flashingStatus = flashingStatus,
        showJailbreakWarning = needJailbreakWarning && !flashingEnabled,
        rebootLabelRes = if (softReboot) R.string.reboot_soft else R.string.reboot,
        actionInProgress = actionInProgress,
    )
    val actions = FlashScreenActions(
        onBack = dropUnlessResumed { if (backEnabled) navigator.pop() },
        onSaveLog = saveLog(logContent, scope) { showMessage(it) },
        onReboot = {
            // 先锁定按钮，reboot 是耗时 IO，期间禁止重复触发
            actionInProgress = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    reboot(if (softReboot) "soft_reboot" else "")
                }
            }
        },
        onClose = dropUnlessResumed { navigator.pop() },
        onConfirmJailbreakWarning = { flashingEnabled = true },
        onDismissJailbreakWarning = dropUnlessResumed { navigator.pop() },
    )

    // 两套主题共享同一份刷写状态，避免切换主题时丢失日志或结果
    when (LocalUiMode.current) {
        UiMode.Miuix -> FlashScreenMiuix(state, actions)
        UiMode.Material -> FlashScreenMaterial(state, actions, snackbarHost)
    }
}
