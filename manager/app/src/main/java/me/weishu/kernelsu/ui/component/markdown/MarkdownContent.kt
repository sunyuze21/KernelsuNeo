package me.weishu.kernelsu.ui.component.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

/**
 * Markdown 内容容器。
 *
 * 渲染完成后把 WebView 的透明度淡入、同时淡出底部占位指示器，避免 WebView
 * 首次布局时出现白屏闪烁。`loaded` 以内容与模式为 key，内容切换时会重新
 * 播放一次过渡。Miuix 模式下容器高度随内容变化做 `animateContentSize`；
 * Material 模式则由固定的容器色区分内容区域，两者不能用同一套动画，
 * 否则会与各自的主题层级冲突。
 *
 * @param content Markdown 源文本或纯文本
 * @param isMarkdown 为 true 时按 Markdown 解析，否则原样展示
 * @param restrictedBaseUrl 受限渲染时的站点根地址，null 表示使用应用内资源
 */
@Composable
fun MarkdownContent(
    content: String,
    isMarkdown: Boolean,
    restrictedBaseUrl: String? = null,
) {
    val uiMode = LocalUiMode.current
    // 内容或模式变化时重置，让新内容重新走一遍淡入过渡
    var loaded by remember(content, isMarkdown) { mutableStateOf(false) }
    // 正文淡入较慢、占位淡出较快，交接时不会出现两层同时半透明导致的发灰
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "MarkdownContentAlpha",
    )
    val placeholderAlpha by animateFloatAsState(
        targetValue = if (loaded) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "MarkdownContentPlaceholderAlpha",
    )
    // Miuix 主题自身处理内容底色，传 null 让 WebView 保持透明；Material 需要显式容器色
    val containerColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surfaceContainerHigh
        UiMode.Miuix -> null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (uiMode == UiMode.Miuix) it.animateContentSize(animationSpec = tween(durationMillis = 300)) else it }
    ) {
        // 用 graphicsLayer 改透明度，避免每帧触发重组，仅更新绘制属性
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .graphicsLayer { this.alpha = alpha }
        ) {
            GithubMarkdown(
                content = content,
                isMarkdown = isMarkdown,
                onLoadingChange = { loaded = !it },
                containerColor = containerColor,
                restrictedBaseUrl = restrictedBaseUrl,
            )
        }
        // 占位层在完全透明后从组合中移除，避免空转的进度动画持续请求帧
        if (placeholderAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .graphicsLayer { this.alpha = placeholderAlpha },
                contentAlignment = Alignment.Center,
            ) {
                when (LocalUiMode.current) {
                    UiMode.Material -> LoadingIndicator()
                    UiMode.Miuix -> InfiniteProgressIndicator()
                }
            }
        }
    }
}
