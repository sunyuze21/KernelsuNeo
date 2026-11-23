package me.weishu.kernelsu.ui.component.rebootlistpopup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.KsuIsValid

/**
 * Material 版重启菜单项。用 SelectableDropdownMenuItem 的首尾形状拼出分组圆角，
 * 因此必须按 index/count 设置，否则圆角会错乱。
 */
@Composable
fun RebootDropdownItems(onItemClick: (String) -> Unit) {
    val options = getRebootListOption()
    options.forEachIndexed { index, option ->
        SelectableDropdownMenuItem(
            selected = false,
            // 点击即回传各自的 reason，由上层统一执行重启
            onClick = { onItemClick(option.reason) },
            text = { Text("  " + stringResource(option.labelRes)) },
            shapes = MenuDefaults.itemShape(index = index, count = options.size),
        )
    }
}

/**
 * Material 版重启弹窗。必须包在 [KsuIsValid] 内，否则内核不可用时
 * 菜单中的重启动作没有意义。展开状态由本地状态控制，选择后立即收起。
 */
@Composable
fun RebootListPopupMaterial() {
    var expanded by remember { mutableStateOf(false) }

    KsuIsValid {
        val onReboot = rememberRebootAction()

        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = stringResource(id = R.string.reboot)
            )
        }

        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                RebootDropdownItems { reason ->
                    // 先收起菜单再重启，避免重启瞬间残留弹窗
                    expanded = false
                    onReboot(reason)
                }
            }
        }
    }
}
