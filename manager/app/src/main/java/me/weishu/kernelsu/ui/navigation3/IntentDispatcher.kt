package me.weishu.kernelsu.ui.navigation3

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.data.script.ScriptRepository
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.util.DownloadService
import me.weishu.kernelsu.ui.util.getFileName
import me.weishu.kernelsu.ui.webui.WebUIActivity

// ksu:// 自定义协议及参数名；对外暴露的深链格式需保持向后兼容
private const val SCHEME_KSU = "ksu"
/** 深链 host：执行模块 action。 */
private const val HOST_ACTION = "action"
/** 深链 host：打开模块 WebUI。 */
private const val HOST_WEBUI = "webui"
/** 深链参数：模块 id。 */
private const val PARAM_ID = "id"
/** 深链参数：WebUI 访问 token，用于校验调用方。 */
private const val PARAM_TOKEN = "token"

/**
 * Resolved intent action to execute after validation.
 *
 * 中文说明：延迟到校验通过后才执行的动作；之所以先"解析"再"执行"，
 * 是因为部分动作（安装 ZIP）需要用户确认或受安全模式限制。
 */
private sealed interface PendingAction {
    /** Install module(s) from URI — triggered by DownloadService notification or ZIP file open. */
    data class InstallModule(
        val uri: Uri,
        val displayName: String,
        val requiresConfirmation: Boolean,
    ) : PendingAction {
        companion object {
            /**
             * 用于 rememberSaveable 的序列化器。
             *
             * 保存的是可放入 Bundle 的基础类型，进程被回收后仍能恢复待确认的安装动作。
             */
            val InstallModuleSaver = listSaver<InstallModule?, Any>(
                save = { module ->
                    if (module == null) {
                        emptyList()
                    } else {
                        listOf(
                            module.uri,
                            module.displayName,
                            module.requiresConfirmation
                        )
                    }
                },
                restore = { list ->
                    if (list.isEmpty()) {
                        null
                    } else {
                        InstallModule(
                            uri = list[0] as Uri,
                            displayName = list[1] as String,
                            requiresConfirmation = list[2] as Boolean
                        )
                    }
                }
            )
        }
    }

    /** Execute a module's action script — triggered by shortcut or deep link. */
    data class ExecuteAction(val moduleId: String) : PendingAction

    /** Open a module's WebUI — triggered by shortcut. */
    data class OpenWebUI(val moduleId: String) : PendingAction
}

/**
 * 解析后的 ksu:// 深链类型。
 *
 * 与 [PendingAction] 分开是为了让 URI 解析保持纯函数、便于单独校验。
 */
private sealed interface KsuDeepLink {
    /** 深链触发模块 action 脚本。 */
    data class Action(val moduleId: String) : KsuDeepLink
    /** 深链打开模块 WebUI。 */
    data class WebUi(val moduleId: String) : KsuDeepLink
}

/**
 * 构造进程内的 WebUI URI，交给 WebUIActivity 处理。
 *
 * 复用 ksu:// 协议避免另外定义一套参数格式。
 */
private fun buildInternalWebUiUri(moduleId: String): Uri {
    return Uri.Builder()
        .scheme(SCHEME_KSU)
        .authority(HOST_WEBUI)
        .appendQueryParameter(PARAM_ID, moduleId)
        .build()
}

/**
 * 从 URI 推导用于展示的文件名，取不到时退回路径末段或固定占位文案。
 */
fun getDisplayName(uri: Uri): String {
    return uri.getFileName(ksuApp) ?: uri.lastPathSegment ?: "Unknown"
}

// 文件管理器打开 ZIP 时可能给出不同的 MIME，全部视为模块包
private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")

/**
 * Resolve an intent snapshot into a [PendingAction].
 * Returns null if the intent carries no recognized action.
 *
 * 中文说明：这里是外部输入的信任边界，token 必须与本地保存的一致才继续，
 * 防止其他应用伪造广播/深链触发安装或执行脚本。
 */
