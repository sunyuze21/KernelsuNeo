package me.weishu.kernelsu.ui.component

import androidx.compose.runtime.Composable
import me.weishu.kernelsu.Natives

/**
 * 仅在内核侧 KernelSU 接口可用时才渲染 [content]。
 * 非 Manager 身份或拿不到版本号时（如未加载内核模块）直接不渲染，
 * 避免下层 Composable 访问到不存在的内核能力而崩溃。
 */
@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    // 只有 Manager 身份才可能拿到有效的内核版本号
    val isManager = Natives.isManager
    // version 为空表示内核模块缺失或不可用，此时不渲染内容
    val ksuVersion = if (isManager) Natives.version else null

    if (ksuVersion != null) {
        content()
    }
}
