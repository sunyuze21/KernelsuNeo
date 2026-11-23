package me.weishu.kernelsu.ui.component.material

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.gesture.WindowNavigationEventBridge

/**
 * 顶栏 + 可展开全屏搜索的组合组件。
 *
 * 折叠态下搜索框内嵌在页面中，展开后切换到全屏 SearchBar 覆盖内容。
 * 为避免“输入 → 回调 → 外部状态回写 → 再次触发输入”的死循环，
 * 内部 [textFieldState] 与外部 `searchText` 做了双向同步但都带相等性判断，
 * 且用 `rememberUpdatedState` 读取最新外部值，防止 collector 捕获旧闭包。
 * 收起时默认清空关键字并隐藏键盘，符合“退出搜索即重置”的交互预期。
 *
 * @param title 页面标题内容
 * @param searchText 外部持有的搜索关键字
 * @param onSearchTextChange 关键字变化回调
 * @param onClearClick 点击清除按钮时的回调（用于同时清理业务侧结果）
 * @param snackbarHostState 全屏搜索页底部的 Snackbar 状态
 * @param navigationIcon 折叠态顶栏的导航图标
 * @param actions 折叠态顶栏的操作区
 * @param scrollBehavior 顶栏滚动行为，控制大标题折叠
 * @param defaultContent 关键字为空时展示的内容（通常是搜索历史/推荐）
 * @param searchContent 有关键字时展示的搜索结果
 */
@Composable
fun SearchAppBar(
    title: @Composable () -> Unit,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onClearClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    defaultContent: @Composable BoxScope.(bottomPadding: Dp, closeSearch: () -> Unit) -> Unit = { _, _ -> },
    searchContent: @Composable BoxScope.(bottomPadding: Dp, closeSearch: () -> Unit) -> Unit = { _, _ -> }
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scaledDensity = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }

    val scope = rememberCoroutineScope()
    val searchBarState = rememberContainedSearchBarState()
    val textFieldState = rememberTextFieldState()
    val currentQuery = textFieldState.text.toString()
    val latestSearchText by rememberUpdatedState(searchText)
    // 目标值也参与判断：动画进行中 target 已是 Expanded，可避免中途重复触发收起逻辑
    val isSearchExpanded = searchBarState.currentValue != SearchBarValue.Collapsed || searchBarState.targetValue != SearchBarValue.Collapsed
    var previousSearchBarValue by remember { mutableStateOf(searchBarState.currentValue) }
    // 主动收起时置 false，避免 collapseAndClear 清空后又被折叠监听重复清空一次
    var shouldClearOnCollapse by remember { mutableStateOf(true) }
    val clearSearchText: () -> Unit = {
        textFieldState.setTextAndPlaceCursorAtEnd("")
        onClearClick()
    }
    val collapseAndClear: () -> Unit = {
        shouldClearOnCollapse = false
        clearSearchText()
        scope.launch { searchBarState.animateToCollapsed() }
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // 先隐藏键盘再收起，顺序反了会在动画期间看到输入法残留
    DisposableEffect(Unit) {
        onDispose {
            keyboardController?.hide()
        }
    }

    // 外部关键字变化时同步到输入框；相等性判断可防止光标被无谓地推到末尾
    LaunchedEffect(searchText) {
        val current = textFieldState.text.toString()
        if (current != searchText) {
            textFieldState.setTextAndPlaceCursorAtEnd(searchText)
        }
    }

    // 输入框 → 外部的单向回流；distinctUntilChanged 退掉重复快照，减少上层重组
    LaunchedEffect(textFieldState, latestSearchText) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { value ->
                if (value != latestSearchText) {
                    onSearchTextChange(value)
                }
            }
    }

    // 只有在“展开 → 折叠”这个方向才清理，展开过程不触发，避免刚点开就被清空
    LaunchedEffect(searchBarState) {
        snapshotFlow { searchBarState.currentValue }
            .distinctUntilChanged()
            .collect { value ->
                val collapsedFromExpanded =
                    previousSearchBarValue != SearchBarValue.Collapsed && value == SearchBarValue.Collapsed
                previousSearchBarValue = value
                if (collapsedFromExpanded) {
                    if (shouldClearOnCollapse) {
                        clearSearchText()
                    }
                    shouldClearOnCollapse = true
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            }
    }

    // 仅在全屏搜索时拦截返回键，折叠态交还给导航栈
    BackHandler(isSearchExpanded) {
        if (isSearchExpanded) {
            collapseAndClear()
        }
    }

    val inputField: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                leadingIcon = {
                    if (isSearchExpanded) {
                        IconButton(
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = { collapseAndClear() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                        }
                    } else {
                        Icon(Icons.Filled.Search, null)
                    }
                },
                trailingIcon = {
                    if (isSearchExpanded && currentQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { clearSearchText() },
                            content = { Icon(Icons.Filled.Close, null) }
                        )
                    }
                },
                interactionSource = interactionSource
            )
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            LargeFlexibleTopAppBar(
                title = title,
                colors = expressiveTopAppBarColors(),
                navigationIcon = { if (navigationIcon != null) navigationIcon() },
                actions = { if (actions != null) actions() },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior
            )

            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 13.dp),

                state = searchBarState,
                inputField = inputField,
                colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }

    ExpandedFullScreenContainedSearchBar(
        state = searchBarState,
        inputField = inputField,
        windowInsets = { SearchBarDefaults.fullScreenWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
        content = {
            WindowNavigationEventBridge()
            // 先算出系统栏占据的底部高度，让内容自行决定是否额外避让
            val bottomPadding = SearchBarDefaults.fullScreenWindowInsets.asPaddingValues().calculateBottomPadding()
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentQuery.isNotEmpty()) {
                    searchContent(bottomPadding, collapseAndClear)
                } else {
                    defaultContent(bottomPadding, collapseAndClear)
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 16.dp)
                ) {
                    SnackBarHost(hostState = snackbarHostState)
                }
            }
        }
    )
}
