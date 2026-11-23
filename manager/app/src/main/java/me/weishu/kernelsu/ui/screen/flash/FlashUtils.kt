package me.weishu.kernelsu.ui.screen.flash

import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.FlashResult
import me.weishu.kernelsu.ui.util.LkmSelection
import me.weishu.kernelsu.ui.util.downloadBoot
import me.weishu.kernelsu.ui.util.flashModule
import me.weishu.kernelsu.ui.util.installBoot
import me.weishu.kernelsu.ui.util.restoreBoot
import me.weishu.kernelsu.ui.util.uninstallPermanently
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** 刷写生命周期：进行中禁止返回，结束后才允许展示结果与下一步操作。 */
enum class FlashingStatus {
    FLASHING,
    SUCCESS,
    FAILED
}

/**
 * 卸载/还原入口的展示元数据。NONE 仅作为无选中项时的占位，
 * 因此文案资源为 0；带图标与确认文案的项会先弹确认框。
 */
enum class UninstallType(val icon: ImageVector, val title: Int, val message: Int) {
    TEMPORARY(
        Icons.Rounded.RemoveModerator,
        R.string.settings_uninstall_temporary,
        R.string.settings_uninstall_temporary_message
    ),
    PERMANENT(
        Icons.Rounded.DeleteForever,
        R.string.settings_uninstall_permanent,
        R.string.settings_uninstall_permanent_message
    ),
    RESTORE_STOCK_IMAGE(
        Icons.Rounded.RestartAlt,
        R.string.settings_restore_stock_image,
        R.string.settings_restore_stock_image_message
    ),
    NONE(Icons.Rounded.Adb, 0, 0)
}

/**
 * 刷写任务描述，作为导航路由参数跨页面传递，故实现 Parcelable。
 * [boot] 为空表示直接使用设备当前 boot；[ota] 为 true 时写入非活动槽位。
 */
@Parcelize
sealed class FlashIt : Parcelable {
    /** 使用本地镜像（或当前 boot）安装；[backup] 控制刷入前是否备份原镜像。 */
    @Parcelize
    data class FlashBoot(
        val boot: Uri? = null,
        val lkm: LkmSelection,
        val ota: Boolean,
        val partition: String? = null,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
    ) : FlashIt()

    /** 先下载远程镜像再安装；[partition] 必须已确定，否则无法定位写入目标。 */
    @Parcelize
    data class DownloadBoot(
        val url: String,
        val partition: String,
        val lkm: LkmSelection,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
    ) : FlashIt()

    /** 依次刷入多个模块 zip；任一失败即中止，避免部分模块状态不一致。 */
    @Parcelize
    data class FlashModules(val uris: List<Uri>) : FlashIt()

    /** 从备份还原原始 boot 镜像。 */
    @Parcelize
    data object FlashRestore : FlashIt()

    /** 永久卸载 KernelSU。 */
    @Parcelize
    data object FlashUninstall : FlashIt()
}

/**
 * 顺序刷入多个模块：任一模块失败立即返回其错误码，
 * 但只要此前有模块要求重启，仍需把重启标记透传给调用方。
 */
fun flashModulesSequentially(
    uris: List<Uri>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    // 多个模块的 showReboot 需要累积，任一模块要求即需重启
    var needsReboot = false
    for (uri in uris) {
        flashModule(uri, onStdout, onStderr).apply {
            needsReboot = needsReboot || showReboot
            if (code != 0) {
                return FlashResult(code, err, needsReboot)
            }
        }
    }
    return FlashResult(0, "", needsReboot)
}

