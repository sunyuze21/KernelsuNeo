package me.weishu.kernelsu.ui.screen.appprofile

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.forceStopApp
import me.weishu.kernelsu.ui.util.getSepolicy
import me.weishu.kernelsu.ui.util.launchApp
import me.weishu.kernelsu.ui.util.restartApp
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById

/**
 * 单个应用（或共享 uid 的应用组）的 profile 编辑入口。
 * 打开时从内核读取当前策略，特殊应用强制禁止 root；保存前会先写 SELinux 规则，
 * 任一步失败都提示并保留原策略，避免内存状态与内核实际策略不一致。
 */
@Composable
fun AppProfileScreen(uid: Int) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    // 优先从已加载的应用列表取，列表尚未就绪时用 getGroupedApp 兜底构造。
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    // 应用可能已被卸载或列表尚未加载，直接退出，避免展示空页面。
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    // profileKey 是内核识别策略的键，特殊应用与普通包名可能不同。
    val packageName = primaryAppInfo.profileKey
    // 共享 uid 时任意一个成员声明的 sharedUserId 都可用于提示。
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    // 读取内核保存的策略；特殊应用不允许 root，避免越权。
    // 仅在允许 root 时加载 SELinux 规则，普通应用没有必要读取策略。
    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Natives.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }.also {
            if (it.allowSu && !primaryAppInfo.special) {
                it.rules = getSepolicy(packageName)
            }
        }
    }
    // 用 uid+包名作为 key，切换应用时重置草稿，避免把上一个应用的修改带过来。
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }

    // 提前解析文案，避免在协程中访问 Composable 的 stringResource。
    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    /** 按当前 UI 模式提示错误：Material 用 Snackbar，Miuix 没有统一宿主，退回 Toast。 */
    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
    )

    val actions = AppProfileActions(
        // dropUnlessResumed 防止页面已不可见时重复出栈。
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onViewTemplate = { templateId ->
            // 只读查看模板，第二个参数 true 表示从应用 profile 进入。
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onProfileChange = { updatedProfile ->
            scope.launch {
                // 特殊应用即使 UI 被绕过也必须强制关闭 root。
                val profileToSave = if (primaryAppInfo.special) {
                    updatedProfile.copy(allowSu = false)
                } else {
                    updatedProfile
                }
                if (profileToSave.allowSu) {
                    // uid < 2000 属于系统/特权进程，除 1000(system) 外禁止授予 root。
                    if (uid < 2000 && uid != 1000) {
                        showMessage(suNotAllowed)
                        return@launch
                    }
                    // 自定义策略需要先写 SELinux 规则；失败时不能落盘 profile。
                    if (!profileToSave.rootUseDefault
                        && profileToSave.rules.isNotEmpty()
                        && !primaryAppInfo.special
                        && !setSepolicy(profileToSave.name, profileToSave.rules)
                    ) {
                        showMessage(failToUpdateSepolicy)
                        return@launch
                    }
                }
                // setAppProfile 是内核写入，失败时保留旧值并提示。
                if (!Natives.setAppProfile(profileToSave)) {
                    showMessage(failToUpdateAppProfile)
                } else {
                    profile = profileToSave
                    // Material 列表复用同一份 ViewModel，需要重新加载以刷新显示。
                    if (uiMode == UiMode.Material) {
                        viewModel.loadAppList()
                    }
                }
            }
        },
    )

    // 根据用户选择的 UI 模式渲染不同设计语言，状态与动作保持一致。
    when (uiMode) {
        UiMode.Miuix -> AppProfileScreenMiuix(
            state = state,
            actions = actions,
        )

        UiMode.Material -> AppProfileScreenMaterial(
            state = state,
            actions = actions,
            snackBarHost = snackbarHost,
        )
    }
}
