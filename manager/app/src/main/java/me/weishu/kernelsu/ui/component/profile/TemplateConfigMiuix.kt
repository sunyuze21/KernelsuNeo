package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.listAppProfileTemplates
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * @author weishu
 * @date 2023/10/21.
 */
@Composable
fun TemplateConfigMiuix(
    modifier: Modifier = Modifier,
    profile: Natives.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit
) {
    val profileTemplates = listAppProfileTemplates()
    val noTemplates = profileTemplates.isEmpty()

    if (noTemplates) {
        // 无模板时只展示创建入口，避免下拉到空列表
        ArrowPreference(
            modifier = modifier,
            title = stringResource(R.string.app_profile_template_create),
            startAction = {
                Icon(
                    Icons.Rounded.Create,
                    null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MiuixTheme.colorScheme.onBackground
                )
            },
            onClick = onManageTemplate,
        )
    } else {
        // 未选择时回退到首个模板，但不会自动写回 profile
        val template = profile.rootTemplate ?: profileTemplates[0]

        Column(modifier = modifier) {
            OverlayDropdownPreference(
                title = stringResource(R.string.profile_template),
                items = profileTemplates,
                // 找不到已选模板时索引回 0，防止下拉显示空白
                selectedIndex = profileTemplates.indexOf(template).takeIf { it >= 0 } ?: 0,
                onSelectedIndexChange = { index ->
                    if (index < 0 || index >= profileTemplates.size) return@OverlayDropdownPreference
                    val selected = profileTemplates[index]
                    val templateInfo = getTemplateInfoById(selected)
                    // 先写 sepolicy，成功后再合并模板属性，避免内核与配置不同步
                    if (templateInfo != null && setSepolicy(selected, templateInfo.rules.joinToString("\n"))) {
                        onProfileChange(
                            profile.copy(
                                rootTemplate = selected,
                                rootUseDefault = false,
                                uid = templateInfo.uid,
                                gid = templateInfo.gid,
                                groups = templateInfo.groups,
                                capabilities = templateInfo.capabilities,
                                context = templateInfo.context,
                                namespace = templateInfo.namespace,
                            )
                        )
                    }
                },
                maxHeight = 280.dp
            )
            ArrowPreference(
                title = stringResource(R.string.app_profile_template_view),
                onClick = { onViewTemplate(template) }
            )
        }
    }
}