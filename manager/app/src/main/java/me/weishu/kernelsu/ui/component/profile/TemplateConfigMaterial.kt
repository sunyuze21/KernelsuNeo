package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReadMore
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.profile.dialogs.SingleSelectDialog
import me.weishu.kernelsu.ui.util.listAppProfileTemplates
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById

// 模板下拉选项：id 用于持久化/查规则，name 用于展示
private data class TemplateOption(
    val id: String,
    val name: String
)

/**
 * Material 版模板配置：选择 root 模板或跳转模板管理/查看。
 * 选中模板时会先写入 sepolicy，成功后才把 template 的 uid/gid/context 等
 * 合并进 profile，避免配置与内核策略不一致。
 */
@Composable
fun TemplateConfigMaterial(
    profile: Natives.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }
    // rootTemplate 为空表示尚未选择模板
    val template = profile.rootTemplate ?: ""
    val profileTemplates = listAppProfileTemplates()
    val noTemplates = profileTemplates.isEmpty()

    val templateOptions = remember(profileTemplates) {
        profileTemplates.map { tid ->
            TemplateOption(tid, tid)
        }
    }

    // 优先定位已选模板；若已失效则回退到第一个，保证对话框有默认选中项
    val selectedTemplate = remember(template, templateOptions) {
        templateOptions.find { it.id == template } ?: templateOptions.firstOrNull()
    }

    if (showDialog.value && !noTemplates) {
        SingleSelectDialog(
            title = stringResource(R.string.profile_template),
            items = templateOptions,
            selectedItem = selectedTemplate ?: templateOptions.first(),
            itemTitle = { it.name },
            onConfirm = { selected ->
                val tid = selected.id
                val templateInfo = getTemplateInfoById(tid)
                // 只有 sepolicy 写入成功才更新 profile，防止半套配置生效
                if (templateInfo != null && setSepolicy(tid, templateInfo.rules.joinToString("\n"))) {
                    onProfileChange(
                        profile.copy(
                            rootTemplate = tid,
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
                showDialog.value = false
            },
            onDismiss = { showDialog.value = false }
        )
    }

    val selectedTemplateName = template.ifEmpty { "None" }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = buildList {
            add {
                SegmentedListItem(
                    headlineContent = { Text(stringResource(R.string.profile_template)) },
                    supportingContent = { Text(selectedTemplateName) },
                    trailingContent = {
                        // 无模板时展示“创建”入口，有模板时点击行即可选择
                        if (noTemplates) {
                            IconButton(onClick = onManageTemplate) {
                                Icon(Icons.Filled.Create, contentDescription = null)
                            }
                        }
                    },
                    onClick = {
                        if (!noTemplates) {
                            showDialog.value = true
                        }
                    }
                )
            }
            // 仅已选模板时提供“查看模板”入口
            if (template.isNotEmpty()) add {
                SegmentedListItem(
                    headlineContent = { Text(stringResource(R.string.app_profile_template_view)) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.ReadMore, contentDescription = null)
                    },
                    onClick = { onViewTemplate(template) }
                )
            }
        }
    )
}
