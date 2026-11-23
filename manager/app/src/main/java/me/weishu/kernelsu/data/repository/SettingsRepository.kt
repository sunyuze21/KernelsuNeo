package me.weishu.kernelsu.data.repository

/**
 * Manager 的本地设置与内核 feature 开关。
 * 属性读写 SharedPreferences；suspend 方法查询 ksud/内核状态，避免在主线程阻塞。
 */
interface SettingsRepository {
    /** 界面模式（浅色/深色/跟随系统）的存储值。 */
    var uiMode: String
    /** 是否检查 Manager 自身更新。 */
    var checkUpdate: Boolean
    /** 是否检查已安装模块更新。 */
    var checkModuleUpdate: Boolean
    /** 主题模式索引。 */
    var themeMode: Int
    /** 是否使用 Miuix 风格 Monet 取色。 */
    var miuixMonet: Boolean
    /** Monet 主色种子；0 表示未指定。 */
    var keyColor: Int
    /** MaterialKolor 的配色风格名称。 */
    var colorStyle: String
    /** MaterialKolor 的规范版本名称。 */
    var colorSpec: String
    /** 是否启用预测性返回动画（Android 14+）。 */
    var enablePredictiveBack: Boolean
    /** 是否允许滑动关闭页面。 */
    var enableSwipeDismiss: Boolean
    /** 左右翻页手势拦截模式（0~2）。 */
    var pagerInterceptionMode: Int
    /** 是否启用界面模糊效果。 */
    var enableBlur: Boolean
    /** 是否使用悬浮底栏。 */
    var enableFloatingBottomBar: Boolean
    /** 悬浮底栏是否启用模糊。 */
    var enableFloatingBottomBarBlur: Boolean
    /** 是否在导航项上显示角标。 */
    var enableNavigationBadge: Boolean
    /** 侧边导航栏是否展开。 */
    var navigationRailExpanded: Boolean
    /** 页面缩放比例。 */
    var pageScale: Float
    /** 模块描述最多显示的行数。 */
    var moduleDescriptionMaxLines: Int
    /** 是否开启 WebView 调试。 */
    var enableWebDebugging: Boolean
    /** 模块列表是否把已启用模块排在前面。 */
    var moduleSortEnabledFirst: Boolean
    /** 模块列表是否把带 action 的模块排在前面。 */
    var moduleSortActionFirst: Boolean
    /** Name of the [me.weishu.kernelsu.ui.screen.modulerepo.RepoSort] entry. */
    var moduleRepoSortOrder: String
    /** SuperUser 列表是否显示系统应用。 */
    var superuserShowSystemApps: Boolean
    /** SuperUser 列表是否只看主用户应用。 */
    var superuserShowOnlyPrimaryUserApps: Boolean
    /** SuperUser 列表排序方式索引。 */
    var superuserSortOption: Int
    /** su 日志过滤条件；null 表示不过滤。 */
    var suLogFilters: Set<String>?
    /** 是否启用自动“越狱”（随开机启动 late-load 服务）。 */
    var autoJailbreak: Boolean
    /** 重启时是否优先使用软重启。 */
    var useSoftReboot: Boolean
    /** 供外部 Intent 校验的随机 token，首次访问时生成并持久化。 */
    val intentToken: String

    /** 查询 su 兼容功能的内核状态字符串。 */
    suspend fun getSuCompatStatus(): String
    /** 查询 su 兼容功能持久化的值（内核返回 null 表示不支持）。 */
    suspend fun getSuCompatPersistValue(): Long?
    /** 读取 su 兼容开关的当前值。 */
    fun isSuEnabled(): Boolean
    /** 写入 su 兼容开关，返回内核是否接受。 */
    fun setSuEnabled(enabled: Boolean): Boolean
    /** 保存 su 兼容模式偏好（仅本地）。 */
    fun setSuCompatModePref(mode: Int)
    /** 读取 su 兼容模式偏好。 */
    fun getSuCompatModePref(): Int

    /** 查询模块 umount 功能状态。 */
    suspend fun getKernelUmountStatus(): String
    /** 读取内核模块 umount 开关。 */
    fun isKernelUmountEnabled(): Boolean
    /** 设置内核模块 umount 开关。 */
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    /** 查询 SELinux 隐藏功能状态。 */
    suspend fun getSelinuxHideStatus(): String
    /** 读取 SELinux 隐藏开关。 */
    fun isSelinuxHideEnabled(): Boolean
    /** 设置 SELinux 隐藏开关；返回内核错误码（0 表示成功）。 */
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    /** 查询 su 日志功能状态。 */
    suspend fun getSulogStatus(): String
    /** 查询 su 日志持久化值。 */
    suspend fun getSulogPersistValue(): Long?
    /** 查询 su 日志当前值。 */
    suspend fun getSulogValue(): Long?
    /** 通过 ksud 设置 su 日志开关。 */
    fun setSulogEnabled(enabled: Boolean): Boolean

    /** 查询 adb root 功能状态。 */
    suspend fun getAdbRootStatus(): String
    /** 查询 adb root 持久化值。 */
    suspend fun getAdbRootPersistValue(): Long?
    /** 查询 adb root 当前值。 */
    suspend fun getAdbRootValue(): Long?
    /** 设置 adb root，并在成功后重启 adbd。 */
    fun setAdbRootEnabled(enabled: Boolean): Boolean
    /** 查询“仅 adb 可用 su”功能状态。 */
    suspend fun getShellSuAdbOnlyStatus(): String
    /** 查询“仅 adb 可用 su”持久化值。 */
    suspend fun getShellSuAdbOnlyPersistValue(): Long?
    /** 查询“仅 adb 可用 su”当前值。 */
    suspend fun getShellSuAdbOnlyValue(): Long?
    /** 设置“仅 adb 可用 su”。 */
    fun setShellSuAdbOnlyEnabled(enabled: Boolean): Boolean

    /** 查询模块签名验证是否开启；标记文件存在即视为开启。 */
    suspend fun isModuleIntegrityCheckEnabled(): Boolean
    /** 通过 ksud 创建/删除标记文件，开关模块签名验证。 */
    fun setModuleIntegrityCheckEnabled(enabled: Boolean): Boolean

    /** 读取非 root 默认 profile 的模块 umount 设置。 */
    fun isDefaultUmountModules(): Boolean
    /** 写入非 root 默认 profile 的模块 umount 设置。 */
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    /** 当前是否为 LKM 模式。 */
    fun isLkmMode(): Boolean

    /** 让 ksud 持久化 feature 开关（`feature save`）。 */
    fun execKsudFeatureSave()
}
