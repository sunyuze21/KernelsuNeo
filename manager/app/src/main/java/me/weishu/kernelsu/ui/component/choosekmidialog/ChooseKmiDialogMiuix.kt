package me.weishu.kernelsu.ui.component.choosekmidialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.getCurrentKmi
import me.weishu.kernelsu.ui.util.getSupportedKmis
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference

/**
 * Miuix 版 KMI 选择对话框。与 Material 版行为一致，区别是用 OverlayDialog + 可保存的选中状态：
 * rememberSaveable 让进程重建后仍保留用户已选但未确认的项。
 */
@Composable
fun ChooseKmiDialogMiuix(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit
) {
    // 异步获取支持的 KMI 与当前 KMI，初始值保证首帧可渲染
    val supportedKMIs by produceState(initialValue = emptyList()) {
        value = getSupportedKmis()
    }
    val currentKmi by produceState(initialValue = "") {
        value = getCurrentKmi()
    }
    // 用 rememberSaveable 保存未确认选择，配置变更/进程重建不丢失
    val currentSelection = rememberSaveable(currentKmi) { mutableStateOf(currentKmi) }
    OverlayDialog(
        show = show,
        title = stringResource(R.string.select_kmi),
        // 当前 KMI 未知时用 Unknown 占位，避免摘要为空
        summary = stringResource(R.string.current_kmi, currentKmi.let { it.ifBlank { "Unknown" } }),
        onDismissRequest = {
            onDismissRequest()
            currentSelection.value = currentKmi
        },
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            // 限制最大高度，KMI 较多时列表内部滚动而非撑破对话框
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(supportedKMIs) { kmi ->
                        CheckboxPreference(
                            title = kmi,
                            summary = if (kmi == currentKmi) stringResource(R.string.current_device_kmi) else null,
                            insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                            checkboxLocation = CheckboxLocation.End,
                            checked = currentSelection.value == kmi,
                            // 选中项保持按压态，形成单选视觉
                            holdDownState = currentSelection.value == kmi,
                            onCheckedChange = { _ ->
                                currentSelection.value = kmi
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            currentSelection.value = currentKmi
                        },
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        // 与 Material 版一致：仅允许确认受支持的 KMI
                        enabled = supportedKMIs.contains(currentSelection.value),
                        onClick = {
                            onSelected(currentSelection.value)
                            onDismissRequest()
                        },
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}
