package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.profile.Capabilities
import me.weishu.kernelsu.profile.Groups
import me.weishu.kernelsu.toRawFlags
import me.weishu.kernelsu.toRootProfileFlags
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField
import me.weishu.kernelsu.ui.component.profile.dialogs.MultiSelectDialog
import me.weishu.kernelsu.ui.component.profile.dialogs.SingleSelectDialog
import me.weishu.kernelsu.ui.util.isSepolicyValid

/**
 * Material 版 root profile 全量配置：UID/GID、附加组、能力、命名空间、
 * root flag 与 SELinux 上下文/规则。所有这些字段都属于 root 授权策略，
 * 任一字段被修改都会清除 [Natives.Profile.rootUseDefault]，
 * 表示不再跟随内核默认 profile。
 */
@Composable
fun RootProfileConfigMaterial(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit
) {
    Column(
        modifier = modifier.padding(bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        UidGidPanel(
            enabled = enabled,
            uid = profile.uid,
            gid = profile.gid,
            // 手动改 UID/GID 即脱离默认 profile
            onUidChange = { onProfileChange(profile.copy(uid = it, rootUseDefault = false)) },
            onGidChange = { onProfileChange(profile.copy(gid = it, rootUseDefault = false)) }
        )

        GroupsPanel(
            enabled = enabled,
            // 只保留仍存在于枚举中的 gid，避免旧配置带入未知值
            selected = profile.groups.mapNotNull { gid ->
                Groups.entries.find { it.gid == gid }
            },
            onSelectionChange = { selection ->
                onProfileChange(
                    profile.copy(
                        groups = selection.map { it.gid },
                        rootUseDefault = false
                    )
                )
            }
        )

        CapsPanel(
            enabled = enabled,
            selected = profile.capabilities,
            onSelectionChange = { selection ->
                onProfileChange(
                    profile.copy(
                        capabilities = selection.map { it.cap },
                        rootUseDefault = false
                    )
                )
            }
        )

        MountNameSpacePanel(
            enabled = enabled,
            namespace = profile.namespace,
            onNamespaceChange = { onProfileChange(profile.copy(namespace = it, rootUseDefault = false)) }
        )

        RootProfileFlagPanel(
            enabled = enabled,
            selected = profile.flags.toRootProfileFlags(),
            onSelectionChange = {
                onProfileChange(
                    profile.copy(
                        flags = it.toRawFlags(),
                    )
                )
            }
        )

        SELinuxPanel(
            enabled = enabled,
            context = profile.context,
            rules = profile.rules,
            onContextChange = { domain ->
                onProfileChange(profile.copy(context = domain, rootUseDefault = false))
            },
            onRulesChange = { rules ->
                onProfileChange(profile.copy(rules = rules, rootUseDefault = false))
            }
        )
    }
}

/**
 * UID/GID 输入面板。非数字输入回退为 0，避免 toInt 抛异常中断输入。
 */
@Composable
private fun UidGidPanel(
    enabled: Boolean,
    uid: Int,
    gid: Int,
    onUidChange: (Int) -> Unit,
    onGidChange: (Int) -> Unit
) {
    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf(
            {
                SegmentedTextField(
                    enabled = enabled,
                    value = uid.toString(),
                    // 输入过程可能为空/非法，统一回退 0
                    onValueChange = { onUidChange(it.toIntOrNull() ?: 0) },
                    label = "UID",
                    singleLine = true
                )
            },
            {
                SegmentedTextField(
                    enabled = enabled,
                    value = gid.toString(),
                    onValueChange = { onGidChange(it.toIntOrNull() ?: 0) },
                    label = "GID",
                    singleLine = true
                )
            }
        )
    )
}

/**
 * 附加组多选面板。把内部排序集中在 remember 中，
 * 让 ROOT/SYSTEM/SHELL 优先显示，其余按名称排序，便于查找。
 * 内核限制最多 32 个附加组，因此 maxSelection 固定为 32。
 */
