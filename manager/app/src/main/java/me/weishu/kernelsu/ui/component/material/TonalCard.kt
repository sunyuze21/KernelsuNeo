package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * 带 tonic 底色的通用卡片。
 *
 * 根据是否传入点击回调切换三种实现：
 * - 有长按回调时必须使用 [combinedClickable]，因此外层补 `clip(shape)`，
 *   避免水波纹溢出圆角；
 * - 只有单击时直接用 Card 自带的 `onClick` 重载，可复用其内部语义与按压反馈；
 * - 都没有时退化为纯容器卡片，不注册任何手势，减少无谓的指针处理开销。
 *
 * @param containerColor 卡片背景色，默认较亮的 `surfaceBright`
 * @param contentColor 卡片内容色，默认由背景色推导
 * @param shape 圆角形状，同时用于裁剪点击区域
 * @param enabled 是否响应点击/长按
 * @param onClick 单击回调，为 null 表示不可点击
 * @param onLongClick 长按回调，为 null 表示不支持长按
 * @param interactionSource 交互状态源，外部可传入以联动其他动效
 * @param content 卡片内容
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceBright,
    contentColor: Color = contentColorFor(containerColor),
    shape: Shape = MaterialTheme.shapes.large,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )
    when {
        onLongClick != null -> Card(
            modifier = modifier
                .clip(shape)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                ),
            colors = colors,
            shape = shape,
        ) { content() }

        onClick != null -> Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            shape = shape,
            interactionSource = interactionSource,
        ) { content() }

        else -> Card(
            modifier = modifier,
            colors = colors,
            shape = shape,
        ) { content() }
    }
}
