package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.filter.FilterNumber
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 显示整数值的“箭头设置项”，点击后用对话框编辑。
 *
 * 摘要文本直接展示当前值；对话框打开期间保持按压态（holdDownState），
 * 让用户明确知道当前行正在被编辑。取消/关闭时会把输入框还原成传入值，
 * 避免上次输入残留影响下一次编辑。
 *
 * @param defaultValue 当前值；-1 常被调用方用作“未设置”的哨兵
 * @param onValueChange 确认后的回写回调，为 null 时只读
 */
@Composable
fun SuperEditArrow(
    modifier: Modifier = Modifier,
    title: String,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    defaultValue: Int = -1,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    enabled: Boolean = true,
    onValueChange: ((Int) -> Unit)? = null
) {
    // 可见性只需页内状态；同时驱动 ArrowPreference 的按压态，打开时整行保持高亮
    val showDialog = remember { mutableStateOf(false) }

    ArrowPreference(
        title = title,
        titleColor = titleColor,
        summary = defaultValue.toString(),
        summaryColor = summaryColor,
        startAction = startAction,
        modifier = modifier,
        insideMargin = insideMargin,
        onClick = {
            showDialog.value = true
        },
        holdDownState = showDialog.value,
        enabled = enabled
    )

    EditDialog(
        title = title,
        show = showDialog.value,
        onDismissRequest = { showDialog.value = false },
        dialogTextFieldValue = defaultValue,
        onValueChange = {
            onValueChange?.invoke(it)
        }
    )

}

/**
 * 数值编辑对话框。
 *
 * 输入过滤交给 [FilterNumber]，只允许整数；确认时若内容为空则回写 0
 * 并同步输入框，保证关闭后不会留下空串状态。按当前值重建 filter，
 * 使键盘类型与初始文本始终匹配。
 */
@Composable
private fun EditDialog(
    title: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    dialogTextFieldValue: Int,
    onValueChange: (Int) -> Unit,
) {
    // 以当前值为 key 重建过滤器，切换条目时不会沿用上一条的输入内容
    val filter = remember(dialogTextFieldValue) { FilterNumber(dialogTextFieldValue) }

    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = {
            onDismissRequest()
            filter.setInputValue(dialogTextFieldValue.toString())
        },
        content = {
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = filter.getInputValue(),
                maxLines = 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                ),
                onValueChange = filter.onValueChange()
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = {
                        onDismissRequest()
                        filter.setInputValue(dialogTextFieldValue.toString())
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        onDismissRequest()
                        with(filter.getInputValue().text) {
                            // 空输入视为“清零”，避免 toInt() 抛异常中断确认流程
                            if (isEmpty()) {
                                onValueChange(0)
                                filter.setInputValue("0")
                            } else {
                                onValueChange(this@with.toInt())
                            }

                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
