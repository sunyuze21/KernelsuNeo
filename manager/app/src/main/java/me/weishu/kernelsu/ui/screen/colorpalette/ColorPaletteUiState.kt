package me.weishu.kernelsu.ui.screen.colorpalette

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import me.weishu.kernelsu.ui.screen.settings.SettingsUiState
import me.weishu.kernelsu.ui.theme.ColorMode

/**
 * 颜色设置页的只读展示状态。
 *
 * 同时保留原始 [uiState]（用于读取滑块等持久化数值）与已解析的枚举值：
 * [currentPaletteStyle] / [currentColorSpec] 在解析失败时会被上层回退成默认值，
 * 因此界面无需再次处理非法字符串。
 */
@Immutable
data class ColorPaletteUiState(
    val uiState: SettingsUiState,
    // 由 themeMode 解析出的实际明暗模式（含跟随系统与 AMOLED 变体）
    val currentColorMode: ColorMode,
    // 当前配色风格，影响 MaterialKolor 生成的颜色方案
    val currentPaletteStyle: PaletteStyle,
    // 当前颜色规范版本，不同版本生成的色调映射不同
    val currentColorSpec: ColorSpec.SpecVersion,
)

/**
 * 颜色设置页的全部用户操作回调。
 *
 * 统一成不可变集合后传给两套 UI 实现，保证 Material 与 Miuix 行为一致；
 * 回调只负责转交给 ViewModel，不在此处做持久化。
 */
@Immutable
data class ColorPaletteScreenActions(
    val onBack: () -> Unit,
    val onSetThemeMode: (Int) -> Unit,
    val onSetMiuixMonet: (Boolean) -> Unit,
    // 0 表示跟随系统壁纸取色，其余为内置主色 ARGB 值
    val onSetKeyColor: (Int) -> Unit,
    val onSetColorMode: (ColorMode) -> Unit,
    val onSetColorStyle: (String) -> Unit,
    val onSetColorSpec: (String) -> Unit,
    val onSetEnableBlur: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBar: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    val onSetEnableNavigationBadge: (Boolean) -> Unit,
    val onSetEnablePredictiveBack: (Boolean) -> Unit,
    val onSetEnableSwipeDismiss: (Boolean) -> Unit,
    val onSetPagerInterceptionMode: (Int) -> Unit,
    val onSetPageScale: (Float) -> Unit,
    val onSetModuleDescriptionMaxLines: (Int) -> Unit,
)
