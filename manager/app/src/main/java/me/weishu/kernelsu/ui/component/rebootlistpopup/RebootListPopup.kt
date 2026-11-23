package me.weishu.kernelsu.ui.component.rebootlistpopup

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.util.reboot

// 重启菜单项：labelRes 用于展示，reason 传给内核以区分重启类型（空串表示普通重启）
data class RebootListOption(
    @param:StringRes val labelRes: Int,
    val reason: String,
)

/**
 * 构建重启选项列表。是否包含 userspace 重启取决于设备 PowerManager 能力，
 * 其余选项按固定顺序展示，reason 必须与内核识别的字符串一致。
 */
@Composable
fun getRebootListOption(): List<RebootListOption> {
    val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?

    @Suppress("DEPRECATION")
    val isRebootingUserspaceSupported = pm?.isRebootingUserspaceSupported == true

    return buildList {
        add(RebootListOption(R.string.reboot, ""))
        // 仅设备支持时提供 userspace 重启入口
        if (isRebootingUserspaceSupported) {
            add(RebootListOption(R.string.reboot_userspace, "userspace"))
        }
        add(RebootListOption(R.string.reboot_soft, "soft_reboot"))
        add(RebootListOption(R.string.reboot_recovery, "recovery"))
        add(RebootListOption(R.string.reboot_bootloader, "bootloader"))
        add(RebootListOption(R.string.reboot_download, "download"))
        add(RebootListOption(R.string.reboot_edl, "edl"))
    }
}

/** Reboots on selection, but confirms first in jailbreak mode where a plain reboot drops root. */
@Composable
fun rememberRebootAction(): (String) -> Unit {
    val title = stringResource(R.string.reboot)
    val message = stringResource(R.string.jailbreak_reboot_warning)
    val confirmDialog = rememberConfirmDialog(onConfirm = { reboot() })

    // 返回稳定的重启动作；依赖标题/文案/对话框句柄，避免捕获过期资源
    return remember(title, message, confirmDialog) {
        { reason ->
            // 免 root/jailbreak 模式下普通重启会丢失 root，先弹确认
            if (Natives.isLateLoadMode && reason.isEmpty()) {
                confirmDialog.showConfirm(title = title, content = message)
            } else {
                reboot(reason)
            }
        }
    }
}

/**
 * 重启列表弹窗入口，按 UI 模式分派实现。
 */
@Composable
fun RebootListPopup() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> RebootListPopupMiuix()
        UiMode.Material -> RebootListPopupMaterial()
    }
}