@Composable
private fun GroupsPanel(
    enabled: Boolean,
    selected: List<Groups>,
    onSelectionChange: (Set<Groups>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    val groups = remember {
        Groups.entries.sortedWith(
            compareBy<Groups> {
                when (it) {
                    Groups.ROOT -> 0
                    Groups.SYSTEM -> 1
                    Groups.SHELL -> 2
                    else -> Int.MAX_VALUE
                }
            }
                .then(compareBy { it.name })
        )
    }

    if (showDialog.value) {
        MultiSelectDialog(
            title = "Groups",
            subtitle = "${selected.size} / 32",
            items = groups,
            selectedItems = selected.toSet(),
            itemTitle = { it.display },
            itemSubtitle = { it.desc },
            // 与内核每组上限保持一致
            maxSelection = 32,
            onSelectionChange = {
                onSelectionChange(it)
            },
            onDismiss = { showDialog.value = false }
        )
    }

    // 摘要只展示已选项，空时显示 None
    val tag = if (selected.isEmpty()) {
        "None"
    } else {
        selected.joinToString(", ") { it.display }
    }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.profile_groups)) },
                supportingContent = { Text(tag) },
                // 禁用时不提供点击，避免打开只读对话框
                onClick = if (enabled) {
                    { showDialog.value = true }
                } else null
            )
        }
    )
}

/**
 * 挂载命名空间选择面板。取值需与内核约定一致：0 继承 / 1 全局 / 2 独立。
 */
@Composable
private fun MountNameSpacePanel(
    enabled: Boolean,
    namespace: Int,
    onNamespaceChange: (Int) -> Unit
) {
    // 仅在本地使用的选项模型
    data class NamespaceOption(
        val value: Int,
        val label: String
    )

    val showDialog = remember { mutableStateOf(false) }

    // 先取字符串资源再构造选项，保证语言切换后能重建
    val inheritedLabel = stringResource(R.string.profile_namespace_inherited)
    val globalLabel = stringResource(R.string.profile_namespace_global)
    val individualLabel = stringResource(R.string.profile_namespace_individual)

    val options = remember(inheritedLabel, globalLabel, individualLabel) {
        listOf(
            NamespaceOption(0, inheritedLabel),
            NamespaceOption(1, globalLabel),
            NamespaceOption(2, individualLabel)
        )
    }

    // 未知取值回退到“继承”，避免摘要空白
    val selectedOption = options.find { it.value == namespace } ?: options[0]

    if (showDialog.value) {
        SingleSelectDialog(
            title = stringResource(R.string.profile_namespace),
            items = options,
            selectedItem = selectedOption,
            itemTitle = { it.label },
            onConfirm = {
                onNamespaceChange(it.value)
                showDialog.value = false
            },
            onDismiss = { showDialog.value = false }
        )
    }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.profile_namespace)) },
                supportingContent = { Text(selectedOption.label) },
                onClick = if (enabled) {
                    { showDialog.value = true }
                } else null
            )
        }
    )
}

/**
 * root flag 多选面板。flag 通过 ordinal 与内核枚举对应，
 * 因此这里按 ordinal 重新匹配，避免直接依赖对象相等性。
 */
@Composable
private fun RootProfileFlagPanel(
    enabled: Boolean,
    selected: List<Natives.Profile.RootProfileFlag>,
    onSelectionChange: (flags: List<Natives.Profile.RootProfileFlag>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    // 用 ordinal 映射回当前枚举实例，旧版本数据也能安全展示
    val selectedFlags = remember(selected) {
        selected.mapNotNull { flag ->
            Natives.Profile.RootProfileFlag.entries.find { it.ordinal == flag.ordinal }
        }
    }

    val flags = remember {
        Natives.Profile.RootProfileFlag.entries.sortedBy { it.display }
    }

    if (showDialog.value) {
        MultiSelectDialog(
            title = stringResource(R.string.profile_flags),
            subtitle = "${selectedFlags.size} / ${Natives.Profile.RootProfileFlag.entries.size}",
            items = flags,
            selectedItems = selectedFlags.toSet(),
            itemTitle = { it.display },
            itemSubtitle = { null },
            // flag 数量有限，无需限制上限
            maxSelection = Int.MAX_VALUE,
            onSelectionChange = {
                onSelectionChange(it.toList())
            },
            onDismiss = { showDialog.value = false }
        )
    }

    val tag = if (selectedFlags.isEmpty()) {
        "None"
    } else {
        selectedFlags.joinToString(", ") { it.display }
    }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.profile_flags)) },
                supportingContent = { Text(tag) },
                onClick = if (enabled) {
                    { showDialog.value = true }
                } else null
            )
        }
    )
}

