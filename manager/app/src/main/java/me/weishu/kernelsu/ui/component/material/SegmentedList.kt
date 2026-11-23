package me.weishu.kernelsu.ui.component.material

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 由分段容器向下传递的形状；为 null 表示调用方不在分组内，需要自行回退默认形状 */
val LocalListItemShapes = compositionLocalOf<ListItemShapes?> { null }
// 组首/尾用 16.dp 大圆角，视觉上把多条列表“拼”成一整张卡片
private val SegmentedOuterRadius = 16.dp
// 中间项只留 4.dp，与大圆角形成对比，同时避免相邻项之间出现明显缝隙
private val SegmentedInnerRadius = 4.dp
// 弹簧刚度偏硬，保证显隐动画干脆收尾
private const val SegmentedSpringStiffness = 800f
// 阻尼接近临界值（0.9），轻微过冲但不产生可见回弹抖动
private const val SegmentedSpringDamping = 0.9f

/** DSL 标记：限制 SegmentedColumnScope 的成员只能在最内层作用域调用，防止嵌套作用域串用 */
@DslMarker
annotation class SegmentedColumnDsl

/**
 * 分段列表的默认配色。
 *
 * 禁用态容器色刻意与正常态保持一致，只靠内容色降级来表达不可用，
 * 否则整块卡片变灰会破坏分组的一体感。
 */
@Composable
private fun defaultSegmentedColors(): ListItemColors = ListItemDefaults.segmentedColors(
    containerColor = colorScheme.surfaceBright,
    disabledContainerColor = colorScheme.surfaceBright,
    supportingContentColor = colorScheme.onSurfaceVariant
)

/**
 * 计算“孤立一项”的分段形状。
 *
 * 仅一项时四个角都应是大圆角，直接复用 `shapes.large`；
 * 多项时交给 [ListItemDefaults.segmentedShapes] 按 index/count 区分首尾。
 */
@Composable
private fun defaultSingleSegmentedShape(index: Int, count: Int): ListItemShapes {
    val base = ListItemDefaults.segmentedShapes(index, count)
    return if (count == 1) {
        base.copy(shape = MaterialTheme.shapes.large)
    } else {
        base
    }
}

/**
 * 静态列表版的分段容器。
 *
 * 调用方一次性传入已构造好的 Composable 列表，这里只负责按顺序套上
 * 分组形状后纵向排列，不涉及显隐动画。
 *
 * @param visibleLen 实际可见的条目数；当部分条目被条件隐藏时用它保持
 *   首尾圆角按完整数量计算。传 0 或负值则回退为 `content.size`，
 *   否则尾部圆角会落在最后一个渲染项上，看起来像少画了半组。
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    visibleLen: Int = 0,
    content: List<@Composable () -> Unit>,
) {
    if (content.isEmpty()) return

    Column(modifier = modifier) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        // 2.dp 间距与 ListItemDefaults.SegmentedGap 对齐，保证静态版与动画版视觉一致
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content.forEachIndexed { index, itemContent ->
                CompositionLocalProvider(
                    LocalListItemShapes provides defaultSingleSegmentedShape(
                        index = index,
                        count = if (visibleLen > 0) visibleLen else content.size
                    ),
                ) {
                    itemContent()
                }
            }
        }
    }
}

/**
 * [SegmentedColumn] 的 DSL 作用域。
 *
 * 只负责收集条目而不立即组合，父容器因此可以先统计可见项、再统一计算
 * 形状与动画，避免边组合边测量导致的布局抖动。
 */
@SegmentedColumnDsl
class SegmentedColumnScope {
    /** 单个条目：key 用于跨重组稳定动画状态，visible 控制展开/收起 */
    internal data class Entry(
        val key: Any?,
        val visible: Boolean,
        val content: @Composable () -> Unit,
    )

    /** 条目按插入顺序保存，插入下标同时作为未指定 key 时的默认标识 */
    internal val entries = mutableListOf<Entry>()

