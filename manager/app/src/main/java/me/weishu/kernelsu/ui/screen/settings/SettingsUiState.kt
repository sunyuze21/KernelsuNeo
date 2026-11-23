package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import me.weishu.kernelsu.ui.UiMode

/**
 * 设置页的完整状态快照，默认值与首次安装时的行为一致。
 * 字段来自持久化设置或内核能力查询；能力状态非 supported 的项会通过对应的 *Status 字段置灰。
 */
@Immutable
data class SettingsUiState(
    // 取值来自 UiMode，决定整个管理器使用 Miuix 还是 Material 布局。
    val uiMode: String = UiMode.DEFAULT_VALUE,
    // 管理器自身更新检查开关，关闭后首页不再请求更新信息。
    val checkUpdate: Boolean = true,
    // 模块更新检查独立于管理器版本检查，可分别关闭。
    val checkModuleUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val enablePredictiveBack: Boolean = false,
    val enableSwipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = 1,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
    val moduleDescriptionMaxLines: Int = 4,
    val enableWebDebugging: Boolean = false,

    // Su Compat
    // 内核能力状态：supported/managed/forced/unsupported，决定控件是否可编辑。
    val suCompatStatus: String = "",
    val suCompatMode: Int = 0, // 0: enable default, 1: disable until reboot, 2: disable always
    // 内核当前是否启用 su 兼容；与用户选择的模式分开保存，便于回显真实状态。
    val isSuEnabled: Boolean = false,

    // Kernel Umount
    // 内核侧 umount 能力状态，非 supported 时禁止修改。
    val kernelUmountStatus: String = "",
    val isKernelUmountEnabled: Boolean = false,

    // SELinux Hide
    // SELinux 隐藏能力状态；该功能涉及内核策略，必须由内核确认可用。
    val selinuxHideStatus: String = "",
    val isSelinuxHideEnabled: Boolean = false,

    // SU Log
    // su 日志能力状态；关闭后不再记录授权与提权日志。
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,

    // Umount Modules
    // 新授权应用的默认 umount 行为，会写入应用 profile 的默认模板。
    val isDefaultUmountModules: Boolean = false,

    // ADB Root
    // adb root 能力状态，受内核与 SELinux 策略共同限制。
    val adbRootStatus: String = "",
    val isAdbRootEnabled: Boolean = false,

    // Shell SU ADB Only
    // 限制 shell 只能通过 adb 调用 su，用于缩小提权面。
    val shellSuAdbOnlyStatus: String = "",
    val isShellSuAdbOnlyEnabled: Boolean = false,

    // Module Integrity Check
    // 实验性：/data/adb/ksu/.enable_integrity_check 存在时，ksud 安装模块会校验签名。
    val isModuleIntegrityCheckEnabled: Boolean = false,

    // LKM 模式下才允许在设置里卸载内核。
    val isLkmMode: Boolean = false,
    // late-load（越狱）模式通常禁用刷写、卸载等危险操作。
    val isLateLoadMode: Boolean = false,

    // Auto Jailbreak
    // 仅 late-load 模式可用，重启后自动尝试重新激活。
    val autoJailbreak: Boolean = false,

    // Soft Reboot
    // 软重启只重启框架，不重启内核；late-load 下强制启用。
    val useSoftReboot: Boolean = false
)

/**
 * 设置页可执行动作集合。
 * 所有回调都指向 ViewModel 或导航器，UI 层不直接读写持久化存储。
 */
@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onSetCheckModuleUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenProfileTemplate: () -> Unit,
    val onSetSuCompatMode: (Int) -> Unit,
    val onSetKernelUmountEnabled: (Boolean) -> Unit,
    val onSetSelinuxHideEnabled: (Boolean) -> Unit,
    val onSetSulogEnabled: (Boolean) -> Unit,
    val onSetAdbRootEnabled: (Boolean) -> Unit,
    val onSetShellSuAdbOnlyEnabled: (Boolean) -> Unit,
    val onSetModuleIntegrityCheckEnabled: (Boolean) -> Unit,
    val onSetDefaultUmountModules: (Boolean) -> Unit,
    val onSetEnableWebDebugging: (Boolean) -> Unit,
    val onSetAutoJailbreak: (Boolean) -> Unit,
    val onSetUseSoftReboot: (Boolean) -> Unit,
    val onOpenAbout: () -> Unit,
)
