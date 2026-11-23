package me.weishu.kernelsu.ui.component.choosekmidialog

import androidx.compose.runtime.Composable
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * KMI 选择对话框入口，按当前 UI 模式分派到 Miuix/Material 实现。
 * [onSelected] 回调 null 表示未选择/清除；关闭后各实现会重置内部选中态。
 */
@Composable
fun ChooseKmiDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> ChooseKmiDialogMiuix(show, onDismissRequest, onSelected)
        UiMode.Material -> ChooseKmiDialogMaterial(show, onDismissRequest, onSelected)
    }
}
