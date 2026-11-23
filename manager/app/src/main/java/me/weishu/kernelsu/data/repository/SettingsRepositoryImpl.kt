package me.weishu.kernelsu.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.magica.BootCompletedReceiver
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.screen.modulerepo.RepoSort
import me.weishu.kernelsu.ui.util.execKsud
import me.weishu.kernelsu.ui.util.getFeaturePersistValue
import me.weishu.kernelsu.ui.util.getFeatureValue
import me.weishu.kernelsu.ui.util.getFeatureStatus
import me.weishu.kernelsu.ui.util.getRootShell
import java.security.SecureRandom

/** 设置存储的 SharedPreferences 文件名。 */
private const val SETTINGS_PREFS = "settings"
/** 软重启偏好的 key，同时被 [isSoftRebootPreferred] 与 [SettingsRepositoryImpl] 使用。 */
private const val KEY_USE_SOFT_REBOOT = "soft_reboot"
/** 模块签名验证的标记文件；存在即表示 ksud 会校验模块签名。 */
private const val INTEGRITY_CHECK_PATH = "/data/adb/ksu/.enable_integrity_check"

/** Prefer soft reboot: always in jailbreak mode, or when the setting is enabled. */
fun isSoftRebootPreferred(): Boolean =
    Natives.isLateLoadMode || ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_SOFT_REBOOT, false)

/** 基于 SharedPreferences 的设置实现；feature 状态则转调 Natives / ksud。 */
class SettingsRepositoryImpl : SettingsRepository {

    private companion object {
        /** 外部 Intent token 的存储 key。 */
        private const val INTENT_TOKEN_KEY = "intent_token"
        /** 用于生成 Intent token 的加密安全随机源。 */
        private val secureRandom = SecureRandom()
    }

    /** 延迟打开设置文件，避免 Application 初始化阶段过早访问磁盘。 */
    private val prefs by lazy {
        ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    }

    override var uiMode: String
        get() = prefs.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE
        set(value) = prefs.edit { putString("ui_mode", value) }

    override var checkUpdate: Boolean
        get() = prefs.getBoolean("check_update", true)
        set(value) = prefs.edit { putBoolean("check_update", value) }

    override var checkModuleUpdate: Boolean
        get() = prefs.getBoolean("module_check_update", true)
        set(value) = prefs.edit { putBoolean("module_check_update", value) }

    override var themeMode: Int
        get() = prefs.getInt("color_mode", 0)
        set(value) = prefs.edit { putInt("color_mode", value) }

    override var miuixMonet: Boolean
        get() = prefs.getBoolean("miuix_monet", false)
        set(value) = prefs.edit { putBoolean("miuix_monet", value) }

    override var keyColor: Int
        get() = prefs.getInt("key_color", 0)
        set(value) = prefs.edit { putInt("key_color", value) }

    override var colorStyle: String
        get() = prefs.getString("color_style", PaletteStyle.TonalSpot.name) ?: PaletteStyle.TonalSpot.name
        set(value) = prefs.edit { putString("color_style", value) }

    override var colorSpec: String
        get() = prefs.getString("color_spec", ColorSpec.SpecVersion.SPEC_2025.name) ?: ColorSpec.SpecVersion.SPEC_2025.name
        set(value) = prefs.edit { putString("color_spec", value) }

    override var enablePredictiveBack: Boolean
        get() = prefs.getBoolean("enable_predictive_back", false)
        set(value) = prefs.edit { putBoolean("enable_predictive_back", value) }

    override var enableSwipeDismiss: Boolean
        get() = prefs.getBoolean("enable_swipe_dismiss", true)
        set(value) = prefs.edit { putBoolean("enable_swipe_dismiss", value) }

    override var pagerInterceptionMode: Int
        get() = prefs.getInt("pager_interception_mode", 1)
        // 合法范围只有 0..2，写入前统一裁剪，避免异常值破坏手势判断。
        set(value) = prefs.edit { putInt("pager_interception_mode", value.coerceIn(0, 2)) }

    override var enableBlur: Boolean
        get() = prefs.getBoolean("enable_blur", false)
        set(value) = prefs.edit { putBoolean("enable_blur", value) }

    override var enableFloatingBottomBar: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar", value) }

