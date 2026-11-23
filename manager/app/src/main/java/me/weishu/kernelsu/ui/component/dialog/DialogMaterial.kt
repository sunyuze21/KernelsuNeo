package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.ui.component.markdown.MarkdownContent
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog

/**
 * Material 版加载对话框：不可点击外部或返回键关闭，避免加载过程中界面状态被破坏。
 */
@Composable
fun LoadingDialogMaterial(
    showDialog: MutableState<Boolean>,
) {
    if (showDialog.value) {
        Dialog(
            onDismissRequest = { },
            // Keep the dialog non-dismissible
            properties = DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            )
        ) {
            Surface(
                modifier = Modifier.size(100.dp), shape = MaterialTheme.shapes.extraLarge
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }
        }
    }
}

/**
 * Material 版确认对话框。关闭时先派发回调再隐藏，保证业务能拿到取消事件；
 * 内容按类型分派：受限/HTML 走锁定渲染，Markdown 走普通渲染，其余按纯文本显示。
 */
@Composable
fun ConfirmDialogMaterial(
    visuals: ConfirmDialogVisuals,
    confirm: () -> Unit,
    dismiss: () -> Unit,
    showDialog: MutableState<Boolean>
) {
    if (showDialog.value) {
        ExpressiveDialog(
            onDismissRequest = {
                // 点击外部/返回视为取消
                dismiss()
                showDialog.value = false
            },
            title = { Text(visuals.title) },
            text = visuals.content?.let { content ->
                {
                    when {
                        // Registry content (and any HTML, which only comes from the registry) is rendered locked down.
                    visuals.isRestricted || visuals.isHtml -> MarkdownContent(
                        content = content,
                        isMarkdown = !visuals.isHtml,
                        // 限制基础 URL，防止注册表内容加载任意远端资源
                        restrictedBaseUrl = RegistryUrls.DEFAULT_BASE_URL,
                    )
                    visuals.isMarkdown -> MarkdownContent(content = content, isMarkdown = true)
                    else -> Text(text = content)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm()
                        showDialog.value = false
                    }
                ) {
                    // 自定义按钮文案优先，缺省回退到系统确定
                    Text(visuals.confirm ?: stringResource(id = android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismiss()
                        showDialog.value = false
                    }
                ) {
                    Text(visuals.dismiss ?: stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
}
