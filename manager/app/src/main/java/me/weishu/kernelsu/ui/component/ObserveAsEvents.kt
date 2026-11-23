package me.weishu.kernelsu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle-aware one-shot event collection: collects [events] only while >= [minActiveState]
 * (default STARTED). Pair with `Channel(BUFFERED).receiveAsFlow()` so events emitted during a pause
 * are buffered and delivered on resume.
 */
@Composable
fun <T> ObserveAsEvents(
    events: Flow<T>,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onEvent: suspend (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // 随 lifecycle 状态启停收集：低于 minActiveState 时停止，恢复后继续，防止后台消费事件
    LaunchedEffect(events, lifecycleOwner, minActiveState) {
        lifecycleOwner.repeatOnLifecycle(minActiveState) {
            events.collect { onEvent(it) }
        }
    }
}