    /**
     * 追加一个条目。
     *
     * key 为空时退化为插入下标；content 延迟到父容器组合时才执行，
     * 所以这里可以安全地依据外部状态决定 visible。
     */
    fun item(
        key: Any? = null,
        visible: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        entries.add(Entry(key ?: entries.size, visible, content))
    }
}

/**
 * DSL 版分段容器，支持条目显隐动画。
 *
 * 显隐由两套机制协同完成：
 * 1. 每个条目维护独立的 0f..1f 进度，驱动 `graphicsLayer` 的矩形裁剪与透明度；
 * 2. 自定义 [Layout] 只按“进度折算后的高度”堆叠，使下方条目平滑上移。
 *
 * 圆角与顶部间距跟着首尾可见项变化并做弹簧插值，因此增删条目时整组卡片
 * 是连续变形而非瞬间重排；代价是每个条目都会产生一次动画帧回调，
 * 列表很长时应避免把大量条目放进同一个分组。
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    content: SegmentedColumnScope.() -> Unit,
) {
    val entries = SegmentedColumnScope().apply(content).entries
    if (entries.isEmpty()) return

    Column(modifier = modifier) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }

        // Float 与 Dp 的 spring 泛型不同，必须各自实例化一份
        val floatSpring = spring<Float>(SegmentedSpringDamping, SegmentedSpringStiffness)
        val dpSpring = spring<Dp>(SegmentedSpringDamping, SegmentedSpringStiffness)

        // 用 key 绑定动画状态；条目增删后即使下标变化，进度也不会错位到别的条目
        val progresses = entries.mapIndexed { index, entry ->
            key(entry.key ?: index) {
                animateFloatAsState(
                    targetValue = if (entry.visible) 1f else 0f,
                    animationSpec = floatSpring,
                    label = "SegmentedProgress"
                )
            }
        }

        // 首尾可见项决定大圆角与零间距的归属；全部隐藏时退回首尾下标，避免 -1 参与插值
        val firstVisible = entries.indexOfFirst { it.visible }
        val lastVisible = entries.indexOfLast { it.visible }

        Layout(
            content = {
                entries.forEachIndexed { index, entry ->
                    key(entry.key ?: index) {
                        val isFirst = if (firstVisible == -1) index == 0 else index == firstVisible
                        val isLast = if (lastVisible == -1) index == entries.lastIndex else index == lastVisible

                        // 只有首项保留大圆角+零间距，其余项收成小圆角并让出 SegmentedGap
                        val topRadius by animateDpAsState(
                            if (isFirst) SegmentedOuterRadius else SegmentedInnerRadius,
                            dpSpring, label = "SegmentedTopRadius"
                        )
                        val bottomRadius by animateDpAsState(
                            if (isLast) SegmentedOuterRadius else SegmentedInnerRadius,
                            dpSpring, label = "SegmentedBottomRadius"
                        )
                        val gap by animateDpAsState(
                            if (isFirst) 0.dp else ListItemDefaults.SegmentedGap,
                            dpSpring, label = "SegmentedGap"
                        )

                        val shape = RoundedCornerShape(
                            topStart = topRadius, topEnd = topRadius,
                            bottomStart = bottomRadius, bottomEnd = bottomRadius
                        )

                        Box(
                            modifier = Modifier
                                .zIndex(if (entry.visible) (entries.size - index).toFloat() else -index.toFloat())
                                .graphicsLayer {
                                    val progress = progresses[index].value.coerceAtLeast(0f)
                                    // 用矩形裁剪模拟高度展开：进度增长时内容自上而下“露出”
                                    clip = true
                                    this.shape = object : Shape {
                                        // 用“高度按进度缩放”的矩形做裁剪，等价于纵向展开动画
                                        override fun createOutline(
                                            size: Size,
                                            layoutDirection: LayoutDirection,
                                            density: Density,
                                        ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height * progress))
                                    }
                                    // 透明度乘 1.5 提前饱和（progress≈0.67 即全不透明），避免展开初期内容发灰
                                    alpha = (progress * 1.5f).coerceIn(0f, 1f)
                                }
                        ) {
                            CompositionLocalProvider(
                                LocalListItemShapes provides ListItemDefaults.segmentedShapes(0, 1).copy(shape = shape)
                            ) {
                                Column(modifier = Modifier.padding(top = gap)) {
                                    entry.content()
                                }
                            }
                        }
                    }
                }
            }
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            val positions = IntArray(placeables.size)
            // 按进度累加高度：隐藏项贡献 0，因此不占位；整组总高可能为 0，需兜底
            var y = 0f
            placeables.forEachIndexed { index, placeable ->
                positions[index] = y.roundToInt()
                y += placeable.height * progresses[index].value.coerceAtLeast(0f)
            }
            layout(constraints.maxWidth, y.roundToInt().coerceAtLeast(0)) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(0, positions[index])
                }
            }
        }
    }
}

/**
 * 静态列表中的单个分段项。
 *
 * 按 index/count 计算自身应得的圆角；适合不参与显隐动画、
 * 但仍需要与相邻项拼成一组的场景。
 */
