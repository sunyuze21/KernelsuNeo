package me.weishu.kernelsu.ui.webui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.R

/**
 * WebUI 的一次性 UI 事件。
 *
 * JS 对话框事件持有 JsResult，必须由 UI 层回调 confirm/cancel，否则网页
 * 会一直等待；其余事件只用于驱动界面状态。
 */
sealed class WebUIEvent {
    /** 页面尚未就绪，显示加载指示器。 */
    data object Loading : WebUIEvent()
    /** WebView 已准备完成。 */
    data object WebViewReady : WebUIEvent()
    /** 准备失败，展示错误后关闭页面。 */
    data class Error(val message: String) : WebUIEvent()
    /** JS 调用 ksu.exit()，请求关闭页面。 */
    data object Close : WebUIEvent()
    /** JS alert()；[result] 需要在 UI 关闭时确认。 */
    data class ShowAlert(val message: String, val result: JsResult) : WebUIEvent()
    /** JS confirm()；[result] 需要回传 true/false。 */
    data class ShowConfirm(val message: String, val result: JsResult) : WebUIEvent()
    /** JS prompt()；[result] 需要回传输入值或取消。 */
    data class ShowPrompt(val message: String, val defaultValue: String, val result: JsPromptResult) : WebUIEvent()
    /** 网页请求文件选择，[intent] 交给系统选择器。 */
    data class ShowFileChooser(val intent: Intent) : WebUIEvent()
}

/**
 * 单个 WebUI 页面的运行时状态。
 *
 * 持有 WebView、root shell、JS 桥接对象与对话框回调。调用 [dispose] 时必须
 * 按顺序清理，尤其是未完成的 [filePathCallback]，否则 WebView 会泄漏。
 */
class WebUIState {
    var webView: WebView? = null
    // 全局挂载命名空间的 root shell，供 WebUI 访问模块文件。
    var rootShell: Shell? = null
    var webViewInterface: WebViewInterface? = null
    var downloadInterface: WebUIDownloadInterface? = null
    // 模块目录固定为 /data/adb/modules/<id>，在 prepareWebView 中赋值。
    lateinit var modDir: String
    var moduleName: String = ""

    var uiEvent by mutableStateOf<WebUIEvent>(WebUIEvent.Loading)
    var isUrlLoaded = false
    // 当前设备 insets，WebView 线程也会读取，因此标记 Volatile。
    @Volatile
    var currentInsets: Insets = Insets(0, 0, 0, 0)
    var isInsetsEnabled by mutableStateOf(false)
    var webCanGoBack by mutableStateOf(false)
    // 文件选择器的回调；同一时间只允许一个未完成的请求。
    var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    /** 确认 alert 并把状态恢复为 WebViewReady。 */
    fun onAlertResult() {
        val event = uiEvent
        if (event is WebUIEvent.ShowAlert) {
            event.result.confirm()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    /** 回传 confirm 结果；用户取消或确认都会让网页继续执行。 */
    fun onConfirmResult(confirmed: Boolean) {
        val event = uiEvent
        if (event is WebUIEvent.ShowConfirm) {
            if (confirmed) event.result.confirm() else event.result.cancel()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    /** 回传 prompt 输入；null 表示用户取消。 */
    fun onPromptResult(result: String?) {
        val event = uiEvent
        if (event is WebUIEvent.ShowPrompt) {
            if (result != null) event.result.confirm(result) else event.result.cancel()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    /** 回传文件选择结果；null 表示取消，必须清空回调避免复用旧请求。 */
    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
        uiEvent = WebUIEvent.WebViewReady
    }

    /** 请求关闭 WebUI（由 JS 的 ksu.exit() 触发）。 */
    fun requestExit() {
        uiEvent = WebUIEvent.Close
    }

    /**
     * 释放 WebView、JS 桥接与 root shell。
     *
     * 顺序很重要：先恢复任务描述，再销毁桥接对象关闭文件句柄，最后销毁
     * WebView 并关闭 root shell，避免回调访问已释放资源。
     */
    fun dispose(activity: Activity) {
        activity.setTaskDescription(activity.getString(R.string.app_name))
        downloadInterface?.destroy()
        downloadInterface = null
        webViewInterface?.destroy()
        webViewInterface = null
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        rootShell?.close()
        rootShell = null
    }
}