/** 按任务类型分发到对应的安装/还原/卸载实现，统一返回 [FlashResult]。 */
fun flashIt(
    flashIt: FlashIt,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    return when (flashIt) {
        is FlashIt.FlashBoot -> installBoot(
            flashIt.boot,
            flashIt.lkm,
            flashIt.ota,
            flashIt.partition,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            onStdout,
            onStderr
        )

        is FlashIt.DownloadBoot -> downloadBoot(
            flashIt.url,
            flashIt.partition,
            flashIt.lkm,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            onStdout,
            onStderr
        )

        is FlashIt.FlashModules -> {
            flashModulesSequentially(flashIt.uris, onStdout, onStderr)
        }

        FlashIt.FlashRestore -> restoreBoot(onStdout, onStderr)
        FlashIt.FlashUninstall -> uninstallPermanently(onStdout, onStderr)
    }
}

/**
 * 执行刷写并持续把输出回传 UI。
 * [enabled] 变为 false（如越狱警告未确认）时不启动任务；[text] 非空说明
 * 已经执行过，避免配置变化导致重复刷写。协程随组合离开自动取消。
 */
@Composable
fun FlashEffect(
    flashIt: FlashIt,
    text: String,
    logContent: StringBuilder,
    onTextUpdate: (String) -> Unit,
    onShowRebootChange: (Boolean) -> Unit,
    onFlashingStatusChange: (FlashingStatus) -> Unit,
    enabled: Boolean = true
) {
    LaunchedEffect(enabled) {
        if (!enabled || text.isNotEmpty()) {
            return@LaunchedEffect
        }
        var currentText = text
        // 刷写运行在 IO 线程，UI 状态更新必须切回主线程
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            flashIt(flashIt, onStdout = {
                val tempText = "$it\n"
                // 控制台清屏序列以 ESC[H ESC[J 开头，收到时应丢弃已有输出
                if (tempText.startsWith("[H[J")) { // clear command
                    currentText = tempText.substring(6)
                } else {
                    currentText += tempText
                }
                mainHandler.post {
                    onTextUpdate(currentText)
                }
                // 日志保留全部原始输出，不受清屏影响，便于用户保存排查
                logContent.append(it).append("\n")
            }, onStderr = {
                logContent.append(it).append("\n")
            }).apply {
                if (code != 0) {
                    currentText += "Error code: $code.\n $err Please save and check the log.\n"
                    mainHandler.post {
                        onTextUpdate(currentText)
                    }
                }
                if (showReboot) {
                    currentText += "\n\n\n"
                    mainHandler.post {
                        onTextUpdate(currentText)
                        onShowRebootChange(true)
                    }
                }
                // 只有最终结果码才能决定成功/失败，中间输出不代表刷写完成
                mainHandler.post {
                    onFlashingStatusChange(if (code == 0) FlashingStatus.SUCCESS else FlashingStatus.FAILED)
                }
            }
        }
    }
}

/**
 * 返回一个“保存日志”动作。日志写入公共 Downloads 目录，
 * 文件名带时间戳避免覆盖；写入失败异常会随协程抛出，暂不做额外处理。
 */
fun saveLog(
    logContent: StringBuilder,
    scope: CoroutineScope,
    showMessage: (String) -> Unit
): () -> Unit {
    return {
        scope.launch {
            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val date = format.format(Date())
            // 每次保存生成独立文件，便于对比不同刷写过程
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "KernelSU_install_log_${date}.log"
            )
            file.writeText(logContent.toString())
            showMessage("Log saved to ${file.absolutePath}")
        }
    }
}

/** 越狱环境下刷写 boot 的强制等待秒数，防止用户未读警告就继续。 */
private const val JAILBREAK_WARNING_COUNTDOWN = 10

/**
 * 越狱模式警告框：倒计时结束前确认按钮不可用。
 * 对话框随组合销毁时倒计时协程会被取消，重新进入会重新计数。
 */
@Composable
fun JailbreakFlashWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(JAILBREAK_WARNING_COUNTDOWN) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000.milliseconds)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(android.R.string.dialog_alert_title)) },
        text = {
            Text(
                stringResource(R.string.jailbreak_flash_warning),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = countdown == 0
            ) {
                Text(
                    if (countdown > 0)
                        stringResource(R.string.jailbreak_flash_warning_countdown, countdown)
                    else
                        stringResource(R.string.install_next)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
