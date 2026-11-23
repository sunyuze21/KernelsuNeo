package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.profile.Capabilities
import me.weishu.kernelsu.profile.Groups
import me.weishu.kernelsu.toRawFlags
import me.weishu.kernelsu.toRootProfileFlags
import me.weishu.kernelsu.ui.component.miuix.SuperEditArrow
import me.weishu.kernelsu.ui.util.isSepolicyValid
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Miuix 版 root profile 全量配置。与 Material 版字段一致，
 * 但选择类字段用 OverlayDialog 内联呈现，因此各 Panel 自己维护草稿选择，
 * 确认时才回调，取消则丢弃。除 flags 外，任何字段修改都会清除 rootUseDefault。
 */
@Composable
fun RootProfileConfigMiuix(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean = true,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(
        modifier = modifier
    ) {
        if (!fixedName) {
            TextField(
                enabled = enabled,
                label = stringResource(R.string.profile_name),
                value = profile.name,
                onValueChange = { onProfileChange(profile.copy(name = it)) }
            )
        }

        SuperEditArrow(
            enabled = enabled,
            title = "UID",
            defaultValue = profile.uid,
        ) {
            // 修改 UID 即自定义 profile，不再跟随默认
            onProfileChange(
                profile.copy(
                    uid = it,
                    rootUseDefault = false
                )
            )

        }

        SuperEditArrow(
            enabled = enabled,
            title = "GID",
            defaultValue = profile.gid,
        ) {
            onProfileChange(
                profile.copy(
                    gid = it,
                    rootUseDefault = false
                )
            )

        }

        // 过滤未知 gid，避免旧配置带入当前枚举不存在的组
        val selectedGroups = profile.groups.let { e ->
            e.mapNotNull { g ->
                Groups.entries.find { it.gid == g }
            }
        }

        GroupsPanel(enabled, selectedGroups) {
            onProfileChange(
                profile.copy(
                    groups = it.map { group -> group.gid },
                    rootUseDefault = false
                )
            )
        }

        // capabilities 在 profile 中存数值，展示前映射回枚举
        val selectedCaps = profile.capabilities.mapNotNull { e ->
            Capabilities.entries.find { it.cap == e }
        }

        CapsPanel(enabled, selectedCaps) {
            onProfileChange(
                profile.copy(
                    capabilities = it.map { cap -> cap.cap },
                    rootUseDefault = false
                )
            )
        }

        MountNameSpacePanel(enabled = enabled, profile = profile) {
            onProfileChange(
                profile.copy(
                    namespace = it,
                    rootUseDefault = false
                )
            )
        }

        RootProfileFlagPanel(enabled = enabled, selected = profile.flags.toRootProfileFlags()) {
            onProfileChange(
                profile.copy(
                    flags = it.toRawFlags(),
                )
            )
        }

        SELinuxPanel(enabled = enabled, profile = profile, onSELinuxChange = { domain, rules ->
            // 域与规则作为一组一起写回，避免只更新其一
            onProfileChange(
                profile.copy(
                    context = domain,
                    rules = rules,
                    rootUseDefault = false
                )
            )
        })
    }
}

/**
 * 附加组多选面板。草稿集合在对话框内维护，取消时回滚到 [selected]。
 * 与内核一致限制最多 32 个组。
 */
