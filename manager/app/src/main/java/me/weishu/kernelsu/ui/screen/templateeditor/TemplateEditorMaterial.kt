package me.weishu.kernelsu.ui.screen.templateeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedTextField
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.profile.RootProfileConfig

/**
 * 模板编辑器的 Material 实现。
 *
 * 顶栏按新建/只读/编辑三种模式切换标题与动作；表单字段通过
 * [TemplateEditorListItem] 统一渲染，RootProfileConfig 负责 UID/GID 等
 * 根权限配置。只读模式下所有输入与保存动作都应被禁用。
 */
@Composable
fun TemplateEditorScreenMaterial(
    state: TemplateEditorUiState,
    actions: TemplateEditorActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            TopBar(
                title = if (state.isCreation) {
                    stringResource(R.string.app_profile_template_create)
                } else if (state.readOnly) {
                    stringResource(R.string.app_profile_template_view)
                } else {
                    stringResource(R.string.app_profile_template_edit)
                },
                readOnly = state.readOnly,
                summary = state.titleSummary,
                onBack = actions.onBack,
                onDelete = actions.onDelete,
                onSave = actions.onSave,
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(top = 13.dp)
                .imePadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add(
                        {
                            TemplateEditorListItem(
                                label = stringResource(id = R.string.app_profile_template_name),
                                value = state.template.name,
                                readOnly = state.readOnly,
                                onValueChange = actions.onNameChange,
                            )
                        }
                    )
                    if (state.isCreation) {
                        add(
                            {
                                // id 仅新建时可见，既有模板的 id 不允许修改。
                                TemplateEditorListItem(
                                    label = stringResource(id = R.string.app_profile_template_id),
                                    value = state.template.id,
                                    errorHint = state.idErrorHint,
                                    isError = state.idErrorHint.isNotEmpty(),
                                    readOnly = state.readOnly,
                                    onValueChange = actions.onIdChange,
                                )
                            }
                        )
                    }
                    add(
                        {
                            TemplateEditorListItem(
                                label = stringResource(id = R.string.module_author),
                                value = state.template.author,
                                readOnly = state.readOnly,
                                onValueChange = actions.onAuthorChange,
                            )
                        }
                    )
                    add(
                        {
                            TemplateEditorListItem(
                                label = stringResource(id = R.string.app_profile_template_description),
                                value = state.template.description,
                                multiline = true,
                                readOnly = state.readOnly,
                                onValueChange = actions.onDescriptionChange,
                            )
                        }
                    )
                }
            )

            RootProfileConfig(
                // fixedName 避免用户在此修改模板名，名称由上方字段统一维护。
                fixedName = true,
                enabled = !state.readOnly,
                profile = toNativeProfile(state.template),
                onProfileChange = actions.onProfileChange,
            )

            Spacer(
                Modifier.height(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            )
        }
    }
}

/** 模板文本字段：可选多行、错误提示与只读状态，统一 Material 文本样式。 */
@Composable
private fun TemplateEditorListItem(
    label: String,
    value: String,
    errorHint: String = "",
    isError: Boolean = false,
    multiline: Boolean = false,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit
) {
    SegmentedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        supportingContent = if (isError && errorHint.isNotEmpty()) {
            { Text(errorHint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        } else null,
        isError = isError,
        singleLine = !multiline,
        minLines = 1,
        maxLines = if (multiline) 100 else 1,
        readOnly = readOnly
    )
}

/** 顶栏：标题/副标题、返回，以及非只读时的删除与保存按钮。 */
@Composable
private fun TopBar(
    title: String,
    readOnly: Boolean,
    summary: String = "",
    onBack: () -> Unit,
    onDelete: () -> Unit = {},
    onSave: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = {
            Column {
                Text(title)
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        navigationIcon = {
            TopBarBackButton(onClick = onBack)
        },
        actions = {
            if (readOnly) return@LargeFlexibleTopAppBar
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = stringResource(id = R.string.app_profile_template_delete)
                )
            }
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = stringResource(id = R.string.app_profile_template_save)
                )
            }
        },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
