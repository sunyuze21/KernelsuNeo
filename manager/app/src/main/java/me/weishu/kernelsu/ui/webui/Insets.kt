package me.weishu.kernelsu.ui.webui

/**
 * Insets data class from GitHub@MMRLApp/WebUI-X-Portable
 *
 * Data class representing insets (top, bottom, left, right) for a view.
 *
 * This class provides methods to generate CSS code that can be injected into a WebView
 * to apply these insets as CSS variables. This is useful for adapting web content
 * to the safe areas of a device screen, considering notches, status bars, and navigation bars.
 *
 * @property top The top inset value in pixels.
 * @property bottom The bottom inset value in pixels.
 * @property left The left inset value in pixels.
 * @property right The right inset value in pixels.
 *
 * 说明：单位为 px，注入时直接写 CSS 变量；同时兼容 Framework7 的
 * --f7-safe-area-* 变量，方便不同 WebUI 框架复用。
 */
data class Insets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
) {
    // 生成 :root CSS 变量，页面加载后注入即可生效。
    val css
        get() = buildString {
            appendLine(":root {")
            appendLine("\t--safe-area-inset-top: ${top}px;")
            appendLine("\t--safe-area-inset-right: ${right}px;")
            appendLine("\t--safe-area-inset-bottom: ${bottom}px;")
            appendLine("\t--safe-area-inset-left: ${left}px;")
            appendLine("\t--window-inset-top: var(--safe-area-inset-top, 0px);")
            appendLine("\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);")
            appendLine("\t--window-inset-left: var(--safe-area-inset-left, 0px);")
            appendLine("\t--window-inset-right: var(--safe-area-inset-right, 0px);")
            appendLine("\t--f7-safe-area-top: var(--window-inset-top, 0px) !important;")
            appendLine("\t--f7-safe-area-bottom: var(--window-inset-bottom, 0px) !important;")
            appendLine("\t--f7-safe-area-left: var(--window-inset-left, 0px) !important;")
            appendLine("\t--f7-safe-area-right: var(--window-inset-right, 0px) !important;")
            append("}")
        }
    // 运行时更新 CSS 变量的 JS 片段，用于旋转/分屏后即时调整。
    val js
        get() = buildString {
            append("(function() {")
            append(" var s = document.documentElement.style;")
            append(" s.setProperty('--safe-area-inset-top', '${top}px');")
            append(" s.setProperty('--safe-area-inset-right', '${right}px');")
            append(" s.setProperty('--safe-area-inset-bottom', '${bottom}px');")
            append(" s.setProperty('--safe-area-inset-left', '${left}px');")
            append("})();")
        }
}