@Composable
private fun GroupsPanel(
    enabled: Boolean,
    selected: List<Groups>,
    closeSelection: (selection: Set<Groups>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    // 常用组优先，其余按名称排序，便于在较长列表中查找
    val groups = remember {
        Groups.entries.toTypedArray().sortedWith(
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

    // 草稿选择：与外部 selected 解耦，确认前不产生副作用
    val currentSelection = remember(selected) { mutableStateOf(selected.toSet()) }

    OverlayDialog(
        show = showDialog.value,
        title = stringResource(R.string.profile_groups),
        // 摘要实时反映草稿数量，给出剩余额度提示
        summary = "${currentSelection.value.size} / 32",
        onDismissRequest = { showDialog.value = false },
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            // 限制最大高度，组较多时列表内部滚动
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(groups) { group ->
                        CheckboxPreference(
                            title = group.display,
                            summary = group.desc,
                            insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                            checkboxLocation = CheckboxLocation.End,
                            checked = currentSelection.value.contains(group),
                            // 已选项保持按压态，形成多选视觉
                            holdDownState = currentSelection.value.contains(group),
                            onCheckedChange = { isChecked ->
                                val newSelection = currentSelection.value.toMutableSet()
                                if (isChecked) {
                                    // 超过 32 个直接忽略，保证与内核上限一致
                                    if (newSelection.size < 32) newSelection.add(group)
                                } else {
                                    newSelection.remove(group)
                                }
                                currentSelection.value = newSelection
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            // 取消：恢复为外部当前值
                            currentSelection.value = selected.toSet()
                            showDialog.value = false
                        },
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        onClick = {
                            closeSelection(currentSelection.value)
                            showDialog.value = false
                        },
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    val tag = if (selected.isEmpty()) {
        "None"
    } else {
        selected.joinToString(separator = ",", transform = { it.display })
    }
    ArrowPreference(
        enabled = enabled,
        title = stringResource(R.string.profile_groups),
        summary = tag,
        onClick = {
            showDialog.value = true
        }
    )

}

/**
 * 挂载命名空间选择。用下拉而非对话框，索引即内核约定的命名空间取值。
 */
@Composable
private fun MountNameSpacePanel(
    enabled: Boolean,
    profile: Natives.Profile,
    onMntNamespaceChange: (namespaceType: Int) -> Unit
) {
    OverlayDropdownPreference(
        enabled = enabled,
        title = stringResource(id = R.string.profile_namespace),
        // 顺序必须与内核取值 0/1/2 对应
        items = listOf(
            stringResource(id = R.string.profile_namespace_inherited),
            stringResource(id = R.string.profile_namespace_global),
            stringResource(id = R.string.profile_namespace_individual),
        ),
        selectedIndex = profile.namespace, onSelectedIndexChange = { index ->
            onMntNamespaceChange(index)
        }
    )
}

/**
 * root flag 多选面板。flag 枚举数量有限，不限制选择上限。
 * 草稿选择在对话框内维护，取消时回滚。
 */
@Composable
private fun RootProfileFlagPanel(
    enabled: Boolean,
    selected: List<Natives.Profile.RootProfileFlag>,
    closeSelection: (selection: List<Natives.Profile.RootProfileFlag>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    val caps = remember {
        Natives.Profile.RootProfileFlag.entries.toTypedArray().sortedBy { it.display }
    }

    val currentSelection = remember(selected) { mutableStateOf(selected.toSet()) }

    OverlayDialog(
        show = showDialog.value,
        title = stringResource(R.string.profile_flags),
        onDismissRequest = { showDialog.value = false },
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(caps) { cap ->
                        CheckboxPreference(
                            title = cap.display,
                            // flag 的描述是资源 id，需要在此解析
                            summary = stringResource(cap.desc),
                            insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                            checkboxLocation = CheckboxLocation.End,
                            checked = currentSelection.value.contains(cap),
                            holdDownState = currentSelection.value.contains(cap),
                            onCheckedChange = { isChecked ->
                                val newSelection = currentSelection.value.toMutableSet()
                                if (isChecked) {
                                    newSelection.add(cap)
                                } else {
                                    newSelection.remove(cap)
                                }
                                currentSelection.value = newSelection
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            // 先关窗再回滚，避免回滚瞬间闪一下旧选择
                            showDialog.value = false
                            currentSelection.value = selected.toSet()
                        },
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        onClick = {
                            closeSelection(currentSelection.value.toList())
                            showDialog.value = false
                        },
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    val tag = if (selected.isEmpty()) {
        "None"
    } else {
        selected.joinToString(separator = ",", transform = { it.display })
    }
    ArrowPreference(
        enabled = enabled,
        title = stringResource(R.string.profile_flags),
        summary = tag,
        onClick = {
            showDialog.value = true
        }
    )

}

/**
 * capabilities 多选面板。传入的是已映射的枚举集合，
 * 因此摘要可直接展示可读名称，无需再反查数值。
 */
@Composable
private fun CapsPanel(
    enabled: Boolean,
    selected: Collection<Capabilities>,
    closeSelection: (selection: Set<Capabilities>) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    val caps = remember {
        Capabilities.entries.toTypedArray().sortedBy { it.display }
    }

    val currentSelection = remember(selected) { mutableStateOf(selected.toSet()) }

    OverlayDialog(
        show = showDialog.value,
        title = stringResource(R.string.profile_capabilities),
        onDismissRequest = { showDialog.value = false },
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(caps) { cap ->
                        CheckboxPreference(
                            title = cap.display,
                            summary = cap.desc,
                            insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                            checkboxLocation = CheckboxLocation.End,
                            checked = currentSelection.value.contains(cap),
                            holdDownState = currentSelection.value.contains(cap),
                            onCheckedChange = { isChecked ->
                                val newSelection = currentSelection.value.toMutableSet()
                                if (isChecked) {
                                    newSelection.add(cap)
                                } else {
                                    newSelection.remove(cap)
                                }
                                currentSelection.value = newSelection
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            showDialog.value = false
                            currentSelection.value = selected.toSet()
                        },
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        onClick = {
                            closeSelection(currentSelection.value)
                            showDialog.value = false
                        },
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    val tag = if (selected.isEmpty()) {
        "None"
    } else {
        selected.joinToString(separator = ",", transform = { it.display })
    }
    ArrowPreference(
        enabled = enabled,
        title = stringResource(R.string.profile_capabilities),
        summary = tag,
        onClick = {
            showDialog.value = true
        }
    )

}

/**
 * SELinux 域与规则编辑面板。草稿以 profile 当前值为初值，
 * 外部值变化时（profile.context/rules 变更）重新初始化，避免展示过期内容。
 * 确认按钮在两个字段都合法时才可用。
 */
@Composable
private fun SELinuxPanel(
    enabled: Boolean,
    profile: Natives.Profile,
    onSELinuxChange: (domain: String, rules: String) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    // 用上下文本身作为 remember key，外部更新后草稿自动刷新
    var domain by remember(profile.context) { mutableStateOf(profile.context) }
    var rules by remember(profile.rules) { mutableStateOf(profile.rules) }

    val isDomainValid = remember(domain) {
        // 四段式 context，只允许小写字母/数字/下划线
        val regex = Regex("^[a-z_]+:[a-z0-9_]+:[a-z0-9_]+(:[a-z0-9_]+)?$")
        domain.matches(regex)
    }
    val isRulesValid = remember(rules) { isSepolicyValid(rules) }

    OverlayDialog(
        show = showDialog.value,
        title = stringResource(R.string.profile_selinux_context),
        onDismissRequest = { showDialog.value = false },
        content = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    TextField(
                        value = domain,
                        onValueChange = { domain = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        label = stringResource(id = R.string.profile_selinux_domain),
                        // 非法时用红色边框提示，深浅色主题下调整透明度保持对比
                        colors = TextFieldDefaults.textFieldColors(
                            borderColor = if (isDomainValid) {
                                colorScheme.primary
                            } else {
                                Color.Red.copy(alpha = if (isSystemInDarkTheme()) 0.3f else 0.6f)
                            },
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true
                    )
                    TextField(
                        value = rules,
                        onValueChange = { rules = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        label = stringResource(id = R.string.profile_selinux_rules),
                        colors = TextFieldDefaults.textFieldColors(
                            borderColor = if (isRulesValid) {
                                colorScheme.primary
                            } else {
                                Color.Red.copy(alpha = if (isSystemInDarkTheme()) 0.3f else 0.6f)
                            },
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                        ),
                        singleLine = false
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = { showDialog.value = false },
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        onClick = {
                            onSELinuxChange(domain, rules)
                            showDialog.value = false
                        },
                        text = stringResource(R.string.confirm),
                        // 任一字段非法则禁止确认，避免写入内核失败
                        enabled = isDomainValid && isRulesValid,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )

    ArrowPreference(
        enabled = enabled,
        title = stringResource(R.string.profile_selinux_context),
        summary = profile.context,
        onClick = { showDialog.value = true }
    )
}
