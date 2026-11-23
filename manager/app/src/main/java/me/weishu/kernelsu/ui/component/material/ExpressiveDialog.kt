package me.weishu.kernelsu.ui.component.material

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/**
 * Expressive 风格对话框。
 *
 * 相对系统 AlertDialog 只调整了默认形状（`extraLarge` 圆角）与各槽位的
 * 透传能力；所有颜色默认沿用 AlertDialogDefaults，保证深浅色主题下都有
 * 正确的表面色与文字对比度。
 *
 * @param onDismissRequest 点击外部或返回键的关闭回调
 * @param confirmButton 确认按钮，必填
 * @param modifier 对话框修饰符
 * @param dismissButton 取消按钮，为 null 时不显示
 * @param icon 顶部图标
 * @param title 标题
 * @param text 正文内容
 * @param shape 对话框形状，默认超大圆角
 * @param containerColor 背景色
 * @param iconContentColor 图标色
 * @param titleContentColor 标题色
 * @param textContentColor 正文色
 * @param tonalElevation 色调阴影高度
 * @param properties 窗口属性，例如是否可点击外部关闭
 */
@Composable
fun ExpressiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/**
 * 文本版确认对话框的便捷封装。
 *
 * 仅接受纯字符串，内部再包成 Text，适合“确定要执行吗”这类不需要自定义布局的
 * 场景；需要富内容时直接用 [ExpressiveDialog]。`message` 与 `dismissText`
 * 为 null 时对应槽位整体省略，避免出现空 Text 占位造成的额外间距。
 */
@Composable
fun ExpressiveConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissText: String? = null,
) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
}
