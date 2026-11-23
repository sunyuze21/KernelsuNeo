package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Material3 Expressive 风格的切换按钮。
 *
 * 仅做参数透传，不额外包裹布局：形状由调用方按“是否选中”传入不同的 `shapes`，
 * 这样按压/选中时才能产生形状形变，而不会因中间层重组导致形变动画被重置。
 *
 * @param checked 当前是否处于选中态
 * @param onCheckedChange 选中态变化回调
 * @param shapes 选中/未选中对应的形状集合，用于表达形状动画
 * @param modifier 外部修饰符
 * @param enabled 是否可交互，禁用时颜色由 [ToggleButtonColors] 降级
 * @param colors 颜色 token，默认取 [expressiveToggleButtonColors]
 * @param content 按钮内容，作用域为 RowScope 以便排布图标与文字
 */
@Composable
fun ExpressiveToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ToggleButtonColors = expressiveToggleButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        shapes = shapes,
        content = content,
    )
}

/**
 * 构造切换按钮的默认配色。
 *
 * 选中态使用 primary 以强调“已开启”，未选中态回退到
 * `surfaceContainerHighest`，与卡片类容器保持同一层级的中性底色。
 */
@Composable
fun expressiveToggleButtonColors(
    checkedContainerColor: Color = MaterialTheme.colorScheme.primary,
    checkedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
): ToggleButtonColors = ToggleButtonDefaults.colors(
    checkedContainerColor = checkedContainerColor,
    checkedContentColor = checkedContentColor,
    containerColor = containerColor,
    contentColor = contentColor,
)