/**
 * Linux capabilities 多选面板。profile 中保存的是 cap 数值，展示时才映射回枚举。
 */
@Composable
private fun CapsPanel(
    enabled: Boolean,
    selected: List<Int>,
    onSelectionChange: (Set<Capabilities>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    // 过滤掉当前枚举不认识的 cap，防止旧数据导致异常
    val selectedCaps = remember(selected) {
        selected.mapNotNull { cap ->
            Capabilities.entries.find { it.cap == cap }
        }
    }

    val capabilities = remember {
        Capabilities.entries.sortedBy { it.display }
    }

    if (showDialog.value) {
        MultiSelectDialog(
            title = "Capabilities",
            subtitle = "${selectedCaps.size} / ${Capabilities.entries.size}",
            items = capabilities,
            selectedItems = selectedCaps.toSet(),
            itemTitle = { it.display },
            itemSubtitle = { null },
            maxSelection = Int.MAX_VALUE,
            onSelectionChange = {
                onSelectionChange(it)
            },
            onDismiss = { showDialog.value = false }
        )
    }

    val tag = if (selectedCaps.isEmpty()) {
        "None"
    } else {
        selectedCaps.joinToString(", ") { it.display }
    }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.profile_capabilities)) },
                supportingContent = { Text(tag) },
                onClick = if (enabled) {
                    { showDialog.value = true }
                } else null
            )
        }
    )
}

/**
 * SELinux 配置入口面板，摘要展示域；规则编辑交给 [SELinuxDialog]。
 * context 为空时用破折号占位，提示尚未设置。
 */
@Composable
private fun SELinuxPanel(
    enabled: Boolean,
    context: String,
    rules: String,
    onContextChange: (String) -> Unit,
    onRulesChange: (String) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    if (showDialog.value) {
        SELinuxDialog(
            domain = context,
            rules = rules,
            onConfirm = { domain, r ->
                // 两个字段都需写回，规则与域是一组配置
                onContextChange(domain)
                onRulesChange(r)
                showDialog.value = false
            },
            onDismiss = { showDialog.value = false }
        )
    }

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = listOf {
            SegmentedListItem(
                headlineContent = { Text(stringResource(R.string.profile_selinux_context)) },
                supportingContent = { Text(context.ifEmpty { "—" }) },
                onClick = if (enabled) {
                    { showDialog.value = true }
                } else null
            )
        }
    )
}

/**
 * SELinux 域与规则编辑对话框。域名需符合 `user:role:type[:level]` 形式，
 * 规则用 [isSepolicyValid] 校验；任一不合法都禁用确认，避免写入内核后被拒绝。
 * 编辑内容暂存在本地，确认时才通过 [onConfirm] 上抛。
 */
@Composable
private fun SELinuxDialog(
    domain: String,
    rules: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // 本地草稿，取消即丢弃
    var currentDomain by remember { mutableStateOf(domain) }
    var currentRules by remember { mutableStateOf(rules) }

    val isDomainValid = remember(currentDomain) {
        // 仅允许小写字母/下划线组成的四段式 context
        val regex = Regex("^[a-z_]+:[a-z0-9_]+:[a-z0-9_]+(:[a-z0-9_]+)?$")
        currentDomain.matches(regex)
    }
    val isRulesValid = remember(currentRules) { isSepolicyValid(currentRules) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_selinux_context)) },
        text = {
            Column {
                OutlinedTextField(
                    value = currentDomain,
                    onValueChange = { currentDomain = it },
                    label = { Text(stringResource(R.string.profile_selinux_domain)) },
                    // 即时以错误色提示格式问题
                    isError = !isDomainValid,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentRules,
                    onValueChange = { currentRules = it },
                    label = { Text(stringResource(R.string.profile_selinux_rules)) },
                    isError = !isRulesValid,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii
                    ),
                    // 规则通常多行，限制行数避免对话框过高
                    minLines = 3,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentDomain, currentRules) },
                enabled = isDomainValid && isRulesValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
