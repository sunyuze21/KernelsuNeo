package me.weishu.kernelsu.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.ui.util.AppIconCache
import me.weishu.kernelsu.ui.util.createRootShell
import me.weishu.kernelsu.ui.util.withMainUserUid
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

// WebView 虚拟域名：请求由 WebViewAssetLoader/SuFilePathHandler 本地处理。
private const val WEB_DOMAIN = "mui.kernelsu.org"
// 自定义 scheme，用于 ksu://icon/<pkg> 提供应用图标。
private const val KSU_SCHEME = "ksu"
// ksu://icon 的 host，用于区分图标请求与其他自定义请求。
private const val ICON_HOST = "icon"
// 与 BlobDownloadHandler 共享的内部域名，用于下载拦截。
private const val INTERNAL_BLOB_DOWNLOAD_HOST = "blob-download.kernelsu.internal"

// 注入 WebUI 的下载辅助脚本，统一处理 blob/分块下载。
private fun loadDownloadJs(context: Context): String {
    return context.assets.open("webview/download.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/**
 * 同步获取已安装应用列表（供 WebView 拦截请求等非协程场景使用）。
 *
 * 使用 runBlocking 是必要的：WebViewClient 回调是同步的；内部优先读缓存
 * 以减少阻塞时间。
 */
internal fun webUIApps(): List<AppInfo> = runBlocking {
    SuperUserViewModel.loadApps(useCache = true).getOrNull()?.first.orEmpty()
}

/** 设置最近任务中的标题；Android 13 起改用 Builder API。 */
fun Activity.setTaskDescription(label: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label))
    } else {
        val taskDescription = ActivityManager.TaskDescription.Builder()
            .setLabel(label)
            .build()
        setTaskDescription(taskDescription)
    }
}

