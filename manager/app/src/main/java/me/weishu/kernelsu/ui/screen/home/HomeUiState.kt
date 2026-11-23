package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.KernelVersion
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo

/**
 * 首页一次性聚合的状态快照。
 * 内核版本、KernelSU 版本与 UAPI 版本都来自内核查询，用不可变数据类承载，
 * 既避免并发修改，也让 Compose 能准确判断哪些界面需要重组。
 */
@Immutable
data class HomeUiState(
    // 内核版本，用于判断是否 GKI 以及当前内核能力。
    val kernelVersion: KernelVersion,
    // 内核侧 KernelSU 版本号；为 null 表示内核未集成或尚未激活。
    val ksuVersion: Int?,
    // 管理器与内核各自支持的 UAPI 版本，二者不匹配时需要提示升级。
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    // 工作模式：null 表示未知/未激活，true 为 LKM，false 为 GKI。
    val lkmMode: Boolean?,
    // LKM 是否由管理器内置；只有内置镜像才允许管理器直接更新。
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
) {
    // SELinux 为 Permissive 时才允许走越狱路径，因此直接暴露该判断结果。
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    // 内核以 GKI（非 LKM）模式运行且已激活时给出弃用提示。
    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    // 仅当内置 LKM 版本与管理器不一致且双方 UAPI 兼容时才提示更新，
    // 否则应先升级另一侧，避免升级后无法通信。
    val showLkmUpdate: Boolean
        get() = isManager &&
                lkmMode == true &&
                isLkmBundled &&
                ksuVersion?.toLong() != currentManagerVersionCode &&
                !requiresNewKernel &&
                !requiresNewManager

    // Jailbreak mode runs on locked bootloaders, so flashing a boot image would brick the device.
    val canInstallKernelUpdate: Boolean
        get() = lkmMode == true && !isLateLoadMode

    // 非内置 LKM 由用户自行加载，需要标记出来，避免被误认为可随管理器更新。
    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    // 内核已激活但拿不到 root，通常是授权或 SELinux 策略被拦截。
    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    // 管理器本身是 PR 构建时，优先提示管理器侧风险。
    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    // 仅在管理器不是 PR 构建时才提示内核 PR 构建，避免重复告警。
    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    // 只比较数值版本号，避免版本名字符串格式差异导致误判。
    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

/**
 * 首页可执行动作集合。
 * 用数据类而不是直接传 ViewModel，便于两套 UI 复用同一行为，也方便预览。
 */
@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    // 默认空实现：非越狱场景或预览中不提供该入口。
    val onJailbreakClick: () -> Unit = {},
)
