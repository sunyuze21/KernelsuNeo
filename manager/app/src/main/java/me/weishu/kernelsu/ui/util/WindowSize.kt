package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * 判断是否应显示双栏布局。
 *
 * 采用 Material 的窗口尺寸断点：宽度 ≥840dp 必定双栏；平板/折叠屏在
 * 600dp 以上且长宽比不极端时也使用双栏，避免竖屏平板空间浪费。
 */
@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    // 用 dp 判断，需先把像素尺寸除以当前 density。
    val deviceDensity = LocalResources.current.displayMetrics.density
    val widthDp = windowInfo.containerSize.width / deviceDensity
    val heightDp = windowInfo.containerSize.height / deviceDensity
    return widthDp >= 840f || (widthDp >= 600f && heightDp / widthDp < 1.2f)
}
