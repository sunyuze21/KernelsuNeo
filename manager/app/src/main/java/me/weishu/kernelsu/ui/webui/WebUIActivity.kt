package me.weishu.kernelsu.ui.webui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.ThemeController
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

/**
 * 承载模块 WebUI 的独立 Activity。
 *
 * WebUI 需要 JavaScript 与 root 文件访问，因此单独开进程内页面而不是嵌入
 * 主界面；主题、UI 模式与主应用保持一致。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity() {
    // 初始化边到边显示，并监听主题/设置变化以实时刷新 WebUI。
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
            val settingsRepo = remember { SettingsRepositoryImpl() }
            var appSettings by remember { mutableStateOf(ThemeController.getAppSettings()) }
            var uiModeValue by remember { mutableStateOf(settingsRepo.uiMode) }
            val uiMode = remember(uiModeValue) {
                UiMode.fromValue(uiModeValue)
            }

            // 监听设置变化：颜色相关键刷新主题，ui_mode 切换 Miuix/Material。
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key in listOf("color_mode", "key_color", "color_style", "color_spec")) {
                        appSettings = ThemeController.getAppSettings()
                    } else if (key == "ui_mode") {
                        uiModeValue = settingsRepo.uiMode
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            CompositionLocalProvider(LocalUiMode provides uiMode) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                    MainContent(activity = this, onFinish = { finish() })
                }
            }
        }
    }
}

/**
 * WebUI 主内容：解析模块 id、准备 WebView，并根据状态展示加载/错误/页面。
 *
 * 模块 id 来自 Intent URI，缺失或模块不可用时直接结束页面。
 */
@Composable
private fun MainContent(activity: ComponentActivity, onFinish: () -> Unit) {
    // 模块 id 由启动方通过 `?id=` 传入。
    val moduleId = remember { activity.intent.data?.getQueryParameter("id") }
    // 跨重组保留 WebView 与其状态，避免重复创建。
    val webUIState = remember { WebUIState() }

    LaunchedEffect(moduleId) {
        if (moduleId == null) {
            onFinish()
            return@LaunchedEffect
        }
        prepareWebView(activity, moduleId, webUIState)
    }

    DisposableEffect(Unit) {
        onDispose { webUIState.dispose(activity) }
    }

    when (val event = webUIState.uiEvent) {
        is WebUIEvent.Error -> {
            LaunchedEffect(event) {
                Toast.makeText(activity, event.message, Toast.LENGTH_SHORT).show()
                onFinish()
            }
        }

        is WebUIEvent.Close -> {
            LaunchedEffect(event) { onFinish() }
        }

        else -> {}
    }
    // 仅“加载中”显示转圈，其余事件（弹窗/关闭）由页面自身处理。
    val isLoading = webUIState.uiEvent is WebUIEvent.Loading

    Crossfade(targetState = isLoading, animationSpec = tween(300)) { loading ->
        if (loading) {
            LoadingContent()
        } else {
            WebUIScreen(webUIState = webUIState)
        }
    }
}

/** 加载占位页：按 UI 模式选择 Miuix 或 Material 的进度指示器。 */
@Composable
private fun LoadingContent() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                InfiniteProgressIndicator()
            }
        }

        UiMode.Material -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.LoadingIndicator()
            }
        }
    }
}
