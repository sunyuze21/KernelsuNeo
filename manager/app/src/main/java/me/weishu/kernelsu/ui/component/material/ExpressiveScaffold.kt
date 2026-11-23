package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Expressive 风格的页面骨架。
 *
 * 与原生 Scaffold 的差异仅在默认容器色：使用 `surfaceContainer` 而非纯背景色，
 * 让页面与内层卡片形成轻微色阶差。`contentColor` 通过 [contentColorFor] 推导，
 * 保证调用方自定义容器色时文字仍满足对比度要求。
 *
 * @param modifier 外层修饰符
 * @param topBar 顶栏插槽，通常传 ExpressiveTopAppBar
 * @param bottomBar 底栏插槽
 * @param snackbarHost Snackbar 宿主插槽
 * @param floatingActionButton 悬浮按钮插槽
 * @param floatingActionButtonPosition 悬浮按钮位置，默认靠右下
 * @param containerColor 页面容器色，影响整体明暗基调
 * @param contentColor 内容默认前景色
 * @param contentWindowInsets 内容区安全区域，默认遵循系统栏避让
 * @param content 页面内容，接收 Scaffold 计算出的内边距
 */
@Composable
fun ExpressiveScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

/**
 * 构造顶栏配色。
 *
 * 滚动后的容器色默认与静止态一致，避免顶栏在滚动时出现明显的色块跳变；
 * 需要阴影/变色反馈的页面可显式传入不同的 `scrolledContainerColor`。
 */
@Composable
fun expressiveTopAppBarColors(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor: Color = containerColor,
): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = containerColor,
    scrolledContainerColor = scrolledContainerColor,
)
