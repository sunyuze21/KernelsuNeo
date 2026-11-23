package me.weishu.kernelsu.ui.component

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent

/**
 * 透明焦点拦截层：抢占焦点后把按键事件交给 [predicate]，返回 true 表示已消费。
 * 常用于阻止底层内容（如列表、返回键）响应按键，副作用是会夺取焦点，
 * 因此仅在需要拦截的短暂场景使用，避免长期占用焦点影响输入控件。
 */
@Composable
fun KeyEventBlocker(predicate: (KeyEvent) -> Boolean) {
    // 复用一个 FocusRequester，保证重组时仍是同一个焦点请求者
    val requester = remember { FocusRequester() }
    Box(
        Modifier
            .onKeyEvent {
                predicate(it)
            }
            .focusRequester(requester)
            .focusable()
    )
    // 进入组合后主动申请焦点，否则 onKeyEvent 收不到任何按键
    LaunchedEffect(Unit) {
        requester.requestFocus()
    }
}
