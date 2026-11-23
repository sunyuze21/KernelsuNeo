package me.weishu.kernelsu.ui.webui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.atomic.AtomicReference

/**
 * @author rifsxd
 * @date 2025/6/2.
 *
 * 把原生主题色导出为 CSS 变量，注入 WebUI 后让网页配色跟随应用主题。
 * Miuix 与 Material 两套色板字段不同，分别生成。
 */
object MonetColorsProvider {

    // 原子引用保存最新 CSS，Service/WebView 侧可无锁读取。
    private val colorsCss: AtomicReference<String?> = AtomicReference(null)

    /** 返回当前主题对应的 CSS 变量文本；尚未初始化时返回空串。 */
    fun getColorsCss(): String {
        return colorsCss.get() ?: ""
    }

    /** 根据当前 UI 模式刷新 CSS 变量；Compose 重组时自动调用。 */
    @Composable
    fun UpdateCss(materialColorScheme: ColorScheme? = null) {
        when (LocalUiMode.current) {
            UiMode.Miuix -> UpdateCssMiuix()
            UiMode.Material -> UpdateCssMaterial(materialColorScheme ?: MaterialTheme.colorScheme)
        }
    }

    // Miuix 主题：字段名沿用 Material 命名，供网页端统一使用。
    @Composable
    private fun UpdateCssMiuix() {
        val colorScheme = MiuixTheme.colorScheme

        // 主题色变化时重新生成 CSS；协程取消由 Compose 管理。
        LaunchedEffect(colorScheme) {
            // key 与 Material 的 ColorScheme 字段保持同名，方便 WebUI 统一引用。
            val monetColors = mapOf(
                "primary" to colorScheme.primary.toCssValue(),
                "onPrimary" to colorScheme.onPrimary.toCssValue(),
                "primaryContainer" to colorScheme.primaryContainer.toCssValue(),
                "onPrimaryContainer" to colorScheme.onPrimaryContainer.toCssValue(),
                "inversePrimary" to colorScheme.primaryVariant.toCssValue(),
                "secondary" to colorScheme.secondary.toCssValue(),
                "onSecondary" to colorScheme.onSecondary.toCssValue(),
                "secondaryContainer" to colorScheme.secondaryContainer.toCssValue(),
                "onSecondaryContainer" to colorScheme.onSecondaryContainer.toCssValue(),
                "tertiary" to colorScheme.tertiaryContainerVariant.toCssValue(),
                "onTertiary" to colorScheme.tertiaryContainer.toCssValue(),
                "tertiaryContainer" to colorScheme.tertiaryContainer.toCssValue(),
                "onTertiaryContainer" to colorScheme.onTertiaryContainer.toCssValue(),
                "background" to colorScheme.background.toCssValue(),
                "onBackground" to colorScheme.onBackground.toCssValue(),
                "surface" to colorScheme.surface.toCssValue(),
                "tonalSurface" to colorScheme.surfaceContainer.toCssValue(),
                "onSurface" to colorScheme.onSurface.toCssValue(),
                "surfaceVariant" to colorScheme.surfaceVariant.toCssValue(),
                "onSurfaceVariant" to colorScheme.onSurfaceVariantSummary.toCssValue(),
                "surfaceTint" to colorScheme.primary.toCssValue(),
                "inverseSurface" to colorScheme.disabledOnSurface.toCssValue(),
                "inverseOnSurface" to colorScheme.surfaceContainer.toCssValue(),
                "error" to colorScheme.error.toCssValue(),
                "onError" to colorScheme.onError.toCssValue(),
                "errorContainer" to colorScheme.errorContainer.toCssValue(),
                "onErrorContainer" to colorScheme.onErrorContainer.toCssValue(),
                "outline" to colorScheme.outline.toCssValue(),
                "outlineVariant" to colorScheme.dividerLine.toCssValue(),
                "scrim" to colorScheme.windowDimming.toCssValue(),
                "surfaceBright" to colorScheme.surface.toCssValue(),
                "surfaceDim" to colorScheme.surface.toCssValue(),
                "surfaceContainer" to colorScheme.surfaceContainer.toCssValue(),
                "surfaceContainerHigh" to colorScheme.surfaceContainerHigh.toCssValue(),
                "surfaceContainerHighest" to colorScheme.surfaceContainerHighest.toCssValue(),
                "surfaceContainerLow" to colorScheme.surfaceContainer.toCssValue(),
                "surfaceContainerLowest" to colorScheme.surfaceContainer.toCssValue(),
                "filledTonalButtonContentColor" to colorScheme.onPrimaryContainer.toCssValue(),
                "filledTonalButtonContainerColor" to colorScheme.secondaryContainer.toCssValue(),
                "filledTonalButtonDisabledContentColor" to colorScheme.onSurfaceVariantSummary.toCssValue(),
                "filledTonalButtonDisabledContainerColor" to colorScheme.surfaceVariant.toCssValue(),
                "filledCardContentColor" to colorScheme.onPrimaryContainer.toCssValue(),
                "filledCardContainerColor" to colorScheme.primaryContainer.toCssValue(),
                "filledCardDisabledContentColor" to colorScheme.onSurfaceVariantSummary.toCssValue(),
                "filledCardDisabledContainerColor" to colorScheme.surfaceVariant.toCssValue()
            )

            colorsCss.set(monetColors.toCssVars())
        }
    }

