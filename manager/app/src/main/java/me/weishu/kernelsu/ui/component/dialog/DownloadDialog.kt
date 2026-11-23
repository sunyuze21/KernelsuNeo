package me.weishu.kernelsu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 下载地址输入框入口，按当前 UI 模式分派到 Miuix/Material 实现。
 * [onConfirm] 只会在 URL 通过 [isValidUrl] 校验后触发。
 */
@Composable
fun DownloadDialog(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> DownloadDialogMiuix(show, onConfirm, onDismiss)
        UiMode.Material -> DownloadDialogMaterial(show, onConfirm, onDismiss)
    }
}

/**
 * Material 版下载对话框。URL 用 remember 保存，对话框关闭后（不进入组合）自然清空，
 * 避免下次打开残留上次输入。
 */
@Composable
private fun DownloadDialogMaterial(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    // 输入内容由对话框自身管理，父级只需拿到最终确认的 URL
    var url by remember { mutableStateOf("") }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_dialog_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(R.string.download_dialog_msg)) },
                singleLine = true,
                // 弹出 URL 键盘，减少用户输入负担
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                // 未通过校验时禁用确认，避免把非法链接传入下载流程
                enabled = isValidUrl(url.trim()),
                onClick = { onConfirm(url.trim()) }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * Miuix 版下载对话框，行为与 Material 版一致，仅控件风格不同。
 */
@Composable
private fun DownloadDialogMiuix(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次进入组合新建状态，关闭后自动丢弃
    var url by remember { mutableStateOf("") }
    WindowDialog(
        show = show,
        title = stringResource(R.string.download_dialog_title),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.download_dialog_msg),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        enabled = isValidUrl(url.trim()),
                        onClick = { onConfirm(url.trim()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/**
 * 校验下载地址：必须是 https 且同时具备 host 与 path。
 * 限制 https 是为了防止明文传输模块包，path 非空则排除仅有域名的无效链接。
 */
private fun isValidUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val uri = url.toUri()
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrEmpty() &&
        !uri.path.isNullOrEmpty()
}
