package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 页面/全局缩放的输入对话框。
 *
 * 输入框只接受数字，空串允许存在（便于用户清空后重新输入）；确认时再次做
 * 80~110 的钳制与兜底，防止非数字或越界值写回配置。`text` 以 `show` 为 key
 * 重置，保证每次打开都回显当前实际缩放值而不是上次编辑残留。
 *
 * @param volumeState 读取当前缩放比例（0.8~1.1）
 * @param onVolumeChange 确认后回写新的缩放比例
 */
@Composable
fun ScaleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    volumeState: () -> Float,
    onVolumeChange: (Float) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.settings_page_scale),
        summary = "80% - 110%",
        onDismissRequest = onDismissRequest,
        content = {
            // 以 show 为 key：每次打开都从真实值重新生成文本，丢弃上次未提交的编辑
            var text by remember(show) {
                mutableStateOf((volumeState() * 100).toInt().toString())
            }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                trailingIcon = {
                    Text(
                        text = "%",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
                onValueChange = { newValue ->
                    if (newValue.isEmpty()) {
                        text = ""
                    } else {
                        val valid = newValue.all { it.isDigit() }
                        if (valid) {
                            text = newValue
                        }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        // 非法输入回退到当前值；范围钳制放在这里而非输入时，避免用户无法输入中间态
                        val parsed = text.toIntOrNull()
                        val clamped = parsed?.coerceIn(80, 110) ?: (volumeState() * 100).toInt()
                        onVolumeChange(clamped / 100f)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
