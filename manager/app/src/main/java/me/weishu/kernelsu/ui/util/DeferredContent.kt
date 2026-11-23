package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

/**
 * Returns true only after the navigation transition animation has completed
 * and an additional buffer frame has passed.
 *
 * Timeline:
 * - During animation: returns false → page shows lightweight placeholder (smooth animation)
 * - Animation ends + 1 frame: returns true → heavy content composes
 *   (stutter is invisible because the page is already static)
 *
 * The value is sticky — once true it never reverts to false,
 * so content stays visible during exit transitions.
 *
 * 说明：用于把重内容推迟到转场动画结束后再组合，避免动画掉帧；
 * 返回值一旦变为 true 就不会回退，保证退出动画期间内容不会突然消失。
 */
@Composable
fun rememberContentReady(): Boolean {
    // 导航转场是否仍在进行。
    val transitionRunning = LocalNavTransitionScope.current.isRunning
    val ready = remember { mutableStateOf(false) }

    // 转场结束且尚未就绪时，再等一帧让布局稳定后放行。
    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    return ready.value
}
