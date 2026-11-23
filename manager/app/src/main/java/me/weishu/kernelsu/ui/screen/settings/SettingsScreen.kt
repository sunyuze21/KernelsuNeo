package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.viewmodel.SettingsViewModel

/**
 * 设置页的容器：读取持久化设置并把用户操作转发给 ViewModel。
 * 真正的设置在 ViewModel 中落盘，这里只负责导航和 UI 模式分发，避免两套 UI 各自处理持久化。
 */
@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    // 设置状态由 ViewModel 持有，重组或旋转屏幕后仍然有效。
    val viewModel = viewModel<SettingsViewModel>()
    // lifecycle-aware 收集可避免页面不可见时继续触发重组。
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 生命周期回调中需要读取最新的页签状态。
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    // 标记首次 onResume 已处理，避免与 LaunchedEffect 重复刷新。
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    // 每次成为当前页都刷新，确保从其他页面修改的设置能及时反映。
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            viewModel.refresh()
        }
    }

    // 从后台返回且仍是当前页时刷新，防止设置被外部进程修改后显示旧值。
    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    // 集中把 UI 事件映射到 ViewModel 的 setter，两套 UI 不直接持有 ViewModel。
    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        // 下拉索引 0 固定为 Miuix、1 为 Material，需与两套设置页的条目顺序保持一致。
        onSetUiModeIndex = { index ->
            viewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenProfileTemplate = { navigator.push(Route.AppProfileTemplate) },
        onSetSuCompatMode = viewModel::setSuCompatMode,
        onSetKernelUmountEnabled = viewModel::setKernelUmountEnabled,
        onSetSelinuxHideEnabled = viewModel::setSelinuxHideEnabled,
        onSetSulogEnabled = viewModel::setSulogEnabled,
        onSetAdbRootEnabled = viewModel::setAdbRootEnabled,
        onSetShellSuAdbOnlyEnabled = viewModel::setShellSuAdbOnlyEnabled,
        onSetModuleIntegrityCheckEnabled = viewModel::setModuleIntegrityCheckEnabled,
        onSetDefaultUmountModules = viewModel::setDefaultUmountModules,
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onSetAutoJailbreak = viewModel::setAutoJailbreak,
        onSetUseSoftReboot = viewModel::setUseSoftReboot,
        onOpenAbout = { navigator.push(Route.About) },
    )

    // 根据用户选择的 UI 模式渲染不同设计语言，两者共享同一份状态与动作。
    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, actions, bottomInnerPadding)
        UiMode.Material -> SettingPagerMaterial(uiState, actions, bottomInnerPadding)
    }
}
