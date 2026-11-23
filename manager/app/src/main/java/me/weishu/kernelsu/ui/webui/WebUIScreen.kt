package me.weishu.kernelsu.ui.webui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * 注册文件选择器结果回调。
 *
 * 支持多选（clipData）与单选；取消或失败传 null，交给 onFileChooserResult
 * 回传给 WebView。
 */
@Composable
fun rememberFileLauncher(webUIState: WebUIState): ActivityResultLauncher<Intent> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                data.clipData?.let { clipData ->
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } ?: data.data?.let { arrayOf(it) }
            }
        } else null
        webUIState.onFileChooserResult(uris)
    }
}

/**
 * WebUI 的 Compose 容器：托管 WebView、返回键、安全区与对话框。
 *
 * WebUI 可选择自行处理 insets（通过 internal/insets.css），此时 Compose
 * 只让出 IME 空间，避免重复留白。
 */
@Composable
fun WebUIScreen(webUIState: WebUIState) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 应用自身绘制区域（状态栏/导航栏/刘海）。
    val drawingInsets = WindowInsets.safeDrawing
    // 需要传给网页的设备 insets（系统栏 + 刘海）。
    val deviceInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val imeInsets = WindowInsets.ime
    val innerPadding = if (webUIState.isInsetsEnabled) imeInsets.asPaddingValues() else drawingInsets.asPaddingValues()
    val fileLauncher = rememberFileLauncher(webUIState)

    // insets 变化时通过 JS 更新 CSS 变量；仅在网页要求启用时执行。
    LaunchedEffect(density, layoutDirection, deviceInsets, webUIState.isInsetsEnabled) {
        if (!webUIState.isInsetsEnabled) {
            return@LaunchedEffect
        }
        snapshotFlow {
            Insets(
                top = (deviceInsets.getTop(density) / density.density).toInt(),
                bottom = (deviceInsets.getBottom(density) / density.density).toInt(),
                left = (deviceInsets.getLeft(density, layoutDirection) / density.density).toInt(),
                right = (deviceInsets.getRight(density, layoutDirection) / density.density).toInt()
            )
        }.collect { newInsets ->
            if (webUIState.currentInsets != newInsets) {
                webUIState.currentInsets = newInsets
                webUIState.webView?.evaluateJavascript(newInsets.js, null)
            }
        }
    }

    // 网页可后退时优先交给 WebView，避免直接退出页面。
    BackHandler(enabled = webUIState.webCanGoBack) {
        webUIState.webView?.goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        webUIState.webView?.let { webView ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { _ ->
                    webView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        if (!webUIState.isUrlLoaded) {
                            // 通过虚拟域名加载 webroot，由 SuFilePathHandler 以 root 提供文件。
                            val homePage = "https://mui.kernelsu.org/index.html"
                            if (width > 0 && height > 0) {
                                loadUrl(homePage)
                                webUIState.isUrlLoaded = true
                            } else {
                                val listener = object : View.OnLayoutChangeListener {
                                    // 等到 WebView 有实际尺寸后再加载，避免首帧白屏/布局错乱。
                                    override fun onLayoutChange(
                                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                                    ) {
                                        if (v.width > 0 && v.height > 0) {
                                            (v as WebView).loadUrl(homePage)
                                            webUIState.isUrlLoaded = true
                                            v.removeOnLayoutChangeListener(this)
                                        }
                                    }
                                }
                                addOnLayoutChangeListener(listener)
                            }
                        }
                    }
                },
                update = { view ->
                    view.requestLayout()
                }
            )
        }
    }

    when (LocalUiMode.current) {
        UiMode.Miuix -> HandleWebUIEventMiuix(webUIState, fileLauncher)
        UiMode.Material -> HandleWebUIEventMaterial(webUIState, fileLauncher)
    }

    HandleWebViewLifecycle(webUIState)
    HandleConfigurationChanges(webUIState)
}

// 将宿主的生命周期转发给 WebView，暂停/恢复 JS 定时器与媒体播放。
@Composable
private fun HandleWebViewLifecycle(webUIState: WebUIState) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, webUIState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webUIState.webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webUIState.webView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

// 字体缩放变化时同步 WebView 的 textZoom，保持与系统设置一致。
@Composable
private fun HandleConfigurationChanges(webUIState: WebUIState) {
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.fontScale, webUIState.webView) {
        webUIState.webView?.settings?.textZoom = (configuration.fontScale * 100).toInt()
    }
}
