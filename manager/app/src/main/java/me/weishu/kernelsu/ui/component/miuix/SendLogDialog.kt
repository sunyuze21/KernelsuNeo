package me.weishu.kernelsu.ui.component.miuix

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.LoadingDialogHandle
import me.weishu.kernelsu.ui.util.getBugreportFile
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 日志保存/分享对话框（miuix 风格）。
 *
 * 与 Material 版不同，这里用 Toast 反馈保存结果，不依赖外层 Snackbar。
 * 导出在 IO 线程执行，loading 对话框由调用方注入以便与应用其他位置共用。
 * SAF 返回 null 表示用户取消，此时静默返回，不能弹出成功提示。
 *
 * @param loadingDialog 外部持有的 loading 句柄，用于导出期间阻塞交互
 */
@Composable
fun SendLogDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    loadingDialog: LoadingDialogHandle,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logSavedText = stringResource(R.string.log_saved)
    val sendLogText = stringResource(R.string.send_log)
    // 文件名在启动 SAF 时就要确定，因此先用当前时间生成带时间戳的名称
    val exportBugreportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            loadingDialog.show()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                getBugreportFile(context).inputStream().use {
                    it.copyTo(output)
                }
            }
            loadingDialog.hide()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, logSavedText, Toast.LENGTH_SHORT).show()
            }
        }
    }
    OverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                text = stringResource(R.string.send_log),
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = colorScheme.onSurface
            )
            ArrowPreference(
                title = stringResource(id = R.string.save_log),
                startAction = {
                    Icon(
                        Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
                    val current = LocalDateTime.now().format(formatter)
                    exportBugreportLauncher.launch("KernelSU_bugreport_${current}.tar.gz")
                    onDismissRequest()
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            ArrowPreference(
                title = stringResource(id = R.string.send_log),
                startAction = {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 16.dp),
                        tint = colorScheme.onSurface
                    )
                },
                onClick = {
                    scope.launch {
                        onDismissRequest()
                        val bugreport = loadingDialog.withLoading {
                            withContext(Dispatchers.IO) {
                                getBugreportFile(context)
                            }
                        }

                        val uri: Uri =
                            // FileProvider 授权是分享文件给其他应用的唯一安全途径，
                            // 配合 FLAG_GRANT_READ_URI_PERMISSION 才能让对方读取
                            FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileprovider",
                                bugreport
                            )

                        // EXTRA_STREAM + 读权限是分享文件的标准组合；用 chooser 让用户选择目标应用
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            setDataAndType(uri, "application/gzip")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                sendLogText
                            )
                        )
                    }
                },
                insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            )
            TextButton(
                text = stringResource(id = android.R.string.cancel),
                onClick = {
                    onDismissRequest()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp)
                    .padding(horizontal = 24.dp)
            )
        }
    )
}
