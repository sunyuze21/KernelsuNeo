package me.weishu.kernelsu.ui.screen.module

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import me.weishu.kernelsu.ui.util.module.Shortcut
import kotlin.math.roundToInt

/** 模块快捷方式的两种目标：执行动作脚本或打开 WebUI。 */
enum class ShortcutType {
    Action,
    WebUI
}

/** 查询指定模块是否已创建对应类型的桌面快捷方式。 */
fun hasModuleShortcut(context: Context, moduleId: String, type: ShortcutType): Boolean {
    return when (type) {
        ShortcutType.Action -> Shortcut.hasModuleActionShortcut(context, moduleId)
        ShortcutType.WebUI -> Shortcut.hasModuleWebUiShortcut(context, moduleId)
    }
}

/** 删除模块快捷方式；模块卸载或类型切换后需要清理旧快捷方式。 */
fun deleteModuleShortcut(context: Context, moduleId: String, type: ShortcutType) {
    when (type) {
        ShortcutType.Action -> Shortcut.deleteModuleActionShortcut(context, moduleId)
        ShortcutType.WebUI -> Shortcut.deleteModuleWebUiShortcut(context, moduleId)
    }
}

/** 创建模块快捷方式；iconUri 为空时交给底层选择默认图标。 */
fun createModuleShortcut(
    context: Context,
    moduleId: String,
    name: String,
    iconUri: String?,
    type: ShortcutType
) {
    when (type) {
        ShortcutType.Action -> {
            Shortcut.createModuleActionShortcut(
                context = context,
                moduleId = moduleId,
                name = name,
                iconUri = iconUri
            )
        }

        ShortcutType.WebUI -> {
            Shortcut.createModuleWebUiShortcut(
                context = context,
                moduleId = moduleId,
                name = name,
                iconUri = iconUri
            )
        }
    }
}

/** 描述文本的两种测量槽位；分别用于折叠态与展开态的测量。 */
private enum class DescriptionSlot {
    Collapsed,
    Expanded
}

/**
 * 可展开的描述文本。
 *
 * 内部用同一段文本测量折叠/展开两种高度，再按动画进度插值高度，
 * 因此展开动画期间不会出现布局跳变；是否可展开由测量结果回传给调用方。
 */
@Composable
fun ExpandableDescriptionText(
    text: String,
    expanded: Boolean,
    maxLinesLimit: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    onCanExpandChange: (Boolean) -> Unit = {},
) {
    // 动画进度锁定在没有重组开销的对象里；重置时以 text 为 key，避免换文本后残留旧进度
    val progress = remember(text) { Animatable(if (expanded) 1f else 0f) }
    var canExpand by remember { mutableStateOf(true) }

    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        if (progress.targetValue != target) {
            // 展开与收起使用不同曲线，避免收起时显得拖沓
            val spec = if (expanded) {
                tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
            } else {
                tween<Float>(durationMillis = 320, easing = EaseInOutCubic)
            }
            progress.animateTo(target, animationSpec = spec)
        }
    }

    // 可展开性来自测量结果，必须回调给调用方以决定是否显示“展开”按钮
    LaunchedEffect(canExpand, onCanExpandChange) {
        onCanExpandChange(canExpand)
    }

    SubcomposeLayout(
        modifier = modifier.clipToBounds()
    ) { constraints ->
        // 折叠态用于确定最小高度，也作为不可展开时的唯一展示内容
        val collapsedPlaceable = subcompose(DescriptionSlot.Collapsed) {
            Text(
                text = text,
                color = color,
                style = style,
                fontSize = fontSize,
                textDecoration = textDecoration,
                maxLines = maxLinesLimit,
                overflow = TextOverflow.Ellipsis
            )
        }.first().measure(constraints)

        // 展开态不限行数，仅用于测量完整高度
        val expandedPlaceable = subcompose(DescriptionSlot.Expanded) {
            Text(
                text = text,
                color = color,
                style = style,
                fontSize = fontSize,
                textDecoration = textDecoration,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip
            )
        }.first().measure(constraints)

        val collapsedHeight = collapsedPlaceable.height
        val expandedHeight = expandedPlaceable.height
        val canExpandNow = expandedHeight > collapsedHeight
        // 在布局阶段写入状态会触发下一帧重组，这里仅在值变化时写入以免死循环
        if (canExpand != canExpandNow) {
            canExpand = canExpandNow
        }

        if (!canExpandNow) {
            val width = collapsedPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = collapsedHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                collapsedPlaceable.place(0, 0)
            }
        } else {
            val currentProgress = progress.value
            // 高度在折叠与展开之间线性插值，保证父容器裁剪时不闪烁
            val currentHeight = (collapsedHeight + (expandedHeight - collapsedHeight) * currentProgress)
                .roundToInt()
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            // 进度为 0 时优先放折叠文本，避免展开文本在收起瞬间先出现一次
            val placeableToUse = if (currentProgress == 0f) collapsedPlaceable else expandedPlaceable
            val width = placeableToUse.width.coerceIn(constraints.minWidth, constraints.maxWidth)

            layout(width, currentHeight) {
                placeableToUse.place(0, 0)
            }
        }
    }
}
