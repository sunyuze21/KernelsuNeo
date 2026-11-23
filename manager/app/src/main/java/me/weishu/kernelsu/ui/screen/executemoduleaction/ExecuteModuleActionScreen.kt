package me.weishu.kernelsu.ui.screen.executemoduleaction

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

/**
 * 执行模块 action 脚本的容器页。
 *
 * 该页面本身不持有业务状态，只负责把脚本输出、完成标记与退出行为
 * 组装成 [ExecuteModuleActionUiState]/[ExecuteModuleActionScreenActions]，
 * 再分派给 Material 或 Miuix 实现。从快捷方式启动时，完成后要直接
 * 结束任务而不是留在导航栈里。
 */
@Composable
fun ExecuteModuleActionScreen(moduleId: String, fromShortcut: Boolean = false) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    // 脚本输出文本较大，用 rememberSaveable 保证旋屏后不丢失且可恢复。
    var text by rememberSaveable { mutableStateOf("") }
    // 完整日志（含 stderr）单独缓冲，供“保存日志”使用。
    val logContent = remember { StringBuilder() }
    var isComplete by rememberSaveable { mutableStateOf(false) }
    val uiMode = LocalUiMode.current
    val snackbarHost = remember { SnackbarHostState() }
    // 快捷方式入口没有可返回的导航栈，只能结束整个任务。
    val exitExecute = {
        if (fromShortcut && activity != null) {
            activity.finishAndRemoveTask()
        } else {
            navigator.pop()
        }
    }

    // 按当前 UI 模式选择提示方式：Material 用 Snackbar，Miuix 用 Toast。
    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Always auto disable from shortcuts
    LaunchedEffect(isComplete) {
        if (isComplete) {
            if (fromShortcut) {
                exitExecute()
            }
        }
    }

    ExecuteModuleActionEffect(
        moduleId = moduleId,
        text = text,
        logContent = logContent,
        fromShortcut = fromShortcut,
        onTextUpdate = { text = it },
        onComplete = { isComplete = true },
        onExit = exitExecute
    )

    val state = ExecuteModuleActionUiState(
        text = text,
        isComplete = isComplete,
    )
    val actions = ExecuteModuleActionScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        // 保存日志是副作用，回调内部走协程并复用同一套提示逻辑。
        onSaveLog = saveLog(logContent, scope) { showMessage(it) },
        onClose = exitExecute,
    )

    // 两种 UI 共用同一份状态与动作，保证输出和执行语义完全一致。
    when (uiMode) {
        UiMode.Miuix -> ExecuteModuleActionScreenMiuix(state, actions)
        UiMode.Material -> ExecuteModuleActionScreenMaterial(state, actions, snackbarHost)
    }
}
