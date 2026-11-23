package me.weishu.kernelsu.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * 卸载方式选择对话框入口，按 UI 模式分派实现。
 * 选择后先弹确认，确认才真正跳转刷写流程，避免误触。
 */
@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
