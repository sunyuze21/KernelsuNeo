package me.weishu.kernelsu.ui.component.material

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.util.getBugreportFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.nav.gesture.WindowNavigationEventBridge

/**
 * 沿 ContextWrapper 链向上查找宿主 ComponentActivity。
 *
 * Composable 中拿到的 Context 可能是 ContextThemeWrapper 等包装类型，
 * 直接 `as ComponentActivity` 会失败；用 tailrec 逐层拆包可安全地拿到
 * 真正持有 lifecycle 的 Activity，找不到时返回 null 由调用方降级处理。
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

/**
 * 日志导出底部弹窗：支持“保存到本地”和“分享”两种方式。
 *
 * 两种操作都先把 bugreport 落盘成文件（[getBugreportFile]），再分别走
 * SAF 的 CreateDocument 与 FileProvider 分享；前者由系统授予写入权限，
 * 后者必须显式加 `FLAG_GRANT_READ_URI_PERMISSION` 才能让目标应用读取。
 * 耗时操作切到 IO 线程，loading 遮罩保证用户感知到阻塞，失败时也会在
 * finally 中关闭，避免遮罩泄漏。
 *
 * @param onDismiss 弹窗关闭回调（无论保存成功与否都会回调）
 * @param snackbarHostState 保存成功后用于提示的 Snackbar 状态
 */
@Composable
fun SendLogBottomSheet(
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val activity = context.findComponentActivity()
    val logSaved = stringResource(R.string.log_saved)
    val sendLog = stringResource(R.string.send_log)
    // 保存与分享共用同一个 loading 对话框，避免并发导出时叠出多层遮罩
    val loadingDialog = rememberLoadingDialog()
    val haptic = LocalHapticFeedback.current
    // 初始设为 Hidden，再由 ModalBottomSheet 播放进入动画，否则首帧会直接“跳”出来
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    // 先启动隐藏动画再回调 onDismiss，让关闭过程有动画而不是瞬间消失
    val dismiss = {
        scope.launch { sheetState.hide() }
        onDismiss()
    }

    // SAF 创建的文档可能返回 null（用户取消），此时直接收起弹窗即可
    val exportBugreportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri == null) {
            dismiss()
            return@rememberLauncherForActivityResult
        }
        // 优先复用 Activity 的 lifecycleScope，配置变更后任务不会随组合一起被取消
        val lifecycleScope = activity?.lifecycleScope ?: scope
        lifecycleScope.launch {
            loadingDialog.show()
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        getBugreportFile(context).inputStream().use {
                            it.copyTo(output)
                        }
                    }
                }
            } finally {
                loadingDialog.hide()
            }
            dismiss()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(logSaved)
        }
    }
    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = {
            WindowNavigationEventBridge()
            Row(
                modifier = Modifier
                    .padding(top = 16.dp, bottom = 32.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        modifier = Modifier.size(64.dp),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
                            val current = LocalDateTime.now().format(formatter)
                            exportBugreportLauncher.launch("KernelSU_bugreport_${current}.tar.gz")
                        }) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(id = R.string.save_log),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.save_log), textAlign = TextAlign.Center.also {
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None
                            )
                        }
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalIconButton(
                        modifier = Modifier.size(64.dp),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            scope.launch {
                                val bugreport = loadingDialog.withLoading {
                                    withContext(Dispatchers.IO) {
                                        getBugreportFile(context)
                                    }
                                }

                                val uri: Uri = FileProvider.getUriForFile(
                                    context, "${BuildConfig.APPLICATION_ID}.fileprovider", bugreport
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    setDataAndType(uri, "application/gzip")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent, sendLog
                                    )
                                )
                            }
                        }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(id = R.string.send_log),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.send_log), textAlign = TextAlign.Center.also {
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None
                            )
                        })
                }
            }
        })
}
