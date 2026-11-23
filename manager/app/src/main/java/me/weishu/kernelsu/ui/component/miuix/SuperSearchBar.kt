package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.weishu.kernelsu.ui.component.SearchStatus
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

// 该修饰符在布局阶段读取顶部内边距，并补偿自身高度，避免被推出可视区的部分不参与尺寸计算。
/** Like `padding(top = …)` but reads [top] in the layout phase, so a frame-rate value relayouts instead of recomposing. */
internal fun Modifier.deferredTopPadding(top: () -> Dp): Modifier = layout { measurable, constraints ->
    val topPx = top().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = (constraints.minHeight - topPx).coerceAtLeast(0),
            maxHeight = if (constraints.maxHeight == Constraints.Infinity) {
                Constraints.Infinity
            } else {
                (constraints.maxHeight - topPx).coerceAtLeast(0)
            }
        )
    )
    val width = constraints.constrainWidth(placeable.width)
    val height = constraints.constrainHeight(placeable.height + topPx)
    layout(width, height) {
        placeable.place(0, topPx)
    }
}

// 仅折叠态组合内容：展开时真实输入框由 SearchPager 承载，重复组合会同时存在两个焦点源。
// Search Box Composable
/**
 * 折叠态的搜索框插槽。
 *
 * 只有 [SearchStatus.shouldCollapsed] 为真时才组合内容，避免与展开态的输入框
 * 同时存在；这样也省掉一次输入框的组合与焦点初始化开销。
 */
@Composable
fun SearchStatus.SearchBox(
    content: @Composable () -> Unit
) {
    if (shouldCollapsed()) content()
}

// Search Pager Composable
/**
 * 可展开的搜索页容器。
 *
 * 折叠态显示传入的 `expandBar`；展开态把搜索栏顶到状态栏下方，并用一层
 * 不透明度渐变的遮罩盖住原页面。展开过程中额外挂空的 tap/drag 手势，
 * 用来吞掉背景交互，防止用户在搜索页里误触下层内容。
 * 取消按钮与系统返回都统一走 `COLLAPSING` 状态，保证退出动画只有一条路径。
 *
 * @param onSearchStatusChange 状态机回调，负责推进展开/折叠/结果状态
 * @param defaultResult 未输入关键字时的默认内容
 * @param expandBar 折叠态搜索栏，默认是 [SearchBar]
 * @param searchBarTopPadding 搜索栏自身的内边距
 * @param result 搜索结果为 SHOW 时展示的内容
 */
@Composable
fun SearchStatus.SearchPager(
    onSearchStatusChange: (SearchStatus) -> Unit,
    defaultResult: @Composable () -> Unit,
    expandBar: @Composable (SearchStatus, (SearchStatus) -> Unit, Dp) -> Unit = { searchStatus, onStatusChange, padding ->
        SearchBar(searchStatus, onStatusChange, padding)
    },
    searchBarTopPadding: Dp = 12.dp,
    result: @Composable () -> Unit
) {
    val searchStatus = this
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    // 折叠时跟随拖动偏移，展开时吸附到状态栏下方；动画结束回调用于把状态机推进到下一态
    val topPadding by animateDpAsState(
        targetValue = if (searchStatus.shouldExpand()) {
            systemBarsPadding + 5.dp
        } else {
            max(searchStatus.offsetY, 0.dp)
        },
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "SearchPagerTopPadding"
    ) {
        onSearchStatusChange(searchStatus.onAnimationComplete())
    }
    // 遮罩只改透明度，配合 drawBehind 绘制，不参与布局也不触发子内容重组
    val surfaceAlpha by animateFloatAsState(
        if (searchStatus.shouldExpand()) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "SearchPagerSurfaceAlpha"
    )
    val surfaceColor = colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(5f)
            .drawBehind { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) }
            .semantics { onClick { false } }
            .then(
                if (!searchStatus.isCollapsed()) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        }
                        .draggable(
                            state = rememberDraggableState { },
                            orientation = Orientation.Horizontal,
                        )
                } else Modifier
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topPadding)
                .then(
                    if (!searchStatus.isCollapsed()) Modifier.background(colorScheme.surface)
                    else Modifier
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!searchStatus.isCollapsed()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(colorScheme.surface)
                ) {
                    expandBar(searchStatus, onSearchStatusChange, searchBarTopPadding)
                }
            }
            AnimatedVisibility(
                visible = searchStatus.isExpand() || searchStatus.isAnimatingExpand(),
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it })
            ) {
                Text(
                    text = stringResource(android.R.string.cancel),
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 16.dp, top = searchBarTopPadding, bottom = 6.dp)
                        .clickable(
                            interactionSource = null,
                            enabled = searchStatus.isExpand(),
                            indication = null
                        ) {
                            onSearchStatusChange(
                                searchStatus.copy(
                                    searchText = "",
                                    current = SearchStatus.Status.COLLAPSING
                                )
                            )
                        }
                )
                run {
                    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
                    NavigationBackHandler(
                        state = navEventState,
                        isBackEnabled = true,
                        onBackCompleted = {
                            onSearchStatusChange(
                                searchStatus.copy(
                                    searchText = "",
                                    current = SearchStatus.Status.COLLAPSING
                                )
                            )
                        }
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = searchStatus.isExpand(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (searchStatus.resultStatus) {
                SearchStatus.ResultStatus.DEFAULT -> defaultResult()
                SearchStatus.ResultStatus.EMPTY -> {}
                SearchStatus.ResultStatus.LOAD -> {}
                SearchStatus.ResultStatus.SHOW -> result()
            }
        }
    }
}

