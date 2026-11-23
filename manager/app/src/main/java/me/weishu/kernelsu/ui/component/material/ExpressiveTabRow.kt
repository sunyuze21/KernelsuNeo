package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Expressive 风格的文本标签页。
 *
 * PrimaryTabRow 只负责指示器与容器，这里自行实现每个 tab 的点击区域：
 * 外层 `selectable` 关闭默认 indication，把水波纹限制在内层圆形胶囊上，
 * 这样指示条与点击反馈不会互相干扰。`safeIndex` 做了越界钳制，
 * 防止数据刷新瞬间索引越界导致 TabRow 崩溃。
 *
 * @param selectedTabIndex 当前选中下标
 * @param tabs 标签文本列表
 * @param onTabClick 点击回调，参数为真实下标
 * @param modifier 外部修饰符
 * @param containerColor 标签栏容器色
 */
@Composable
fun ExpressiveTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val safeIndex = selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    PrimaryTabRow(
        selectedTabIndex = safeIndex,
        modifier = modifier,
        containerColor = containerColor,
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = safeIndex == index
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onTabClick(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clip(CircleShape)
                        .indication(interactionSource, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (selected) LocalContentColor.current
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