    // Material 主题：直接映射官方 ColorScheme 字段。
    @Composable
    private fun UpdateCssMaterial(colorScheme: ColorScheme) {
        LaunchedEffect(colorScheme) {
            val monetColors = mapOf(
                "primary" to colorScheme.primary.toCssValue(),
                "onPrimary" to colorScheme.onPrimary.toCssValue(),
                "primaryContainer" to colorScheme.primaryContainer.toCssValue(),
                "onPrimaryContainer" to colorScheme.onPrimaryContainer.toCssValue(),
                "inversePrimary" to colorScheme.inversePrimary.toCssValue(),
                "secondary" to colorScheme.secondary.toCssValue(),
                "onSecondary" to colorScheme.onSecondary.toCssValue(),
                "secondaryContainer" to colorScheme.secondaryContainer.toCssValue(),
                "onSecondaryContainer" to colorScheme.onSecondaryContainer.toCssValue(),
                "tertiary" to colorScheme.tertiary.toCssValue(),
                "onTertiary" to colorScheme.onTertiary.toCssValue(),
                "tertiaryContainer" to colorScheme.tertiaryContainer.toCssValue(),
                "onTertiaryContainer" to colorScheme.onTertiaryContainer.toCssValue(),
                "background" to colorScheme.background.toCssValue(),
                "onBackground" to colorScheme.onBackground.toCssValue(),
                "surface" to colorScheme.surface.toCssValue(),
                "tonalSurface" to colorScheme.surfaceColorAtElevation(1.dp).toCssValue(),
                "onSurface" to colorScheme.onSurface.toCssValue(),
                "surfaceVariant" to colorScheme.surfaceVariant.toCssValue(),
                "onSurfaceVariant" to colorScheme.onSurfaceVariant.toCssValue(),
                "surfaceTint" to colorScheme.surfaceTint.toCssValue(),
                "inverseSurface" to colorScheme.inverseSurface.toCssValue(),
                "inverseOnSurface" to colorScheme.inverseOnSurface.toCssValue(),
                "error" to colorScheme.error.toCssValue(),
                "onError" to colorScheme.onError.toCssValue(),
                "errorContainer" to colorScheme.errorContainer.toCssValue(),
                "onErrorContainer" to colorScheme.onErrorContainer.toCssValue(),
                "outline" to colorScheme.outline.toCssValue(),
                "outlineVariant" to colorScheme.outlineVariant.toCssValue(),
                "scrim" to colorScheme.scrim.toCssValue(),
                "surfaceBright" to colorScheme.surfaceBright.toCssValue(),
                "surfaceDim" to colorScheme.surfaceDim.toCssValue(),
                "surfaceContainer" to colorScheme.surfaceContainer.toCssValue(),
                "surfaceContainerHigh" to colorScheme.surfaceContainerHigh.toCssValue(),
                "surfaceContainerHighest" to colorScheme.surfaceContainerHighest.toCssValue(),
                "surfaceContainerLow" to colorScheme.surfaceContainerLow.toCssValue(),
                "surfaceContainerLowest" to colorScheme.surfaceContainerLowest.toCssValue(),
                "filledTonalButtonContentColor" to colorScheme.onPrimaryContainer.toCssValue(),
                "filledTonalButtonContainerColor" to colorScheme.secondaryContainer.toCssValue(),
                "filledTonalButtonDisabledContentColor" to colorScheme.onSurfaceVariant.toCssValue(),
                "filledTonalButtonDisabledContainerColor" to colorScheme.surfaceVariant.toCssValue(),
                "filledCardContentColor" to colorScheme.onPrimaryContainer.toCssValue(),
                "filledCardContainerColor" to colorScheme.primaryContainer.toCssValue(),
                "filledCardDisabledContentColor" to colorScheme.onSurfaceVariant.toCssValue(),
                "filledCardDisabledContainerColor" to colorScheme.surfaceVariant.toCssValue()
            )

            colorsCss.set(monetColors.toCssVars())
        }
    }

    // 把键值对拼成 :root { --key: value; } 形式的 CSS。
    private fun Map<String, String>.toCssVars(): String {
        return buildString {
            append(":root {\n")
            for ((k, v) in this@toCssVars) {
                append("  --$k: $v;\n")
            }
            append("}\n")
        }
    }

    // Compose Color 转 CSS 十六进制；带透明度时输出 8 位 #RRGGBBAA。
    private fun Color.toCssValue(): String {
        // 把 0..1 的浮点通道转成两位十六进制。
        fun Float.toHex(): String {
            return (this * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        }
        return if (alpha == 1f) {
            "#${red.toHex()}${green.toHex()}${blue.toHex()}"
        } else {
            "#${red.toHex()}${green.toHex()}${blue.toHex()}${alpha.toHex()}"
        }
    }
}