/**
 * 展开态下真正可输入的搜索栏。
 *
 * 内部维护一份 [TextFieldValue] 以保留光标/选区；外部状态回写时只有文本不同才
 * 重建，避免每次输入都重置光标到末尾。展开动画开始时自动请求焦点，
 * 但仅在 `isAnimatingExpand` 时执行一次，防止用户主动收起后又被抢回焦点。
 */
@Composable
fun SearchBar(
    searchStatus: SearchStatus,
    onSearchStatusChange: (SearchStatus) -> Unit,
    searchBarTopPadding: Dp = 12.dp,
) {
    val focusRequester = remember { FocusRequester() }
    // 保留 TextFieldValue 而非纯 String，输入法组合期间的光标/选区才不会丢
    var textFieldValue by remember { mutableStateOf(TextFieldValue(searchStatus.searchText)) }

    // 外部状态变化时同步文本；相等性判断避免用户输入过程中光标被强行移到末尾
    LaunchedEffect(searchStatus.searchText) {
        if (textFieldValue.text != searchStatus.searchText) {
            textFieldValue = TextFieldValue(searchStatus.searchText)
        }
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
            onSearchStatusChange(searchStatus.copy(searchText = it.text))
        },
        singleLine = true,
        textStyle = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            color = colorScheme.onSurface
        ),
        cursorBrush = SolidColor(colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 6.dp)
            .heightIn(min = 45.dp)
            .background(colorScheme.surfaceContainerHigh, CircleShape)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = "search",
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 16.dp, end = 8.dp),
                    tint = colorScheme.onSurfaceContainerHigh,
                )
                Box(modifier = Modifier.weight(1f)) {
                    innerTextField()
                }
                AnimatedVisibility(
                    searchStatus.searchText.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Basic.SearchCleanup,
                        tint = colorScheme.onSurface,
                        contentDescription = "Clean",
                        modifier = Modifier
                            .size(44.dp)
                            .padding(start = 8.dp, end = 16.dp)
                            .clickable(
                                interactionSource = null,
                                indication = null
                            ) {
                                textFieldValue = TextFieldValue("")
                                onSearchStatusChange(searchStatus.copy(searchText = ""))
                            },
                    )
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        if (searchStatus.isAnimatingExpand()) {
            focusRequester.requestFocus()
        }
    }
}

/**
 * 不可交互的搜索栏占位。
 *
 * 复用真实搜索栏的外观（图标、内边距、圆角），但禁用输入并清空回调，
 * 用于折叠态首屏展示，避免在不需要搜索时初始化输入法/焦点相关逻辑。
 */
@Composable
fun SearchBarFake(
    label: String,
    searchBarTopPadding: Dp = 12.dp,
) {
    InputField(
        query = "",
        onQueryChange = { },
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = "Clean",
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 6.dp),
        onSearch = { },
        enabled = false,
        expanded = false,
        onExpandedChange = { }
    )
}
