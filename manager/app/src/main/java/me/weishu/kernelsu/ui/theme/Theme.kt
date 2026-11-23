package me.weishu.kernelsu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * 主题色彩模式。
 *
 * [value] 为持久化的整型编号，顺序被历史版本共享，不能随意调整；
 * MONET_* 表示使用动态取色（Material You），DARK_AMOLED 表示在深色基础上使用纯黑背景。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    companion object {
        /**
         * 按编号还原模式，未知编号回退到跟随系统，兼容被篡改或过期的配置。
         */
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }

    /** 是否跟随系统深浅色（普通动态色模式同样跟随）。 */
    val isSystem: Boolean get() = value == 0 || value == 3
    /** 是否强制深色，包含纯黑变体。 */
    val isDark: Boolean get() = value == 2 || value == 5 || value == 6
    /** 是否使用纯黑背景，仅对 OLED 屏幕省电有意义。 */
    val isAmoled: Boolean get() = value == 6
    /** 是否启用动态取色。 */
    val isMonet: Boolean get() = value >= 3

    /**
     * 映射到等价的非动态色模式，用于用户在 Miuix 界面下关闭 Monet 时保留深浅色意图。
     */
    fun toNonMonetMode(): Int = when (this) {
        MONET_SYSTEM -> 0
        MONET_LIGHT -> 1
        MONET_DARK, DARK_AMOLED -> 2
        else -> value
    }

    /**
     * 映射到等价的动态色模式，用于开启 Monet 时保留原有深浅色意图。
     */
    fun toMonetMode(): Int = when (this) {
        SYSTEM -> 3
        LIGHT -> 4
        DARK -> 5
        else -> value
    }
}

/**
 * 主题相关的完整配置快照。
 *
 * 由 [ThemeController] 从持久化仓库读出后传给 Compose 主题；作为不可变 data class，
 * 值变化时会触发主题层重组。
 */
data class AppSettings(
    val colorMode: ColorMode,
    val keyColor: Int,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion,
)

/**
 * 判断某调色板风格是否支持 2025 版色彩规范。
 *
 * materialkolor 仅部分风格实现了 SPEC_2025，其余风格使用该规范会得到异常配色。
 */
val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
            this == PaletteStyle.Neutral ||
            this == PaletteStyle.Vibrant ||
            this == PaletteStyle.Expressive

/**
 * 将色彩规范降级为与调色板风格兼容的版本。
 *
 * 这是纯函数，便于在主题构建时安全调用；不支持的组合会静默回退到 2021 规范。
 */
fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

/**
 * 主题配置入口：负责把持久化设置转换为可用于 Compose 的 [AppSettings]。
 *
 * 读取过程会做兼容性处理（Miuix 与 Monet 开关的联动、非法枚举值回退），
 * 因此这里不是只读映射，而是在运行时修正不一致的配置。
 */
object ThemeController {
    /**
     * 从设置仓库读取并规整主题配置。
     *
     * 默认参数便于在非 Compose 上下文（如启动阶段）直接调用；
     * 传入自定义 repo 主要用于测试。
     */
    fun getAppSettings(repo: SettingsRepository = SettingsRepositoryImpl()): AppSettings {
        val uiMode = repo.uiMode
        // 原始持久化值，后续可能因 Miuix 的 Monet 开关而被纠正
        var colorModeValue = repo.themeMode

        // Miuix 界面下由独立开关控制动态取色，需要与主题模式保持一致，避免出现"开关显示开但配色不动态"的割裂
        if (uiMode == "miuix") {
            val miuixMonet = repo.miuixMonet
            val colorMode = ColorMode.fromValue(colorModeValue)
            colorModeValue = if (!miuixMonet && colorMode.isMonet) {
                colorMode.toNonMonetMode()
            } else if (miuixMonet && !colorMode.isMonet) {
                colorMode.toMonetMode()
            } else {
                colorModeValue
            }
        }

        val colorMode = ColorMode.fromValue(colorModeValue)
        val keyColor = repo.keyColor
        val paletteStyleStr = repo.colorStyle
        // 枚举名可能因版本升级而失效，解析失败时用默认风格兜底
        val paletteStyle = try {
            PaletteStyle.valueOf(paletteStyleStr)
        } catch (_: Exception) {
            PaletteStyle.TonalSpot
        }
        val colorSpecStr = repo.colorSpec
        // 同上：未知规范名回退到最新默认值
        val colorSpec = try {
            ColorSpec.SpecVersion.valueOf(colorSpecStr)
        } catch (_: Exception) {
            ColorSpec.SpecVersion.SPEC_2025
        }

        return AppSettings(colorMode, keyColor, paletteStyle, colorSpec)
    }
}

/**
 * 全局主题根节点，按当前 UI 模式分派到 Miuix 或 Material 实现。
 *
 * uiMode 通过 CompositionLocal 提供默认值；切换模式会替换整棵子树，
 * 因此两个分支内部的 CompositionLocal 值不会跨模式复用。
 */
@Composable
fun KernelSUTheme(
    appSettings: AppSettings = ThemeController.getAppSettings(),
    uiMode: UiMode = LocalUiMode.current,
    content: @Composable () -> Unit
) {

    when (uiMode) {
        UiMode.Miuix -> MiuixKernelSUTheme(
            appSettings = appSettings,
            content = content
        )

        UiMode.Material -> MaterialKernelSUTheme(
            appSettings = appSettings,
            content = content
        )
    }
}

/**
 * 当前是否应呈现深色界面。
 *
 * 标注 ReadOnlyComposable 表示不写入组合状态，可安全地在任意 Composable 中读取；
 * 强制模式优先于系统设置，未指定时才跟随系统。
 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    return when (LocalColorMode.current) {
        1, 4 -> false  // Force light mode
        2, 5, 6 -> true   // Force dark mode
        else -> isSystemInDarkTheme()  // Follow system (0 or default)
    }
}

/**
 * 向下传递当前的 ColorMode 原始值（Int）。
 *
 * 使用 static 变体：主题模式变化时应由主题根节点重建，而不是依赖细粒度重组。
 */
val LocalColorMode = staticCompositionLocalOf { 0 }

/** 是否启用背景模糊效果，供 Miuix 组件决定是否绘制模糊层。 */
val LocalEnableBlur = staticCompositionLocalOf { false }

/** 底部导航栏是否浮动显示，影响布局内边距与背景处理。 */
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

/** 浮动底栏是否叠加模糊效果，与 [LocalEnableBlur] 相互独立。 */
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

/** 是否在导航项上显示角标（如更新提示），默认开启。 */
val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/** 模块描述的最大显示行数，超出后省略，避免列表项高度失控。 */
val LocalModuleDescriptionMaxLines = staticCompositionLocalOf { 4 }
