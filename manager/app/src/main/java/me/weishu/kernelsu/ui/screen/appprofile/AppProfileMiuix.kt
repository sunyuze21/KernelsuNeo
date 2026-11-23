package me.weishu.kernelsu.ui.screen.appprofile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.AppIconImage
import me.weishu.kernelsu.ui.component.ListPopupDefaults
import me.weishu.kernelsu.ui.component.miuix.DropdownItem
import me.weishu.kernelsu.ui.component.profile.AppProfileConfig
import me.weishu.kernelsu.ui.component.profile.RootProfileConfig
import me.weishu.kernelsu.ui.component.profile.TemplateConfig
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.listAppProfileTemplates
import me.weishu.kernelsu.ui.util.ownerNameForUid
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author weishu
 * @date 2023/5/16.
 */
// 应用 profile 的 Miuix 布局：与 Material 版本共享状态和动作，只在组件与视觉上不同。
@Composable
fun AppProfileScreenMiuix(
    state: AppProfileUiState,
    actions: AppProfileActions,
) {
    // 模糊背景依赖运行时着色器；不支持时 backdrop 为空，顶栏退化为纯色。
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            TopBar(
                onBack = actions.onBack,
                // 共享 uid 或 WebView zygote 没有明确的单一应用可操作。
                showActions = !state.isUidGroup && !state.appGroup.primary.isWebViewZygote,
                packageName = state.packageName,
                // Android 多用户 uid 编码为 userId * 100000 + appId。
                userId = state.uid / 100000,
                onLaunchApp = actions.onLaunchApp,
                onForceStopApp = actions.onForceStopApp,
                onRestartApp = actions.onRestartApp,
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
                    .padding(top = 16.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    AppProfileInner(
                        // 共享 uid 时没有单一包名，改用组名与受影响应用列表展示。
                        packageName = if (state.isUidGroup) {
                            ""
                        } else {
                            state.appGroup.primary.displayIdentifier
                        },
                        appLabel = if (state.isUidGroup) ownerNameForUid(state.appGroup.primary.uid) else state.appGroup.primary.label,
                        appIcon = {
                            AppIconImage(
                                packageInfo = state.appGroup.primary.packageInfo,
                                label = state.appGroup.primary.label,
                                modifier = Modifier.size(64.dp)
                            )
                        },
                        appUid = state.uid,
                        // 共享 uid 时才展示 sharedUserId 提示。
                        sharedUserId = if (state.isUidGroup) state.sharedUserId else "",
                        appVersionName = if (state.isUidGroup) "" else (state.appGroup.primary.packageInfo.versionName ?: ""),
                        appVersionCode = if (state.isUidGroup) 0L else state.appGroup.primary.packageInfo.longVersionCode,
                        profile = state.profile,
                        isUidGroup = state.isUidGroup,
                        isSpecialApp = state.appGroup.primary.special,
                        affectedApps = state.appGroup.apps,
                        onViewTemplate = actions.onViewTemplate,
                        onManageTemplate = actions.onManageTemplate,
                        onProfileChange = actions.onProfileChange,
                    )
                    Spacer(
                        Modifier.height(
                            // 同时补偿导航栏与 caption bar，避免最后一项被系统手势条遮挡。
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

/**
 * 应用 profile 的主体内容：应用信息、root 开关和策略编辑区。
 * 是否展示 root 策略完全由 [isSpecialApp] 与 [Natives.Profile.allowSu] 决定。
 */
@Composable
private fun AppProfileInner(
    modifier: Modifier = Modifier,
    packageName: String,
    appLabel: String,
    appIcon: @Composable (() -> Unit),
    appUid: Int,
    sharedUserId: String = "",
    appVersionName: String,
    appVersionCode: Long,
    profile: Natives.Profile,
    isUidGroup: Boolean = false,
    isSpecialApp: Boolean = false,
    affectedApps: List<SuperUserViewModel.AppInfo> = emptyList(),
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit,
) {
    // 特殊应用（如 shell、system）不能通过此页授予 root。
    val isRootGranted = !isSpecialApp && profile.allowSu
    // Android 多用户 uid 编码：userId * 100000 + appId。
    val userId = appUid / 100000
    val appId = appUid % 100000
    // 模板列表用于 Template 模式下选择与回显。
    val templates = remember { listAppProfileTemplates() }

    Column(
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                appIcon()
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 8.dp)
                        .weight(1f),
                ) {
                    Text(
                        text = appLabel,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight(550),
                        modifier = Modifier
                            .basicMarquee(),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (isSpecialApp) {
                        Text(
                            text = packageName,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    } else if (!isUidGroup) {
                        Text(
                            text = "$appVersionName ($appVersionCode)",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = packageName,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        if (sharedUserId.isNotEmpty()) {
                            Text(
                                text = sharedUserId,
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .basicMarquee(),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = stringResource(R.string.group_contains_apps, affectedApps.size),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Column(
                    modifier = Modifier,
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (userId != 0) {
                        // 非主用户时同时展示 userId 与 appId，避免只看 appId 产生歧义。
                        StatusTag(
                            label = "USER $userId",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                        StatusTag(
                            label = "UID $appId",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                    } else {
                        StatusTag(
                            label = "UID $appUid",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        if (!isSpecialApp) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                            tint = colorScheme.onBackground
                        )
                    },
                    title = stringResource(id = R.string.superuser),
                    checked = isRootGranted,
                    onCheckedChange = { onProfileChange(profile.copy(allowSu = it)) },
                )
            }
        }

        // 模板优先于自定义：rootUseDefault 为 true 时跟随默认策略。
        val initialRootMode = if (profile.rootUseDefault) {
            Mode.Default
        } else if (profile.rootTemplate != null) {
            Mode.Template
        } else {
            Mode.Custom
        }
        // 本地模式仅初始化一次，后续由 profile 回调驱动。
        var rootMode by rememberSaveable {
            mutableStateOf(initialRootMode)
        }
        // 非 root 只有默认与自定义两种状态，没有模板概念。
        val nonRootMode = if (profile.nonRootUseDefault) Mode.Default else Mode.Custom
        val dropdownMode = if (isRootGranted) rootMode else nonRootMode
        // 切换 root 模式；Template 需要同时写入模板的 uid/gid/能力集。
        ProfileBox(dropdownMode, isRootGranted) { mode ->
            if (isRootGranted) {
                when (mode) {
                    Mode.Default, Mode.Custom -> {
                        // 选择默认或自定义都会清空已绑定的模板。
                        onProfileChange(
                            profile.copy(
                                rootUseDefault = mode == Mode.Default,
                                rootTemplate = null
                            )
                        )
                        rootMode = mode
                    }

                    Mode.Template -> {
                        if (templates.isNotEmpty()) {
                            // 默认选中当前模板，没有则取第一个可用模板。
                            val selected = profile.rootTemplate ?: templates[0]
                            val info = me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById(selected)
                            if (info != null && setSepolicy(selected, info.rules.joinToString("\n"))) {
                                // 模板不仅改 SELinux 规则，还会覆盖 uid/gid、附加组和能力集。
                                onProfileChange(
                                    profile.copy(
                                        rootUseDefault = false,
                                        rootTemplate = selected,
                                        uid = info.uid,
                                        gid = info.gid,
                                        groups = info.groups,
                                        capabilities = info.capabilities,
                                        context = info.context,
                                        namespace = info.namespace,
                                    )
                                )
                            } else if (profile.rootTemplate != selected || profile.rootUseDefault) {
                                // 写 SELinux 规则失败时只记录模板 id，避免把不完整策略落盘。
                                onProfileChange(
                                    profile.copy(
                                        rootUseDefault = false,
                                        rootTemplate = selected
                                    )
                                )
                            }
                            rootMode = Mode.Template
                        }
                    }
                }
            } else {
                onProfileChange(profile.copy(nonRootUseDefault = (mode == Mode.Default)))
            }
        }
        Spacer(Modifier.height(12.dp))

        // root 与普通应用使用不同配置组件，避免展示无关字段。
        AnimatedVisibility(
            visible = isRootGranted,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = if (rootMode != Mode.Default) 12.dp else 0.dp),
            ) {
                // 模板模式需要展示模板选择与查看入口。
                AnimatedVisibility(
                    visible = rootMode == Mode.Template,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TemplateConfig(
                        profile = profile,
                        onViewTemplate = onViewTemplate,
                        onManageTemplate = onManageTemplate,
                        onProfileChange = onProfileChange
                    )
                }
                // 自定义模式才允许编辑底层字段。
                AnimatedVisibility(
                    visible = rootMode == Mode.Custom,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    RootProfileConfig(
                        fixedName = true,
                        enabled = rootMode == Mode.Custom,
                        profile = profile,
                        onProfileChange = onProfileChange
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !isRootGranted,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // 未授权 root 时只展示应用级配置，不展示 uid/gid 等 root 字段。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = if (nonRootMode != Mode.Default) 12.dp else 0.dp),
            ) {
                AnimatedVisibility(
                    visible = nonRootMode == Mode.Custom,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    AppProfileConfig(
                        fixedName = true,
                        profile = profile,
                        enabled = true,
                        onProfileChange = onProfileChange
                    )
                }
            }
        }

        if (isUidGroup) {
            // 共享 uid 时列出所有受影响应用，提醒用户策略是整组生效的。
            SmallTitle(
                text = stringResource(R.string.app_profile_affects_following_apps),
                modifier = Modifier.padding(top = 4.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Spacer(Modifier.height(3.dp))
                affectedApps.forEach { app ->
                    BasicComponent(
                        startAction = {
                            AppIconImage(
                                packageInfo = app.packageInfo,
                                label = app.label,
                                modifier = Modifier
                                    .padding(end = 1.dp)
                                    .size(48.dp)
                            )
                        },
                        title = app.label,
                        summary = app.displayIdentifier,
                        insideMargin = PaddingValues(start = 11.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

/**
 * 应用 profile 顶栏：在支持模糊时叠加 backdrop，并在普通单应用下展示更多菜单。
 * 菜单里的启动/停止/重启都需要包名与 userId。
 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    showActions: Boolean = true,
    packageName: String,
    userId: Int,
    onLaunchApp: (String, Int) -> Unit = { _, _ -> },
    onForceStopApp: (String, Int) -> Unit = { _, _ -> },
    onRestartApp: (String, Int) -> Unit = { _, _ -> },
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.profile),
            navigationIcon = {
                IconButton(
                    onClick = onBack
                ) {
                    val layoutDirection = LocalLayoutDirection.current
                    Icon(
                        modifier = Modifier.graphicsLayer {
                            // RTL 语言下把返回箭头水平翻转。
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        tint = colorScheme.onBackground
                    )
                }
            },
            actions = {
                if (showActions) {
                    val showTopPopup = remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showTopPopup.value = true },
                        holdDownState = showTopPopup.value
                    ) {
                        Icon(
                            imageVector = MiuixIcons.MoreCircle,
                            tint = colorScheme.onSurface,
                            contentDescription = stringResource(id = R.string.settings)
                        )
                    }
                    OverlayListPopup(
                        show = showTopPopup.value,
                        popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                        alignment = PopupPositionProvider.Align.TopEnd,
                        onDismissRequest = { showTopPopup.value = false },
                        content = {
                            ListPopupColumn {
                                // 三个动作都针对同一 uid 下的应用实例。
                                val items = listOf(
                                    stringResource(id = R.string.launch_app),
                                    stringResource(id = R.string.force_stop_app),
                                    stringResource(id = R.string.restart_app)
                                )

                                items.forEachIndexed { index, text ->
                                    DropdownItem(
                                        text = text,
                                        optionSize = items.size,
                                        index = index,
                                        onSelectedIndexChange = { selectedIndex ->
                                            when (selectedIndex) {
                                                0 -> onLaunchApp(packageName, userId)
                                                1 -> onForceStopApp(packageName, userId)
                                                2 -> onRestartApp(packageName, userId)
                                            }
                                            showTopPopup.value = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

/**
 * root/非 root 策略模式选择器（Miuix 下拉形式）。
 * Template 只在已有可用模板时加入列表，避免用户选到空模板。
 */
@Composable
private fun ProfileBox(
    mode: Mode,
    hasTemplate: Boolean,
    onModeChange: (Mode) -> Unit,
) {
    val defaultText = stringResource(R.string.profile_default)
    val templateText = stringResource(R.string.profile_template)
    val customText = stringResource(R.string.profile_custom)
    // 下拉展示文案与 Mode 的对应关系单独维护，保证顺序一致。
    val list =
        remember(hasTemplate, defaultText, templateText, customText) {
            buildList {
                add(defaultText)
                if (hasTemplate) {
                    add(templateText)
                }
                add(customText)
            }
        }

    val modesAndTitles = remember(hasTemplate, defaultText, templateText, customText) {
        buildList {
            add(Mode.Default to defaultText)
            if (hasTemplate) {
                add(Mode.Template to templateText)
            }
            add(Mode.Custom to customText)
        }
    }
    // 找不到对应项时回退到第一项，避免下拉越界。
    val selectedIndex = modesAndTitles.indexOfFirst { it.first == mode }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.profile),
            items = list,
            startAction = {
                Icon(
                    Icons.Rounded.AccountCircle,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground
                )
            },
            selectedIndex = if (selectedIndex == -1) 0 else selectedIndex,
        ) {
            onModeChange(modesAndTitles[it].first)
        }
    }
}