@Composable
fun SegmentedItem(
    index: Int,
    count: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalListItemShapes provides defaultSingleSegmentedShape(index, count),
    ) {
        content()
    }
}

/**
 * 分段项的背景外壳。
 *
 * 形状来自 [LocalListItemShapes]，取不到时退回单段默认形状；
 * 底色固定为 `surfaceBright`，与 [defaultSegmentedColors] 保持一致，
 * 保证外壳与内部 ListItem 不会出现色差。
 */
@Composable
fun SegmentedItemContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colorScheme.surfaceBright,
        shape = shapes.shape,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * 可点击的分段列表项。
 *
 * 即使调用方没传 onClick 也传入空 lambda：Material3 的该重载据此启用
 * 点击语义与按压反馈，若传 null 整行会失去可聚焦性，视觉上也点不动。
 */
@Composable
fun SegmentedListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick ?: {},
        onLongClick = onLongClick,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
        modifier = modifier,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        content = headlineContent
    )
}

/**
 * 带多选语义的分段列表项。
 *
 * 勾选由整行承载点击，`onLongClick` 只是附加操作，不参与选中判定；
 * 适合“开关列表”这类需要 TalkBack 播报 checked 状态的场景。
 */
@Composable
fun SegmentedListItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        onLongClick = onLongClick,
        content = headlineContent
    )
}

/**
 * 带“选中”语义的分段列表项。
 *
 * 与 checked 版本的区别在无障碍语义：这里暴露的是 selected，
 * 用于一组互斥选项（如当前生效的配置项），点击必然伴随选中态切换。
 */
@Composable
fun SegmentedListItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        verticalAlignment = Alignment.CenterVertically,
        onLongClick = onLongClick,
        content = headlineContent
    )
}

/**
 * 右侧带开关的分段列表项。
 *
 * 开关自身 `onCheckedChange = null`，点击完全交给整行处理，避免一次点击
 * 触发两次回调；两者共用同一个 [MutableInteractionSource]，行高亮与
 * 开关滑块的手势状态才能保持一致。
 */
