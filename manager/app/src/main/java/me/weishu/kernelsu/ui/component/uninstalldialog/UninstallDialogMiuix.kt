package me.weishu.kernelsu.ui.component.uninstalldialog

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.flash.UninstallType
import me.weishu.kernelsu.ui.screen.flash.UninstallType.NONE
import me.weishu.kernelsu.ui.screen.flash.UninstallType.PERMANENT
import me.weishu.kernelsu.ui.screen.flash.UninstallType.RESTORE_STOCK_IMAGE
import me.weishu.kernelsu.ui.screen.flash.UninstallType.TEMPORARY
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 版卸载对话框。与 Material 版流程一致：选择方式后二次确认，
 * 确认才跳转刷写。这里用 remember(show) 让每次重新打开对话框都重置选择，
 * 避免上次残留的 runType 造成误操作。
 */
@Composable
fun UninstallDialogMiuix(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    // 仅开放“彻底卸载”和“恢复原厂镜像”；TEMPORARY 暂未实现
    val options = listOf(
        // TEMPORARY,
        PERMANENT,
        RESTORE_STOCK_IMAGE
    )
    // 未实现分支的占位提示，避免用户点击后无反馈
    val showTodo = {
        Toast.makeText(context, "TODO", Toast.LENGTH_SHORT).show()
    }
    // 以 show 为 key：对话框重新打开时重置确认态与所选方式
    val showConfirmDialog = remember(show) { mutableStateOf(false) }
    val runType = remember(show) { mutableStateOf<UninstallType?>(null) }

    val run = { type: UninstallType ->
        when (type) {
            PERMANENT -> navigator.push(Route.Flash(FlashIt.FlashUninstall))

            RESTORE_STOCK_IMAGE -> navigator.push(Route.Flash(FlashIt.FlashRestore))

            TEMPORARY -> showTodo()
            NONE -> Unit
        }
    }

    OverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        // 由内容自行控制内边距，这里去掉默认留白
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                text = stringResource(R.string.uninstall),
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface
            )
            options.forEach { type ->
                ArrowPreference(
                    onClick = {
                        // 只记录选择并弹确认，真正执行在确认回调中
                        showConfirmDialog.value = true
                        runType.value = type
                    },
                    title = stringResource(type.title),
                    startAction = {
                        Icon(
                            imageVector = type.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 16.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    },
                    insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp)
                    .padding(horizontal = 24.dp)
            )
        }
    )
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            // 先关闭选择与确认两层，再执行跳转
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
