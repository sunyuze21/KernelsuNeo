package me.weishu.kernelsu.ui.screen.sulog

import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.ListPopupDefaults
import me.weishu.kernelsu.ui.component.ScrollToTopOnChange
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.component.miuix.SearchBarFake
import me.weishu.kernelsu.ui.component.miuix.SearchBox
import me.weishu.kernelsu.ui.component.miuix.SearchPager
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import me.weishu.kernelsu.ui.component.statustag.StatusTag
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.SulogEntry
import me.weishu.kernelsu.ui.util.SulogEventFilter
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Miuix 主题下的 SuLog 页面：搜索框与顶栏联动，日志列表支持下拉刷新，
 * 条目详情使用 OverlayDialog 展示可复制的字段。
 */
@Composable
fun SulogScreenMiuix(
    state: SulogScreenState,
    actions: SulogActions,
) {
    val enableBlur = LocalEnableBlur.current
    val density = LocalDensity.current
    val scrollBehavior = MiuixScrollBehavior()
    // 顶栏折叠程度决定搜索框顶部间距
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }
    // 仅在模糊可用时使用透明顶栏
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val pullToRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val fileSelector = buildSulogFileSelector(state.files, state.selectedFilePath)
    val refreshTexts = listOf(
        stringResource(R.string.refresh_pulling),
        stringResource(R.string.refresh_release),
        stringResource(R.string.refresh_refresh),
        stringResource(R.string.refresh_complete),
    )
    // 搜索框展开/收起状态属于 Miuix 组件内部状态，需与 ViewModel 文本双向同步
    var searchStatus by remember { mutableStateOf(SearchStatus("")) }
    var selectedEntry by remember { mutableStateOf<SulogEntry?>(null) }

    // 搜索结果为空时保持默认页，有搜索词才切到结果页
    LaunchedEffect(state.searchText, state.visibleEntries) {
        searchStatus = searchStatus.copy(
            searchText = state.searchText,
            resultStatus = if (state.searchText.isBlank()) SearchStatus.ResultStatus.DEFAULT else SearchStatus.ResultStatus.SHOW,
        )
    }

    /** 搜索状态变化时同步给 ViewModel，并按文本是否为空切换结果页显示。 */
    fun onSearchStatusChange(nextStatus: SearchStatus) {
        searchStatus = nextStatus.copy(
            resultStatus = if (nextStatus.searchText.isBlank()) SearchStatus.ResultStatus.DEFAULT else SearchStatus.ResultStatus.SHOW,
        )
        actions.onSearchTextChange(nextStatus.searchText)
    }

    SulogDetailDialog(
        show = selectedEntry != null,
        entry = selectedEntry,
        onDismiss = { selectedEntry = null },
    )

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.settings_sulog),
                        navigationIcon = {
                            IconButton(
                                onClick = actions.onBack,
                            ) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = null,
                                    tint = colorScheme.onSurface,
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                modifier = Modifier.padding(end = 8.dp),
                                onClick = actions.onCleanFile,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    tint = colorScheme.onSurface,
                                    contentDescription = stringResource(R.string.sulog_clean_title),
                                )
                            }

                            Box {
                                val showFilterPopup = remember { mutableStateOf(false) }
                                OverlayListPopup(
                                    show = showFilterPopup.value,
                                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = {
                                        showFilterPopup.value = false
                                    },
                                    content = {
                                        ListPopupColumn {
                                            // 过滤器可多选，勾选状态直接反映 selectedFilters
                                            SulogEventFilter.entries.forEachIndexed { index, filter ->
                                                DropdownImpl(
                                                    text = sulogFilterLabel(filter),
                                                    isSelected = filter in state.selectedFilters,
                                                    optionSize = SulogEventFilter.entries.size,
                                                    onSelectedIndexChange = {
                                                        actions.onToggleFilter(filter)
                                                    },
                                                    index = index,
                                                )
                                            }
                                        }
                                    },
                                )
                                IconButton(
                                    onClick = { showFilterPopup.value = true },
                                    holdDownState = showFilterPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Filter,
                                        tint = colorScheme.onSurface,
                                        contentDescription = stringResource(R.string.sulog_filter_title),
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            val newOffsetY = coordinates.positionInWindow().y.toDp()
                                            if (searchStatus.offsetY != newOffsetY) {
                                                onSearchStatusChange(searchStatus.copy(offsetY = newOffsetY))
                                            }
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    onSearchStatusChange(searchStatus.copy(current = SearchStatus.Status.EXPANDING))
                                                }
                                            }
                                        } else Modifier,
                                    ),
                            ) {
                                SearchBarFake(searchStatus.label, dynamicTopPadding)
                            }
                        }
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = ::onSearchStatusChange,
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
            ) {
                val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                ) {
                    item {
                        Spacer(Modifier.height(6.dp))
                    }
                    sulogEntriesSection(
                        entries = state.visibleEntries,
                        errorMessage = state.errorMessage,
                        onEntryClick = { selectedEntry = it },
                    )
                    item {
                        Spacer(Modifier.height(imeBottomPadding))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        searchStatus.SearchBox {
            PullToRefresh(
                isRefreshing = state.isLoading || state.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = actions.onRefresh,
                refreshTexts = refreshTexts,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
            ) {
                // 搜索词、过滤条件或文件变化时回到顶部
                val latestVisibleEntries = rememberUpdatedState(state.visibleEntries)
                ScrollToTopOnChange(
                    listState,
                    state.searchText,
                    state.selectedFilters,
                    state.selectedFilePath,
                ) { latestVisibleEntries.value }
                // 列表作为模糊层采样源，滚动时顶栏模糊随之更新
                Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 6.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                        ),
                        overscrollEffect = null,
                    ) {
                        item {
                            SulogStatusSection(state, actions)
                        }

                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp),
                            ) {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.sulog_log_files),
                                    items = fileSelector.items,
                                    enabled = fileSelector.items.isNotEmpty(),
                                    selectedIndex = fileSelector.selectedIndex,
                                    onSelectedIndexChange = { index ->
                                        state.files.getOrNull(index)?.let { file ->
                                            actions.onSelectFile(file.path)
                                        }
                                    },
                                )
                            }
                        }

                        sulogEntriesSection(
                            entries = state.visibleEntries,
                            errorMessage = state.errorMessage,
                            onEntryClick = { selectedEntry = it },
                        )

                        item {
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
    }
}

