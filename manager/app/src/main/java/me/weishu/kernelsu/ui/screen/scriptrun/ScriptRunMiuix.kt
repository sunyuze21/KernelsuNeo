package me.weishu.kernelsu.ui.screen.scriptrun

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.script.PreparedScript
import me.weishu.kernelsu.data.script.ScriptParam
import me.weishu.kernelsu.ui.component.miuix.EditText
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.ShellHighlightColors
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.rememberShellHighlight
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 脚本运行页的 Miuix 实现：结构上与 Material 版一致，仅控件与动效不同。
 *
 * 准备阶段显示无限进度；失败时展示错误卡片，并在允许时提供“仍作为模块安装”
 * 的入口。所有输入仍以 [ScriptRunUiState] 为准。
 */
@Composable
internal fun ScriptRunScreenMiuix(
    state: ScriptRunUiState,
    actions: ScriptRunActions,
) {
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    // 模糊不可用时使用不透明 surface，避免顶栏内容与正文混在一起。
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            TopBar(
                onBack = actions.onBack,
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        // 解析未完成且未失败时展示准备中状态；其余情况进入列表。
        val prepared = state.prepared
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            if (prepared == null && state.phase != ScriptRunPhase.FAILED) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        InfiniteProgressIndicator()
                        Text(
                            text = stringResource(R.string.script_preparing),
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                return@Box
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .imePadding()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(top = 12.dp)
                    .padding(horizontal = 16.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    if (prepared == null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            BasicComponent(
                                title = stringResource(R.string.script_error_title),
                                summary = state.error,
                            )
                        }
                        if (state.offerInstall) InstallAnywayCardMiuix(actions)
                    } else {
                        ScriptInfoMiuix(state, prepared, actions)
                        ParamsFormMiuix(state, prepared, actions)
                        SourceSectionMiuix(prepared)
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            text = stringResource(R.string.script_run),
                            enabled = state.canRun,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions.onRun,
                        )
                    }
                    Spacer(
                        Modifier.height(
                            12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

/** 脚本信息卡片：把 manifest 的名称、版本、作者与描述合并成摘要展示。 */
@Composable
private fun ScriptInfoMiuix(state: ScriptRunUiState, prepared: PreparedScript, actions: ScriptRunActions) {
    val context = LocalContext.current
    // manifest 字段允许为空，拼接摘要时逐项判空白。
    val manifest = prepared.manifest
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = manifest.name,
            summary = buildString {
                append(scriptSubtitle(manifest.id, manifest.version, manifest.versionCode))
                if (manifest.author.isNotBlank()) {
                    append('\n').append(stringResource(R.string.module_author)).append(": ").append(manifest.author)
                }
                if (manifest.description.isNotBlank()) append('\n').append(manifest.description)
            },
        )
        BasicComponent(
            title = stringResource(R.string.script_notice),
            summary = lastRunText(context, state.lastRun),
        )
    }
    if (state.offerInstall) InstallAnywayCardMiuix(actions)
}

/** A local ZIP can still be installed as a module, also when it cannot be run as a script. */
@Composable
private fun InstallAnywayCardMiuix(actions: ScriptRunActions) {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
    ) {
        BasicComponent(summary = stringResource(R.string.script_local_notice))
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            text = stringResource(R.string.script_install_anyway),
            onClick = actions.onInstallAsModule,
        )
    }
}

/** 参数表单：无参数时不渲染标题与卡片，保持页面紧凑。 */
@Composable
private fun ParamsFormMiuix(state: ScriptRunUiState, prepared: PreparedScript, actions: ScriptRunActions) {
    val params = prepared.params.params
    if (params.isEmpty()) return
    SmallTitle(text = stringResource(R.string.script_params), modifier = Modifier.padding(top = 6.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        params.forEach { param ->
            // 未输入时使用脚本默认值；错误来自 ViewModel 的统一校验结果。
            val value = state.values[param.key] ?: param.defaultValue
            val error = state.errors[param.key]
            when (param) {
                is ScriptParam.Bool -> SwitchPreference(
                    title = param.label,
                    summary = param.description,
                    checked = value == "true",
                    onCheckedChange = { actions.onValueChange(param.key, it.toString()) },
                )

                is ScriptParam.Choice -> OverlayDropdownPreference(
                    items = param.options.map { it.label ?: it.value },
                    selectedIndex = param.options.indexOfFirst { it.value == value }.coerceAtLeast(0),
                    title = param.label,
                    summary = param.description,
                    onSelectedIndexChange = { index -> actions.onValueChange(param.key, param.options[index].value) },
                )

                is ScriptParam.Text, is ScriptParam.Integer -> {
                    EditText(
                        title = param.label,
                        value = value,
                        onValueChange = { actions.onValueChange(param.key, it) },
                        summary = error?.let { paramErrorText(param, it) } ?: param.description,
                        keyboardOptions = if (param is ScriptParam.Integer) {
                            KeyboardOptions(keyboardType = integerKeyboardType(param))
                        } else {
                            KeyboardOptions.Default
                        },
                        isError = error != null,
                    )
                }
            }
        }
    }
}

/** 可展开源码区；展开后用高亮文本横向滚动展示，避免长命令被迫换行。 */
@Composable
private fun SourceSectionMiuix(prepared: PreparedScript) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
    ) {
        BasicComponent(
            title = stringResource(R.string.script_source),
            summary = if (prepared.actionSourceTruncated) stringResource(R.string.script_source_truncated) else null,
            onClick = { expanded = !expanded },
            endActions = {
                Icon(
                    if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurfaceVariantActions,
                    contentDescription = null,
                )
            }
        )
        if (expanded) {
            // 高亮底色跟随 surfaceContainer，保证与卡片背景协调。
            val source = rememberShellHighlight(
                prepared.actionSource,
                ShellHighlightColors.forBackground(colorScheme.surfaceContainer),
            )
            SelectionContainer {
                Text(
                    text = source,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** Miuix 顶栏：在模糊可用时透出背景，[backdrop] 与 [barColor] 需成对使用。 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.script_run_title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    val layoutDirection = LocalLayoutDirection.current
                    Icon(
                        modifier = Modifier.graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                        imageVector = MiuixIcons.Back,
                        tint = colorScheme.onSurface,
                        contentDescription = null,
                    )
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}
