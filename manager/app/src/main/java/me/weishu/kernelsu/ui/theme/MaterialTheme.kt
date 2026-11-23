package me.weishu.kernelsu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import me.weishu.kernelsu.ui.webui.MonetColorsProvider

/**
 * Material 3 Expressive 主题实现。
 *
 * 负责根据 [AppSettings] 计算深浅色、动态取色与纯黑模式，并把最终配色同步到
 * 系统栏外观与 WebUI 的 CSS 变量；主题变化时需要重建配色方案并触发重组。
 */
@Composable
fun MaterialKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    // 强制模式优先；仅"跟随系统"时才采用系统深色状态
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.colorMode.isAmoled
    // keyColor 为 0 表示未指定强调色，此时走系统动态取色
    val dynamicColor = appSettings.keyColor == 0

    // 配色只依赖这几个输入，避免每次重组都重新计算调色板
    val colorScheme = rememberKernelSUColorScheme(
        seedColor = if (dynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoledMode,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    // 在组合外修改窗口装饰：系统栏图标颜色必须与主题深浅保持一致，否则会出现不可读的图标
    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // 包装为可动画状态，主题切换时颜色平滑过渡而非突变
    val animatedColorScheme = colorScheme.animateAsState()

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = {
            // 同步给模块 WebUI，使网页配色与原生界面一致
            MonetColorsProvider.UpdateCss(colorScheme)
            content()
        }
    )
}
