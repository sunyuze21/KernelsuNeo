package me.weishu.kernelsu.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

// 列表安静多久算“稳定”，用于判定数据加载是否结束
private const val LIST_QUIET_MILLIS = 250L
// 钉住顶部的最大时长，避免数据异常时无限循环滚动
private const val LIST_SETTLE_TIMEOUT_MS = 3000L

/**
 * 用可变引用保存上一次的 keys，绕过 LaunchedEffect 重启后丢失上次值的问题，
 * 使得“首次组合不滚动、仅变化时滚动”的语义得以成立。
 */
private class KeysRef {
    var value: List<Any?>? = null
}

/**
 * Flicker-free scroll-to-top when [keys] (sort/filter/search/refresh token) change while the
 * composition is alive. Never scrolls on first composition or nav-return (NavDisplay recreates the
 * screen and resets `remember`), so returning from a detail won't auto-scroll. For actions that
 * don't change sort/filter (e.g. pull-to-refresh) pass a token that only increments then.
 *
 * [isBusy]: pass `{ isRefreshing }` for slow reloads so pinning lasts until the reload finishes;
 * read it via rememberUpdatedState (uiState is a plain value param).
 */
@Composable
fun ScrollToTopOnChange(
    listState: LazyListState,
    vararg keys: Any?,
    onScrolledToTop: () -> Unit = {},
    isBusy: () -> Boolean = { false },
    observedList: () -> Any?,
) {
    val keysList = keys.asList()
    // SideEffect 阶段先跳位：在列表重建引起位置恢复之前把目标置为 0，减少闪动
    val sideRef = remember { KeysRef() }
    SideEffect {
        val prev = sideRef.value
        if (prev != null && prev != keysList) {
            listState.requestScrollToItem(0)
        }
        sideRef.value = keysList
    }
    val effectRef = remember { KeysRef() }
    // 仅当 keys 真正变化时才滚动；prev == null 表示首次组合或导航返回，此时保持原位
    LaunchedEffect(*keys) {
        val prev = effectRef.value
        effectRef.value = keysList
        if (prev != null && prev != keysList) {
            scrollToTopAfterListSettles(listState, isBusy = isBusy, observedList = observedList)
            onScrolledToTop()
        }
    }
}

/**
 * Pins the list to the top each frame until [observedList] is quiet for [LIST_QUIET_MILLIS] and
 * [isBusy]() is false (capped by [LIST_SETTLE_TIMEOUT_MS]), overriding LazyColumn's key-based
 * position restore. [observedList] must read a snapshot-trackable State (bridge uiState via
 * rememberUpdatedState), otherwise settle detection falls back to a fixed timeout.
 */
suspend fun scrollToTopAfterListSettles(
    listState: LazyListState,
    isBusy: () -> Boolean = { false },
    observedList: () -> Any?,
): Unit = coroutineScope {
    var settled = false
    // 旁路协程监听数据是否安静下来；超时退出内层等待以复查 isBusy
    val watcher = launch {
        while (true) {
            val changed = withTimeoutOrNull(LIST_QUIET_MILLIS.milliseconds) {
                snapshotFlow(observedList).drop(1).first()
            }
            if (changed == null && !isBusy()) break
        }
        settled = true
    }
    // 每帧请求跳到顶部，直到数据稳定或到达总超时，用逐帧钉住抵消 LazyColumn 的位置恢复
    withTimeoutOrNull(LIST_SETTLE_TIMEOUT_MS.milliseconds) {
        while (!settled) {
            listState.requestScrollToItem(0)
            withFrameNanos { }
        }
    }
    watcher.cancel()
    // 收尾再跳一次，覆盖稳定后最后一批布局调整
    listState.requestScrollToItem(0)
}
