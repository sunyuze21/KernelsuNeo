package me.weishu.kernelsu.ui.component.uninstalldialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.flash.UninstallType
import me.weishu.kernelsu.ui.screen.flash.UninstallType.PERMANENT
import me.weishu.kernelsu.ui.screen.flash.UninstallType.RESTORE_STOCK_IMAGE

/**
 * Material 版卸载对话框。先在选择列表里选方式，再用统一的确认对话框二次确认；
 * 确认后才跳转 Flash 流程。TEMPORARY 暂未开放，因此不在 options 中。
 */
@Composable
fun UninstallDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    val navigator = LocalNavigator.current
    // 仅开放“彻底卸载”和“恢复原厂镜像”；TEMPORARY 未实现，先隐藏
    val options = listOf(
        // TEMPORARY,
        PERMANENT,
        RESTORE_STOCK_IMAGE
    )
    // 确认对话框显隐与用户所选方式分开保存，便于取消时保留/清理
    val showConfirmDialog = remember { mutableStateOf(false) }
    val runType = remember { mutableStateOf<UninstallType?>(null) }

    // 执行所选卸载方式：走导航到对应刷写页
    val run = { type: UninstallType ->
        when (type) {
            PERMANENT -> navigator.push(Route.Flash(FlashIt.FlashUninstall))
            RESTORE_STOCK_IMAGE -> navigator.push(Route.Flash(FlashIt.FlashRestore))
            else -> Unit
        }
    }

    if (show) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.settings_uninstall)) },
            text = {
                SegmentedColumn(
                    modifier = Modifier,
                    content = options.map { type ->
                        {
                            SegmentedListItem(
                                onClick = {
                                    // 记录所选方式并弹出确认，不立即执行
                                    showConfirmDialog.value = true
                                    runType.value = type
                                },
                                headlineContent = { Text(stringResource(type.title)) },
                                supportingContent = { Text(stringResource(type.message)) },
                                leadingContent = {
                                    Icon(
                                        imageVector = type.icon,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            // 确认后关闭两层对话框再跳转，避免返回栈残留
            showConfirmDialog.value = false
            onDismissRequest()
            runType.value?.let { type ->
                run(type)
            }
        },
        onDismiss = {
            showConfirmDialog.value = false
        }
    )

    // 确认文案取自所选方式，找不到时给空串兜底
    val dialogTitle = runType.value?.let { type ->
        options.find { it == type }?.let { stringResource(it.title) }
    } ?: ""
    val dialogContent = runType.value?.let { type ->
        options.find { it == type }?.let { stringResource(it.message) }
    }

    if (showConfirmDialog.value) {
        confirmDialog.showConfirm(title = dialogTitle, content = dialogContent)
    }
}
