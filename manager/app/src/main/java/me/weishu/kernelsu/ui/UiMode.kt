package me.weishu.kernelsu.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面风格模式：决定整套 UI 使用 Miuix 还是 Material 组件体系。
 *
 * [value] 是持久化到配置中的稳定字符串，修改它会破坏已有用户设置的兼容性。
 */
enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        /**
         * 将持久化的字符串还原为枚举，未知值一律回退到 Miuix，避免旧配置导致崩溃。
         */
        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }

        /** 首次启动或配置缺失时采用的默认模式。 */
        val DEFAULT_VALUE = Miuix.value
    }
}

/**
 * 以 CompositionLocal 形式向下传递当前 UI 模式。
 *
 * 使用 static 版本表示该值在组合树内被视为不频繁变化；切换模式时应由上层重建整棵树，
 * 因此不要期望这里的变更能独立触发精确重组。
 */
val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
