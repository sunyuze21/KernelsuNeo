package me.weishu.kernelsu.ui.screen.appprofile

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.screen.superuser.GroupedApps

/**
 * 应用 profile 页的状态。
 * uid 与 packageName 共同定位一条策略；同一 uid 可能对应多个包（共享 uid），
 * 因此还要携带 appGroup 以展示受影响的全部应用。
 */
@Immutable
data class AppProfileUiState(
    val uid: Int,
    val packageName: String,
    // Natives.Profile 是内核侧策略的镜像，包含 allowSu、模板与自定义规则。
    val profile: Natives.Profile,
    val appGroup: GroupedApps,
    // 共享 uid 时用于提示用户这些应用会共用同一份策略。
    val sharedUserId: String,
) {
    // 一个 uid 下挂多个包时，修改会同时影响整组应用。
    val isUidGroup get() = appGroup.apps.size > 1
}

/**
 * 应用 profile 页动作集合。
 * 启动/停止/重启都同时需要包名和用户 id，因为多用户下同一包名可能对应不同实例。
 */
@Immutable
data class AppProfileActions(
    val onBack: () -> Unit,
    val onLaunchApp: (String, Int) -> Unit,
    val onForceStopApp: (String, Int) -> Unit,
    val onRestartApp: (String, Int) -> Unit,
    val onViewTemplate: (String) -> Unit,
    val onManageTemplate: () -> Unit,
    val onProfileChange: (Natives.Profile) -> Unit,
)
