package me.weishu.kernelsu.ui.component.material

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * 支持左右滑动关闭的 Snackbar 宿主。
 *
 * 在原生的 [SnackbarHost] 外面包了一层 [SwipeToDismissBox]：滑动删除的语义必须
 * 显式映射到 `currentSnackbarData.dismiss()`，否则组件只会回弹而不真正出队。
 * 新消息到来时通过 [LaunchedEffect] 把滑块状态 `snapTo` 回 `Settled`，
 * 保证上一条被滑走的位置不会影响下一条的初始视觉位置。
 *
 * @param modifier 传递给内层 SnackbarHost 的修饰符
 * @param hostState Snackbar 队列状态，决定当前展示哪条消息
 * @param snackBar 单条消息的渲染方式，默认沿用 Material3 样式
 */
@Composable
fun SnackBarHost(
    modifier: Modifier = Modifier,
    hostState: SnackbarHostState,
    snackBar: @Composable (SnackbarData) -> Unit = { Snackbar(it) },
) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {},
        onDismiss = {
            hostState.currentSnackbarData?.dismiss()
        }
    ) {
        SnackbarHost(
            modifier = modifier,
            hostState = hostState,
            snackbar = snackBar,
        )
    }
    LaunchedEffect(hostState.currentSnackbarData) {
        if (hostState.currentSnackbarData == null) return@LaunchedEffect
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }
}
