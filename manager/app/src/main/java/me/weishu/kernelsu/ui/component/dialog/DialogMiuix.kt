package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.ui.component.markdown.MarkdownContent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Miuix 版加载对话框：拦截返回手势以避免加载被中断，只展示进度与提示文字。
 */
@Composable
fun LoadingDialogMiuix(
    showDialog: MutableState<Boolean>,
) {
    WindowDialog(
        show = showDialog.value,
        content = {
            // Consume the back gesture before the dialog's own handler
            val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
            NavigationBackHandler(
                state = navEventState,
                isBackEnabled = true,
                // 空回调即“消费但不处理”，阻止返回键关闭对话框
                onBackCompleted = { },
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    InfiniteProgressIndicator(
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Text(
                        modifier = Modifier.padding(start = 12.dp),
                        text = stringResource(R.string.processing),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    )
}

/**
 * Miuix 版确认对话框。内容区用自定义 Layout 保证按钮固定可见：
 * 有正文时测量剩余高度给正文（可滚动），无正文时只放按钮，避免内容撑破窗口。
 */
@Composable
fun ConfirmDialogMiuix(
    visuals: ConfirmDialogVisuals,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    showDialog: MutableState<Boolean>
) {
    WindowDialog(
        show = showDialog.value,
        // 只避开顶部系统栏，底部由对话框自身留白
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        title = visuals.title,
        onDismissRequest = {
            dismiss()
            showDialog.value = false
        },
        content = {
            Layout(
                content = {
                    // dismissState 由 WindowDialog 提供，用于触发关闭动画
                    val dismissState = LocalDismissState.current
                    visuals.content?.let { content ->
                        when {
                            // Registry content (and any HTML, which only comes from the registry) is rendered locked down.
                        visuals.isRestricted || visuals.isHtml -> MarkdownContent(
                            content = content,
                            isMarkdown = !visuals.isHtml,
                            // 锁定远端资源范围，防止注册表内容外联
                            restrictedBaseUrl = RegistryUrls.DEFAULT_BASE_URL,
                        )
                        visuals.isMarkdown -> MarkdownContent(content = content, isMarkdown = true)
                        else -> Text(text = content)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        TextButton(
                            text = visuals.dismiss ?: stringResource(id = android.R.string.cancel),
                            onClick = {
                                dismiss()
                                // 走对话框动画关闭，而不是直接改 show 造成闪断
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = visuals.confirm ?: stringResource(id = android.R.string.ok),
                            onClick = {
                                confirm()
                                dismissState?.invoke()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            ) { measurables, constraints ->
                if (measurables.size != 2) {
                    // 无正文：只测量按钮并按其高度布局
                    val button = measurables[0].measure(constraints)
                    layout(constraints.maxWidth, button.height) {
                        button.place(0, 0)
                    }
                } else {
                    // 有正文：先量按钮，再把剩余高度给正文，避免按钮被正文挤出可视区
                    val button = measurables[1].measure(constraints)
                    val lazyList = measurables[0].measure(constraints.copy(maxHeight = constraints.maxHeight - button.height))
                    layout(constraints.maxWidth, lazyList.height + button.height) {
                        lazyList.place(0, 0)
                        button.place(0, lazyList.height)
                    }
                }
            }
        }
    )
}