private fun resolveIntent(intent: Intent): PendingAction? {
    // DownloadService notification: install module
    if (intent.action == DownloadService.ACTION_INSTALL_MODULE) {
        val token = intent.getStringExtra(DownloadService.EXTRA_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        if (token != SettingsRepositoryImpl().intentToken) return null
        val uriString = intent.getStringExtra(DownloadService.EXTRA_MODULE_URI)
            ?: return null
        val uri = uriString.toUri()
        return PendingAction.InstallModule(
            uri = uri,
            displayName = getDisplayName(uri),
            requiresConfirmation = false,
        )
    }

    // File manager: open ZIP
    val viewUri = intent.data
    if (viewUri != null && viewUri.scheme == "content" && intent.type in ZIP_MIME_TYPES) {
        return PendingAction.InstallModule(
            uri = viewUri,
            displayName = getDisplayName(viewUri),
            requiresConfirmation = true,
        )
    }

    // Check deep links
    return when (val deepLink = parseValidatedDeepLink(intent.data)) {
        is KsuDeepLink.Action -> PendingAction.ExecuteAction(deepLink.moduleId)
        is KsuDeepLink.WebUi -> PendingAction.OpenWebUI(deepLink.moduleId)
        null -> null
    }
}

/**
 * 校验并解析 ksu:// 深链，任何一项不合法都返回 null。
 *
 * 除协议、host 外还校验 token，避免第三方应用直接构造链接调用模块能力。
 */
private fun parseValidatedDeepLink(uri: Uri?): KsuDeepLink? {
    if (uri?.scheme != SCHEME_KSU) return null

    val moduleId = uri.getQueryParameter(PARAM_ID)?.takeIf { it.isNotBlank() } ?: return null
    val token = uri.getQueryParameter(PARAM_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
    if (token != SettingsRepositoryImpl().intentToken) return null

    return when (uri.host) {
        HOST_ACTION -> KsuDeepLink.Action(moduleId)
        HOST_WEBUI -> KsuDeepLink.WebUi(moduleId)
        else -> null
    }
}

/**
 * 统一消费来自 Activity 的 Intent 通道，并分派为导航或系统操作。
 *
 * 通道只保证"每个 Intent 恰好处理一次"，无需改写 activity.intent；
 * 待确认的安装动作通过 rememberSaveable 保存，配置变更后对话框不会丢失。
 */
@SuppressLint("StringFormatInvalid")
@Composable
fun IntentDispatcher(intentChannel: ReceiveChannel<Intent>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // 导航器来自组合树上层，必须在本组件之前提供
    val navigator = LocalNavigator.current
    val isSafeMode = Natives.isSafeMode
    // 仅 Manager 应用处理这些外部 Intent，模块进程不应响应
    val isManager = Natives.isManager
    // 待用户确认的 ZIP 安装；跨配置变更保留，避免旋转屏幕后对话框消失但状态残留
    var pendingZipInstall by rememberSaveable(stateSaver = PendingAction.InstallModule.InstallModuleSaver) { mutableStateOf(null) }
    // 与组合生命周期绑定的作用域，用于在回调中启动需要读取脚本元数据的挂起任务
    val scope = rememberCoroutineScope()

    // A one-shot script ZIP opens the run page, which also offers installing it as a module.
    val openModuleZip: (Uri) -> Unit = { uri ->
        scope.launch {
            // 一次性脚本与普通模块包共用 .zip，需先探测类型再决定跳转目标
            val script = ScriptRepository.peekOneshot(uri)
            if (script != null) {
                navigator.push(Route.ScriptRun(ScriptRunArg(uri, offerInstall = true, displayName = script.name)))
            } else {
                navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
            }
        }
    }

    // 确认对话框的确认/取消都要清空待处理状态，防止重复触发安装
    val installDialog = rememberConfirmDialog(
        onConfirm = {
            pendingZipInstall?.let { action -> openModuleZip(action.uri) }
            pendingZipInstall = null
        },
        onDismiss = { pendingZipInstall = null }
    )

    CollectIntentChannel(intentChannel) { intent ->
        if (!isManager) return@CollectIntentChannel
        val action = resolveIntent(intent) ?: return@CollectIntentChannel

        when (action) {
            is PendingAction.InstallModule -> {
                // 安全模式下禁止安装模块，直接提示并丢弃该次请求
                if (isSafeMode) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.safe_mode_module_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@CollectIntentChannel
                }
                if (action.requiresConfirmation) {
                    pendingZipInstall = action
                    installDialog.showConfirm(
                        title = resources.getString(R.string.module),
                        content = resources.getString(
                            R.string.module_install_prompt_with_name,
                            "\n${action.displayName}"
                        )
                    )
                } else {
                    openModuleZip(action.uri)
                }
            }

            is PendingAction.ExecuteAction -> {
                navigator.push(Route.ExecuteModuleAction(action.moduleId, fromShortcut = true))
            }

            is PendingAction.OpenWebUI -> {
                // WebUI 在新 Activity 中打开，保证模块界面不占用主返回栈
                val webIntent = Intent(context, WebUIActivity::class.java)
                    .setData(buildInternalWebUiUri(action.moduleId))
                context.startActivity(webIntent)
            }
        }
    }
}

/**
 * Receive intents inside a [LaunchedEffect] tied to the channel identity.
 * Each emitted intent is processed exactly once; no activity.intent mutation needed.
 *
 * 中文说明：以 channel 实例为 key，通道被替换时会自动取消旧协程并重新收集，
 * 因此不会出现两个消费者同时处理同一条 Intent。
 */
@Composable
private fun CollectIntentChannel(intentChannel: ReceiveChannel<Intent>, onIntent: suspend (Intent) -> Unit) {
    LaunchedEffect(intentChannel) {
        for (intent in intentChannel) {
            onIntent(intent)
        }
    }
}
