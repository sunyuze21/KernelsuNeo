package me.weishu.kernelsu.ui.screen.templateeditor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.miuix.EditText
import me.weishu.kernelsu.ui.component.profile.RootProfileConfig
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author weishu
 * @date 2023/10/20.
 *
 * 模板编辑器的 Miuix 实现。新建时顶栏显示保存按钮，已有模板编辑时显示删除，
 * 且字段修改会通过上层的 autoSave 立即持久化，因此此处不再单独提供保存按钮。
 */
@Composable
fun TemplateEditorScreenMiuix(
    state: TemplateEditorUiState,
    actions: TemplateEditorActions,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    // 模糊不可用时用不透明 surface；backdrop 与 barColor 需成对使用。
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
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
                isCreation = state.isCreation,
                onBack = actions.onBack,
                onDelete = actions.onDelete,
                onSave = actions.onSave,
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        TextEdit(
                            label = stringResource(id = R.string.app_profile_template_name),
                            text = state.template.name,
                            enabled = !state.readOnly,
                            onValueChange = actions.onNameChange,
                        )

                        TextEdit(
                            label = stringResource(id = R.string.app_profile_template_id),
                            text = state.template.id,
                            isError = state.idErrorHint.isNotEmpty(),
                            enabled = !state.readOnly,
                            onValueChange = actions.onIdChange,
                        )
                        TextEdit(
                            label = stringResource(R.string.module_author),
                            text = state.template.author,
                            enabled = !state.readOnly,
                            onValueChange = actions.onAuthorChange,
                        )

                        TextEdit(
                            label = stringResource(id = R.string.app_profile_template_description),
                            text = state.template.description,
                            enabled = !state.readOnly,
                            onValueChange = actions.onDescriptionChange,
                        )

                        RootProfileConfig(
                            fixedName = true,
                            enabled = !state.readOnly,
                            profile = toNativeProfile(state.template),
                            onProfileChange = actions.onProfileChange,
                        )
                    }
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}


/** Miuix 顶栏：处理返回与删除/新建保存，模糊背景由 [backdrop]/[barColor] 控制。 */
@Composable
private fun TopBar(
    title: String,
    readOnly: Boolean,
    isCreation: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit = {},
    onSave: () -> Unit = {},
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = title,
            navigationIcon = {
                IconButton(
                    onClick = onBack
                ) {
                    val layoutDirection = LocalLayoutDirection.current
                    Icon(
                        modifier = Modifier.graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        tint = colorScheme.onSurface
                    )
                }
            },
            actions = {
                when {
                    !readOnly && !isCreation -> {
                        IconButton(
                            onClick = onDelete
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(id = R.string.app_profile_template_delete),
                                tint = colorScheme.onBackground
                            )
                        }
                    }

                    isCreation -> {
                        IconButton(
                            onClick = onSave
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = stringResource(id = R.string.app_profile_template_save),
                                tint = colorScheme.onBackground
                            )
                        }
                    }
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

/**
 * 受控文本输入组件。
 *
 * 外部 text 变化时用 remember(text) 重建内部状态，确保保存/回填后显示最新值；
 * 每次输入同时更新本地状态并回调，兼顾即时回显与上层持久化。
 */
@Composable
private fun TextEdit(
    label: String,
    text: String,
    isError: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit = {}
) {
    // 用 text 作为 key：父层数据变化时丢弃旧草稿，避免显示过期输入。
    val editText = remember(text) { mutableStateOf(text) }
    EditText(
        title = label.uppercase(),
        value = editText.value,
        onValueChange = { newText ->
            editText.value = newText
            onValueChange(newText)
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
        ),
        isError = isError,
        enabled = enabled,
    )
}