    override var enableFloatingBottomBarBlur: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar_blur", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar_blur", value) }

    override var enableNavigationBadge: Boolean
        get() = prefs.getBoolean("enable_navigation_badge", true)
        set(value) = prefs.edit { putBoolean("enable_navigation_badge", value) }

    override var navigationRailExpanded: Boolean
        get() = prefs.getBoolean("nav_rail_expanded", false)
        set(value) = prefs.edit { putBoolean("nav_rail_expanded", value) }

    override var pageScale: Float
        get() = prefs.getFloat("page_scale", 1.0f)
        set(value) = prefs.edit { putFloat("page_scale", value) }

    override var moduleDescriptionMaxLines: Int
        get() = prefs.getInt("module_description_max_lines", 4)
        set(value) = prefs.edit { putInt("module_description_max_lines", value) }

    override var enableWebDebugging: Boolean
        get() = prefs.getBoolean("enable_web_debugging", false)
        set(value) = prefs.edit { putBoolean("enable_web_debugging", value) }

    override var moduleSortEnabledFirst: Boolean
        get() = prefs.getBoolean("module_sort_enabled_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_enabled_first", value) }

    override var moduleSortActionFirst: Boolean
        get() = prefs.getBoolean("module_sort_action_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_action_first", value) }

    override var moduleRepoSortOrder: String
        // 兼容旧 key：旧版本存的是序号，新版本存枚举名。
        get() = RepoSort.fromStored(
            name = prefs.getString("module_repo_sort", null),
            legacyOrdinal = if (prefs.contains("module_repo_sort_order")) prefs.getInt("module_repo_sort_order", 0) else null,
        ).name
        set(value) = prefs.edit {
            putString("module_repo_sort", value)
            // 迁移完成后删除旧 key，避免下次读取又被旧值影响。
            remove("module_repo_sort_order")
        }

    override var superuserShowSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit { putBoolean("show_system_apps", value) }

    override var superuserShowOnlyPrimaryUserApps: Boolean
        get() = prefs.getBoolean("show_only_primary_user_apps", false)
        set(value) = prefs.edit { putBoolean("show_only_primary_user_apps", value) }

    override var superuserSortOption: Int
        get() = prefs.getInt("superuser_sort_option", 0)
        set(value) = prefs.edit { putInt("superuser_sort_option", value) }

    override var suLogFilters: Set<String>?
        // 返回副本，防止调用方修改集合后 SharedPreferences 内部状态不同步。
        get() = prefs.getStringSet("sulog_filters", null)?.toSet()
        set(filters) = prefs.edit { putStringSet("sulog_filters", filters) }

    override var autoJailbreak: Boolean
        get() = prefs.getBoolean("auto_jailbreak", false)
        set(value) {
            // 自动越狱依赖开机广播，因此同步启用/禁用 BootCompletedReceiver。
            runCatching {
                ksuApp.packageManager.setComponentEnabledSetting(
                    ComponentName(ksuApp, BootCompletedReceiver::class.java),
                    if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure {
                Log.e("Settings", "failed to change boot receiver state to $value", it)
            }
            prefs.edit {
                putBoolean("auto_jailbreak", value)
            }
        }

    override var useSoftReboot: Boolean
        get() = prefs.getBoolean(KEY_USE_SOFT_REBOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SOFT_REBOOT, value) }

    override val intentToken: String
        get() {
        // 首次访问时生成 32 字节随机 token，之后复用同一个值。
        val existing = prefs.getString(INTENT_TOKEN_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val token = ByteArray(32).also(secureRandom::nextBytes)
            .joinToString(separator = "") { "%02x".format(it) }
        prefs.edit { putString(INTENT_TOKEN_KEY, token) }
        return token
    }

    /** 查询 su 兼容功能状态；实际逻辑在 ksud feature 子命令中。 */
    override suspend fun getSuCompatStatus(): String = getFeatureStatus("su_compat")

    /** 查询 su 兼容的持久化值；null 表示内核不支持该 feature。 */
    override suspend fun getSuCompatPersistValue(): Long? = getFeaturePersistValue("su_compat")

    /** 直接从内核读取 su 兼容开关。 */
    override fun isSuEnabled(): Boolean = Natives.isSuEnabled()

    /** 直接写入内核的 su 兼容开关。 */
    override fun setSuEnabled(enabled: Boolean): Boolean = Natives.setSuEnabled(enabled)

    /** 保存 su 兼容模式偏好（仅影响 Manager 的展示/命令拼装）。 */
    override fun setSuCompatModePref(mode: Int) = prefs.edit { putInt("su_compat_mode", mode) }

    /** 读取 su 兼容模式偏好。 */
    override fun getSuCompatModePref(): Int = prefs.getInt("su_compat_mode", 0)

    /** 查询内核模块 umount 功能状态。 */
    override suspend fun getKernelUmountStatus(): String = getFeatureStatus("kernel_umount")

    /** 读取内核模块 umount 开关。 */
    override fun isKernelUmountEnabled(): Boolean = Natives.isKernelUmountEnabled()

    /** 设置内核模块 umount 开关。 */
    override fun setKernelUmountEnabled(enabled: Boolean): Boolean = Natives.setKernelUmountEnabled(enabled)

    /** 查询 SELinux 隐藏功能状态。 */
    override suspend fun getSelinuxHideStatus(): String = getFeatureStatus("selinux_hide")

    /** 读取 SELinux 隐藏开关。 */
    override fun isSelinuxHideEnabled(): Boolean = Natives.isSelinuxHideEnabled()

    /** 设置 SELinux 隐藏；返回内核错误码。 */
    override fun setSelinuxHideEnabled(enabled: Boolean): Int = Natives.setSelinuxHideEnabled(enabled)

    /** 查询 su 日志功能状态。 */
    override suspend fun getSulogStatus(): String = getFeatureStatus("sulog")

    /** 查询 su 日志持久化值。 */
    override suspend fun getSulogPersistValue(): Long? = getFeaturePersistValue("sulog")

    /** 查询 su 日志当前值。 */
    override suspend fun getSulogValue(): Long? = getFeatureValue("sulog")

    /** 通过 ksud 设置 su 日志开关。 */
    override fun setSulogEnabled(enabled: Boolean): Boolean = execKsud("feature set sulog ${if (enabled) 1 else 0}", true)

    /** 查询 adb root 功能状态。 */
    override suspend fun getAdbRootStatus(): String = getFeatureStatus("adb_root")

    /** 查询 adb root 持久化值。 */
    override suspend fun getAdbRootPersistValue(): Long? = getFeaturePersistValue("adb_root")

    /** 查询 adb root 当前值。 */
    override suspend fun getAdbRootValue(): Long? = getFeatureValue("adb_root")

    /** 设置 adb root；写入成功后重启 adbd 使其立即生效。 */
    override fun setAdbRootEnabled(enabled: Boolean): Boolean =
        if (execKsud("feature set adb_root ${if (enabled) 1 else 0}", true)) {
            ShellUtils.fastCmd("setprop ctl.restart adbd")
            true
        } else {
            false
        }

    /** 查询“仅 adb 可用 su”功能状态。 */
    override suspend fun getShellSuAdbOnlyStatus(): String = getFeatureStatus("shell_su_adb_only")

    /** 查询“仅 adb 可用 su”持久化值。 */
    override suspend fun getShellSuAdbOnlyPersistValue(): Long? = getFeaturePersistValue("shell_su_adb_only")

    /** 查询“仅 adb 可用 su”当前值。 */
    override suspend fun getShellSuAdbOnlyValue(): Long? = getFeatureValue("shell_su_adb_only")

    /** 设置“仅 adb 可用 su”。 */
    override fun setShellSuAdbOnlyEnabled(enabled: Boolean): Boolean =
        execKsud("feature set shell_su_adb_only ${if (enabled) 1 else 0}", true)

    /** /data/adb/ksu 仅 root 可读，因此通过 root shell 检查标记文件是否存在。 */
    override suspend fun isModuleIntegrityCheckEnabled(): Boolean = withContext(Dispatchers.IO) {
        val shell = getRootShell()
        shell.isRoot && ShellUtils.fastCmdResult(shell, "[ -e $INTEGRITY_CHECK_PATH ]")
    }

    /** 由 ksud 创建或删除标记文件；失败时返回 false。 */
    override fun setModuleIntegrityCheckEnabled(enabled: Boolean): Boolean =
        execKsud("module integrity ${if (enabled) "enable" else "disable"}", true)

    /** 读取非 root 默认 profile 的模块 umount 设置。 */
    override fun isDefaultUmountModules(): Boolean = Natives.isDefaultUmountModules()

    /** 写入非 root 默认 profile 的模块 umount 设置。 */
    override fun setDefaultUmountModules(enabled: Boolean): Boolean = Natives.setDefaultUmountModules(enabled)

    /** 当前是否以 LKM 模式运行。 */
    override fun isLkmMode(): Boolean = Natives.isLkmMode

    /** 让 ksud 持久化当前 feature 值，重启后仍生效。 */
    override fun execKsudFeatureSave() {
        execKsud("feature save", true)
    }
}
