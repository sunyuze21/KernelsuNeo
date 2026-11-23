package me.weishu.kernelsu.ui.component.choosekmidialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedRadioItem
import me.weishu.kernelsu.ui.util.getCurrentKmi
import me.weishu.kernelsu.ui.util.getSupportedKmis

/**
 * Material 版 KMI 选择对话框。设备支持列表与当前 KMI 均为异步读取，
 * 因此先以空列表/空串占位，读取完成后自动重组。show 为 false 时不进入组合，
 * 避免反复读取设备信息。
 */
@Composable
fun ChooseKmiDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit
) {
    if (!show) return

    // 支持的 KMI 列表来自设备/内核，需异步获取，初始为空避免阻塞首帧
    val supportedKMIs by produceState(initialValue = emptyList()) {
        value = getSupportedKmis()
    }

    // 当前设备 KMI，用于标注“当前”以及作为默认选中项
    val currentKmi by produceState(initialValue = "") {
        value = getCurrentKmi()
    }

    // 用户的选择独立于 currentKmi，取消时再回滚，避免污染已生效配置
    val selectedKmi = remember(currentKmi) { mutableStateOf(currentKmi) }

    AlertDialog(
        onDismissRequest = {
            onDismissRequest()
            // 关闭即丢弃未确认的选择
            selectedKmi.value = currentKmi
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelected(selectedKmi.value)
                    onDismissRequest()
                },
                // 只有受支持的 KMI 才允许确认，防止写入非法值
                enabled = supportedKMIs.contains(selectedKmi.value)
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismissRequest()
                selectedKmi.value = currentKmi
            }) {
                Text(stringResource(id = android.R.string.cancel))
            }
        },
        title = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                text = stringResource(R.string.select_kmi),
                textAlign = TextAlign.Center
            )
        },
        text = {
            SegmentedColumn(
                content = supportedKMIs.map { kmi ->
                    {
                        SegmentedRadioItem(
                            title = kmi,
                            // 仅给当前设备 KMI 加说明，帮助用户区分
                            summary = if (kmi == currentKmi) stringResource(R.string.current_device_kmi) else null,
                            selected = selectedKmi.value == kmi,
                            onClick = { selectedKmi.value = kmi }
                        )
                    }
                }
            )
        }
    )
}