@Composable
fun SegmentedSwitchItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    SegmentedListItem(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, title) } },
        trailingContent = {
            ExpressiveSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

/**
 * 右侧显示当前值、点击弹出下拉菜单的分段列表项。
 *
 * 菜单锚点取自手指按下的位置（[trackPressPosition]），因此气泡会贴着触点
 * 展开而不是固定从行首弹出。`safeIndex` 对越界下标做了钳制，`items`
 * 为空时用 -1 作哨兵，防止数据刷新瞬间读取到非法下标。
 */
@Composable
fun SegmentedDropdownItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    items: List<String>,
    colors: ListItemColors = defaultSegmentedColors(),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    // 记录按下的触点，供 OffsetAnchoredExpressiveMenu 定位气泡
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }

    // items 为空时不能访问 items[safeIndex]，这里用 -1 表示“无有效选中项”
    val hasItems = items.isNotEmpty()
    val safeIndex = if (hasItems) {
        selectedIndex.coerceIn(0, items.lastIndex)
    } else {
        -1
    }

    Box(modifier = Modifier.trackPressPosition { anchorOffset = it.round() }) {
        SegmentedListItem(
            onClick = if (enabled) {
                {
                    onClick?.invoke()
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    expanded = true
                }
            } else null,
            enabled = enabled,
            colors = colors,
            leadingContent = icon?.let { { Icon(it, title) } },
            headlineContent = { Text(text = title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                Text(
                    text = if (hasItems && safeIndex >= 0) items[safeIndex] else "",
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(0.3f),
                    color = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
        )
        OffsetAnchoredExpressiveMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorOffset = anchorOffset,
        ) {
            items.forEachIndexed { index, text ->
                SelectableDropdownMenuItem(
                    text = { Text(text) },
                    selected = index == safeIndex,
                    onClick = {
                        if (index in items.indices) {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onItemSelected(index)
                        }
                        expanded = false
                    },
                    shapes = MenuDefaults.itemShape(index = index, count = items.size),
                    selectedLeadingIcon = {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                )
            }
        }
    }
}

/**
 * 左侧带单选按钮的分段列表项。
 *
 * RadioButton 自身不接收点击（`onClick = null`），点击与触感反馈统一由
 * 整行处理，这样点击文字或留白区域也能完成选择，触控热区更大。
 */
@Composable
fun SegmentedRadioItem(
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    SegmentedListItem(
        selected = selected,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        enabled = enabled,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

/**
 * 左侧带复选框的分段列表项。
 *
 * 复选框与整行共用 [MutableInteractionSource]，点击任一处都只回调一次
 * `onCheckedChange`，同时按压高亮会同时出现在行背景与选框上，
 * 不会出现“点了选框但行没反应”的割裂感。
 */
@Composable
fun SegmentedCheckboxItem(
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    SegmentedListItem(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onCheckedChange(it)
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        headlineContent = { Text(title) },
        leadingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
                modifier = Modifier.size(24.dp)
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

/**
 * 内嵌输入框的分段列表项。
 *
 * 点击整行请求焦点；输入框获得焦点后通过 [BringIntoViewRequester] 把自身
 * 滚入可视区，避免被弹出的输入法遮住。文字样式强制对齐 bodyMedium 的
 * 字号/行高，保证多行输入时行距仍与支持文本一致；占位符使用
 * supportingContentColor，防止默认前景色在浅色卡片上对比度不足。
 */
@Composable
fun SegmentedTextField(
    modifier: Modifier = Modifier,
    label: String = "",
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    colors: ListItemColors = defaultSegmentedColors(),
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(colorScheme.primary),
    placeholder: @Composable (() -> Unit)? = { Text("-") },
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    isError: Boolean = false
) {
    // 焦点落到输入框时把整行滚进可视区，输入法弹出后仍能看到正在编辑的内容
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    // 行点击与输入框共用同一个 FocusRequester，点行内空白也能聚焦
    val focusRequester = remember { FocusRequester() }

    SegmentedListItem(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .focusRequester(focusRequester),
        colors = colors,
        onClick = { focusRequester.requestFocus() },
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        headlineContent = {
            Column {
                if (label.isNotEmpty()) {
                    Text(text = label, color = if (isError) colorScheme.error else colors.contentColor)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    enabled = enabled,
                    readOnly = readOnly,
                    textStyle = textStyle.copy(
                        colors.supportingContentColor,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    ),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    minLines = minLines,
                    visualTransformation = visualTransformation,
                    onTextLayout = onTextLayout,
                    interactionSource = interactionSource,
                    cursorBrush = cursorBrush,
                    decorationBox = { innerTextField ->
                        if (value.isEmpty() && placeholder != null) {
                            Box(contentAlignment = Alignment.CenterStart) {
                                CompositionLocalProvider(
                                    LocalContentColor provides colors.supportingContentColor
                                ) {
                                    ProvideTextStyle(value = MaterialTheme.typography.bodyMedium) {
                                        placeholder()
                                    }
                                }
                            }
                        }
                        innerTextField()
                    }
                )
            }
        }
    )
}
