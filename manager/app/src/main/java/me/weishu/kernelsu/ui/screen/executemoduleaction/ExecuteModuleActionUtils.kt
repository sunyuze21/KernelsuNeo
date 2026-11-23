package me.weishu.kernelsu.ui.screen.executemoduleaction

import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.ui.util.runModuleAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真正执行模块 action 脚本的副作用入口。
 *
 * LaunchedEffect 只在首次组合时触发：先校验模块是否存在、是否启用且带 action 脚本，
 * 任何校验失败都会提示并退出；脚本在 IO 线程执行，stdout 通过主线程 Handler
 * 回写 Compose 状态，stderr 只进日志。`[H[J` 清屏序列会重置当前输出，
 * 保证 `clear` 命令不会让页面无限增长。
 */
@Composable
fun ExecuteModuleActionEffect(
    moduleId: String,
    text: String,
    logContent: StringBuilder,
    fromShortcut: Boolean,
    onTextUpdate: (String) -> Unit,
    onComplete: () -> Unit = {},
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val noModule = stringResource(R.string.no_such_module)
    val moduleUnavailable = stringResource(R.string.module_unavailable)
    val moduleActionSuccess = stringResource(R.string.module_action_success)

    LaunchedEffect(Unit) {
        // 已有输出说明本次执行已启动过，避免重组/旋屏导致脚本重复执行。
        if (text.isNotEmpty()) {
            return@LaunchedEffect
        }
        val repo = ModuleRepositoryImpl()
        val modules = repo.getModules().getOrDefault(emptyList())
        val moduleInfo = modules.find { info -> info.id == moduleId }
        if (moduleInfo == null) {
            Toast.makeText(context, noModule.format(moduleId), Toast.LENGTH_SHORT).show()
            onExit()
            return@LaunchedEffect
        }
        if (!moduleInfo.hasActionScript) {
            onExit()
            return@LaunchedEffect
        }
        // 更新中/待删除/已禁用的模块不能执行 action，否则可能读到半成品脚本。
        if (!moduleInfo.enabled || moduleInfo.update || moduleInfo.remove) {
            Toast.makeText(context, moduleUnavailable.format(moduleInfo.name), Toast.LENGTH_SHORT).show()
            onExit()
            return@LaunchedEffect
        }
        // 脚本退出结果：true 表示成功，快捷方式入口会在成功后提示。
        var actionResult: Boolean
        // 脚本输出逐步累积；清屏指令会直接替换整段文本。
        var currentText = text
        // stdout 回调在 IO 线程，必须切回主线程才能更新 Compose 状态。
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            runModuleAction(
                moduleId = moduleId,
                onStdout = {
                    val tempText = "$it\n"
                    if (tempText.startsWith("[H[J")) { // clear command
                        currentText = tempText.substring(6)
                    } else {
                        currentText += tempText
                    }
                    mainHandler.post {
                        onTextUpdate(currentText)
                    }
                    logContent.append(it).append("\n")
                },
                onStderr = {
                    logContent.append(it).append("\n")
                }
            ).let {
                actionResult = it
            }
        }
        if (actionResult && fromShortcut) {
            Toast.makeText(
                context,
                moduleActionSuccess,
                Toast.LENGTH_SHORT
            ).show()
        }
        onComplete()
    }
}

/**
 * 生成“保存日志”回调。
 *
 * 日志写入公共 Download 目录，需要存储权限；写文件放在传入的 [scope] 中执行，
 * 避免阻塞 UI。文件名带时间戳，防止多次执行互相覆盖，写入完成后通过
 * [showMessage] 告知实际路径。
 */
fun saveLog(
    logContent: StringBuilder,
    scope: CoroutineScope,
    fileNamePrefix: String = "KernelSU_module_action_log",
    showMessage: (String) -> Unit
): () -> Unit {
    return {
        scope.launch {
            // 时间戳精确到秒，满足同一分钟内多次保存也不冲突的需求。
            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val date = format.format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${fileNamePrefix}_${date}.log"
            )
            file.writeText(logContent.toString())
            showMessage("Log saved to ${file.absolutePath}")
        }
    }
}
