package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 警告/提示信息卡片。
 *
 * 颜色由 [WarningLevel] 与主题模式共同决定：动态取色时走主题容器色，
 * 否则用内置的低饱和警示色，避免在非动态取色设备上退化成纯红/纯黄而显得刺眼。
 * 只有传入 `onClick` 时才开启按压反馈，纯展示卡片不应产生“可点击”的暗示。
 *
 * @param message 警告正文
 * @param level 严重程度，决定配色
 * @param onClick 点击回调；为 null 时卡片不可交互
 * @param action 右侧附加操作区（例如“详情”按钮）
 */
@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    level: WarningLevel = WarningLevel.Error,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        onClick = { onClick?.invoke() },
        colors = CardDefaults.defaultColors(
            color = level.containerColor(),
            contentColor = level.contentColor(),
        ),
        showIndication = onClick != null,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                fontSize = 14.sp
            )
            action?.invoke()
        }
    }
}

/**
 * 计算警告卡片背景色。
 *
 * 优先使用动态取色的容器色；未启用动态取色时按明暗主题给出手调的低饱和底色，
 * 这样文字仍能保持较高对比度，同时不抢走页面主色。
 */
@Composable
private fun WarningLevel.containerColor(): Color = when {
    isDynamicColor -> when (this) {
        WarningLevel.Error -> colorScheme.errorContainer
        WarningLevel.Notice -> colorScheme.tertiaryContainer
    }

    isInDarkTheme() -> when (this) {
        WarningLevel.Error -> Color(0xFF310808)
        WarningLevel.Notice -> Color(0xFF3E2F1B)
    }

    else -> when (this) {
        WarningLevel.Error -> Color(0xFFF8E2E2)
        WarningLevel.Notice -> Color(0xFFFFF0DB)
    }
}

/**
 * 计算警告卡片前景色。
 *
 * 动态取色时用对应的 on* 容器色保证可读性；否则使用高饱和的红/橙，
 * 在浅色底上足够醒目。注意这里不区分深浅主题，因为手调的背景已足够深/浅。
 */
@Composable
private fun WarningLevel.contentColor(): Color = when {
    isDynamicColor -> when (this) {
        WarningLevel.Error -> colorScheme.onErrorContainer
        WarningLevel.Notice -> colorScheme.onTertiaryContainer
    }

    else -> when (this) {
        WarningLevel.Error -> Color(0xFFF72727)
        WarningLevel.Notice -> Color(0xFFF5A623)
    }
}