/**
 * 内核特性状态横幅；supported 且未启用时提供开启按钮。
 * 无横幅时不渲染 Row，避免其底部内边距在文件选择器上方留下空白。
 */
@Composable
private fun SulogStatusSection(
    state: SulogScreenState,
    actions: SulogActions,
) {
    // Skip entirely when no banner is shown; an empty Row would still take up
    // its bottom padding above the file selector.
    val showBanner = when (state.sulogStatus) {
        "unsupported", "managed", "forced" -> true
        "supported" -> !state.isSulogEnabled
        else -> false
    }
    if (!showBanner) return

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        when (state.sulogStatus) {
            "unsupported" -> {
                WarningCard(
                    message = stringResource(R.string.sulog_unsupported_title),
                )
            }

            "managed" -> {
                WarningCard(
                    message = stringResource(R.string.feature_status_managed_summary),
                )
            }

            "forced" -> {
                WarningCard(
                    message = stringResource(R.string.feature_status_forced_summary),
                )
            }

            "supported" if !state.isSulogEnabled -> {
                WarningCard(
                    message = stringResource(R.string.sulog_disabled_title),
                    action = {
                        TextButton(
                            text = stringResource(R.string.sulog_enable_action),
                            onClick = actions.onEnableSulog,
                            minHeight = 28.dp,
                            cornerRadius = 50.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.textButtonColors(
                                color = if (isDynamicColor) colorScheme.onErrorContainer else colorScheme.error,
                                textColor = colorScheme.onError,
                            ),
                        )
                    },
                )
            }

            else -> Unit
        }
    }
}

/** 向 LazyColumn 追加日志条目；加载失败时以整屏错误卡片替代列表。 */
private fun LazyListScope.sulogEntriesSection(
    entries: List<SulogEntry>,
    errorMessage: String?,
    onEntryClick: (SulogEntry) -> Unit,
) {
    when {
        errorMessage != null -> item {
            SulogMessageCard(
                modifier = Modifier.fillParentMaxSize(),
                title = stringResource(R.string.sulog_failed_to_load),
                summary = errorMessage,
            )
        }

        else -> itemsIndexed(entries, key = { index, entry -> "$index-${entry.key}" }) { index, entry ->
            SulogEntryCard(
                entry = entry,
                onClick = { onEntryClick(entry) },
            )
        }
    }
}

/** 单条日志卡片：标题、次要说明、时间与摘要标签，右侧显示执行结果。 */
@Composable
private fun SulogEntryCard(
    entry: SulogEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = onClick,
        showIndication = true,
        insideMargin = PaddingValues(16.dp),
    ) {
        val layoutDirection = LocalLayoutDirection.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sulogEntryTitle(entry),
                    modifier = Modifier.basicMarquee(),
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                sulogEntryDescription(entry)?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                entry.timestampText?.let {
                    Text(
                        text = it,
                        modifier = Modifier.basicMarquee(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 标签配色循环使用，数量超出时复用最后一组
                    val colors = listOf(
                        colorScheme.primary to colorScheme.onPrimary,
                        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer,
                        colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer,
                    )
                    sulogEntrySummaryTags(entry).forEachIndexed { index, tag ->
                        val (bg, fg) = colors.getOrElse(index) { colors.last() }
                        StatusTag(label = tag, backgroundColor = bg, contentColor = fg)
                    }
                }
            }
            sulogEntryStatus(entry)?.let {
                Text(
                    text = it,
                    color = colorScheme.onSurfaceVariantActions,
                    fontSize = 12.sp,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
            Image(
                modifier = Modifier
                    .graphicsLayer {
                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                    }
                    .padding(start = 8.dp)
                    .size(width = 10.dp, height = 16.dp),
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
            )
        }
    }
}

/** Miuix 风格的空态/错误态卡片，长文本最多三行并省略。 */
@Composable
private fun SulogMessageCard(
    modifier: Modifier,
    title: String,
    summary: String?,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight(550),
                color = colorScheme.onSurfaceVariantSummary,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 日志详情弹窗。关闭动画期间 [entry] 可能先变为 null，
 * 因此保留 lastEntry 作为过渡内容，避免弹窗文字瞬间消失。
 */
@Composable
private fun SulogDetailDialog(
    show: Boolean,
    entry: SulogEntry?,
    onDismiss: () -> Unit,
) {
    var lastEntry by remember { mutableStateOf(entry) }
    if (entry != null) lastEntry = entry
    val displayEntry = lastEntry ?: return
    OverlayDialog(
        show = show,
        title = sulogEntryTitle(displayEntry),
        onDismissRequest = onDismiss,
        content = {
            Column {
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = sulogEntryDetailText(displayEntry),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(android.R.string.ok),
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