/**
 * 准备并配置模块 WebUI 的 WebView。
 *
 * 安全约束：
 * - 仅当模块存在、启用且未处于更新/删除状态时才加载；
 * - 关闭 fileAccess，所有本地文件都由 SuFilePathHandler 在限定目录内提供；
 * - JS 桥接对象（ksu/ksu_download）拥有 root 能力，只应暴露给可信页面。
 */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun prepareWebView(
    activity: Activity,
    moduleId: String,
    webUIState: WebUIState,
) {
    withContext(Dispatchers.IO) {
        val repo = ModuleRepositoryImpl()
        val modules = repo.getModules().getOrDefault(emptyList())
        val moduleInfo = modules.find { info -> info.id == moduleId }

        if (moduleInfo == null) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.no_such_module, moduleId))
            }
            return@withContext
        }

        if (!moduleInfo.hasWebUi || !moduleInfo.enabled || moduleInfo.update || moduleInfo.remove) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_unavailable, moduleInfo.name))
            }
            return@withContext
        }

        webUIState.moduleName = moduleInfo.name
        // 模块目录由 ksud 固定管理，webroot 就在其下。
        webUIState.modDir = "/data/adb/modules/${moduleId}"

        // 全局挂载命名空间的 root shell，用于读取模块挂载后的文件。
        val shell = createRootShell(true)
        webUIState.rootShell = shell

        withContext(Dispatchers.Main) {
            activity.setTaskDescription(activity.getString(R.string.app_name) + " - ${moduleInfo.name}")

            val webView = WebView(activity)
            webView.setBackgroundColor(Color.TRANSPARENT)

            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            // 仅调试开关打开时允许远程调试 WebView。
            WebView.setWebContentsDebuggingEnabled(prefs.getBoolean("enable_web_debugging", false))

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 禁止 file:// 直接访问，防止绕过 SuFilePathHandler 的目录校验。
                allowFileAccess = false
            }

            // 模块的网页根目录，所有静态资源从这里读取。
            val webRoot = File("${webUIState.modDir}/webroot")
            val webViewAssetLoader = WebViewAssetLoader.Builder()
                .setDomain(WEB_DOMAIN)
                .addPathHandler(
                    "/",
                    SuFilePathHandler(
                        activity,
                        webRoot,
                        shell,
                        { webUIState.currentInsets },
                        { enable -> webUIState.isInsetsEnabled = enable })
                )
                .build()

            // WebViewClient
            webView.webViewClient = object : WebViewClient() {
                // 按优先级拦截：blob 下载 -> ksu://icon 图标 -> 模块 webroot 文件。
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url

                    BlobDownloadHandler.shouldInterceptRequest(request)?.let { return it }

                    // ksu://icon/<package> 动态生成 512px PNG，带 CORS 头供页面使用。
                    if (url.scheme.equals(KSU_SCHEME, ignoreCase = true) && url.host.equals(ICON_HOST, ignoreCase = true)) {
                        val packageName = url.path?.substring(1)
                        if (!packageName.isNullOrEmpty()) {
                            val appInfo = webUIApps()
                                .find { it.packageName == packageName }
                                ?.packageInfo?.applicationInfo
                            if (appInfo != null) {
                                val icon = AppIconCache.loadIconSync(activity, appInfo.withMainUserUid(activity), 512)
                                val stream = ByteArrayOutputStream()
                                icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                return WebResourceResponse(
                                    "image/png", null, 200, "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    ByteArrayInputStream(stream.toByteArray())
                                )
                            } else {
                                val errorMsg = "No such package"
                                val errorStream = ByteArrayInputStream(errorMsg.toByteArray(Charsets.UTF_8))
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    404,
                                    "Not Found",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    errorStream
                                )
                            }
                        }
                    }
                    return webViewAssetLoader.shouldInterceptRequest(url)
                }

                // 页面跳转后同步返回键状态，并重新注入下载/insets 脚本。
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    webUIState.webCanGoBack = view?.canGoBack() ?: false
                    if (webUIState.isInsetsEnabled) webUIState.webView?.evaluateJavascript(webUIState.currentInsets.js, null)
                    view?.evaluateJavascript(loadDownloadJs(activity), null)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }
            }

            // WebChromeClient
            webView.webChromeClient = object : WebChromeClient() {
                // 把 JS 对话框交给 Compose 渲染，返回 true 表示已自行处理。
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowAlert(message, result)
                    return true
                }

                // confirm 需要把 true/false 回传给 JsResult。
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowConfirm(message, result)
                    return true
                }

                // prompt 需要回传输入字符串；取消时回传 null。
                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (message == null || result == null || defaultValue == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowPrompt(message, defaultValue, result)
                    return true
                }

                // 保存 ValueCallback 并拉起系统选择器；旧请求先以 null 结束。
                override fun onShowFileChooser(
                    webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
                ): Boolean {
                    webUIState.filePathCallback?.onReceiveValue(null)
                    webUIState.filePathCallback = filePathCallback

                    // 优先使用 WebView 提供的 Intent，保留 accept 类型/多选等约束。
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    webUIState.uiEvent = WebUIEvent.ShowFileChooser(intent)
                    return true
                }
            }

            // JS Interface
            // 这两个对象拥有 root/文件读写能力，只注入本地可信页面。
            val webviewInterface = WebViewInterface(webUIState)
            val downloadInterface = WebUIDownloadInterface(webUIState)
            webUIState.webViewInterface = webviewInterface
            webUIState.downloadInterface = downloadInterface
            webUIState.webView = webView
            webView.addJavascriptInterface(webviewInterface, "ksu")
            webView.addJavascriptInterface(downloadInterface, "ksu_download")
            // 内部 blob 下载由 shouldInterceptRequest 处理；其余交给系统浏览器。
            webView.setDownloadListener { url, _, _, _, _ ->
                if (Uri.parse(url).host != INTERNAL_BLOB_DOWNLOAD_HOST) {
                    downloadInterface.openExternal(url)
                }
            }
            webView.evaluateJavascript(loadDownloadJs(activity), null)
            webUIState.uiEvent = WebUIEvent.WebViewReady
        }
    }
}
