package me.weishu.kernelsu.ui.component.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode

/**
 * 应用 profile 配置入口，按当前 UI 模式分派实现。
 * [fixedName] 为 true 时不显示名称输入框（如模板/默认配置）；
 * [enabled] 为 false 时仅展示内核默认行为，不可编辑。
 */
@Composable
fun AppProfileConfig(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AppProfileConfigMiuix(
            modifier = modifier,
            fixedName = fixedName,
            enabled = enabled,
            profile = profile,
            onProfileChange = onProfileChange
        )

        UiMode.Material -> AppProfileConfigMaterial(
            modifier = modifier,
            fixedName = fixedName,
            enabled = enabled,
            profile = profile,
            onProfileChange = onProfileChange
        )
    }
}

/**
 * Root 身份 profile 配置入口。与 App 版的主要区别是默认启用且不提供名称编辑，
 * 用于配置全局 root 授权策略。注意 Material 分支不透传 fixedName。
 */
@Composable
fun RootProfileConfig(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean = true,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> RootProfileConfigMiuix(
            modifier = modifier,
            fixedName = fixedName,
            enabled = enabled,
            profile = profile,
            onProfileChange = onProfileChange
        )

        UiMode.Material -> RootProfileConfigMaterial(
            modifier = modifier,
            enabled = enabled,
            profile = profile,
            onProfileChange = onProfileChange
        )
    }
}

/**
 * 模板配置入口，按 UI 模式分派。[onViewTemplate] 打开模板详情，
 * [onManageTemplate] 进入模板管理页，二者都依赖外部导航。
 */
@Composable
fun TemplateConfig(
    modifier: Modifier = Modifier,
    profile: Natives.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> TemplateConfigMiuix(
            modifier = modifier,
            profile = profile,
            onViewTemplate = onViewTemplate,
            onManageTemplate = onManageTemplate,
            onProfileChange = onProfileChange
        )

        UiMode.Material -> TemplateConfigMaterial(
            profile = profile,
            onViewTemplate = onViewTemplate,
            onManageTemplate = onManageTemplate,
            onProfileChange = onProfileChange
        )
    }
}
