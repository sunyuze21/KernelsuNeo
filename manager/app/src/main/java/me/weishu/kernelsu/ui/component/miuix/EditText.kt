package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import kotlin.math.max

/**
 * 左侧标题/摘要、右侧输入框的设置项。
 *
 * 右侧输入框强制右对齐，与设置页的“值在右”阅读习惯一致；未输入时用提示文本占位。
 * 点击整行只做焦点请求与视觉反馈，不消费输入事件，避免与文本选择冲突。
 * 内部用自定义 [Layout] 而非 Row：左右两栏需要按各自内容测量，
 * 且标题与摘要要保持左对齐成组居中，Row 无法在“右栏占满剩余宽度”的同时做到这一点。
 *
 * @param title 主标题
 * @param value 当前输入值
 * @param summary 标题下方的补充说明
 * @param textHint 输入为空时的占位文本
 * @param isError 错误态：输入文字改为半透明红色，深浅主题用不同 alpha 保证可读
 */
@Composable
fun EditText(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onValueChange: (String) -> Unit = {},
    summary: String? = null,
    textHint: String = "",
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    titleColor: BasicComponentColors = EditTextDefaults.titleColor(),
    summaryColor: BasicComponentColors = EditTextDefaults.summaryColor(),
    rightActionColor: BasicComponentColors = EditTextDefaults.rightActionColors(),
    isError: Boolean = false,
) {
    // 行点击、输入框与焦点反馈共享同一个状态源，点击整行的焦点高亮才能正确显示
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()
    // 行点击与输入框共享 FocusRequester，点标题区域也能唤起键盘
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = null
            ) {
                if (enabled) {
                    coroutineScope.launch {
                        interactionSource.emit(FocusInteraction.Focus())
                    }
                }
            }
            .heightIn(min = 56.dp)
            .fillMaxWidth()
            .padding(EditTextDefaults.InsideMargin),
    ) {
        Layout(
            content = {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = titleColor.color(enabled)
                )
                summary?.let {
                    Text(
                        text = it,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = summaryColor.color(enabled)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        onValueChange(it)
                    },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .semantics {
                            onClick {
                                focusRequester.requestFocus()
                                true
                            }
                        },
                    enabled = enabled,
                    textStyle = MiuixTheme.textStyles.main.copy(
                        textAlign = TextAlign.End,
                        color = if (isError) {
                            Color.Red.copy(alpha = if (isSystemInDarkTheme()) 0.3f else 0.6f)
                        } else {
                            rightActionColor.color(enabled)
                        }
                    ),
                    keyboardOptions = keyboardOptions,
                    cursorBrush = SolidColor(colorScheme.primary),
                    interactionSource = interactionSource,
                    decorationBox =
                        @Composable { innerTextField ->
                            Box(
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = if (value.isEmpty()) textHint else "",
                                    color = rightActionColor.color(enabled),
                                    textAlign = TextAlign.End,
                                    softWrap = false,
                                    maxLines = 1
                                )
                                innerTextField()
                            }
                        }
                )
            }
        ) { measurables, constraints ->
            // 左栏最多占一半宽度，避免长标题把输入区挤到不可用
            val leftConstraints = constraints.copy(maxWidth = constraints.maxWidth / 2)
            val hasSummary = measurables.size > 2
            val titleText = measurables[0].measure(leftConstraints)
            val summaryText = (if (hasSummary) measurables[1] else null)?.measure(leftConstraints)
            val leftWidth = max(titleText.width, (summaryText?.width ?: 0))
            val leftHeight = titleText.height + (summaryText?.height ?: 0)
            // 中间固定 16.dp 间距，剩余宽度全给输入框，保证值尽量右对齐且不被截断
            val rightWidth = constraints.maxWidth - leftWidth - 16.dp.roundToPx()
            val rightConstraints = constraints.copy(maxWidth = rightWidth)
            val inputField = (if (hasSummary) measurables[2] else measurables[1]).measure(rightConstraints)
            val totalHeight = max(leftHeight, inputField.height)
            layout(constraints.maxWidth, totalHeight) {
                val titleY = (totalHeight - leftHeight) / 2
                titleText.placeRelative(0, titleY)
                summaryText?.placeRelative(0, titleY + titleText.height)
                inputField.placeRelative(constraints.maxWidth - inputField.width, (totalHeight - inputField.height) / 2)
            }
        }
    }
}

/** [EditText] 的默认尺寸与配色；抽成 object 便于调用方按需覆盖单个 token */
object EditTextDefaults {
    /** 行内统一内边距，同时被列表项高度与自定义测量复用 */
    val InsideMargin = PaddingValues(16.dp)

    /** 主标题配色：正常态用 onSurface，禁用态降级到 disabled 色 */
    @Composable
    fun titleColor(
        color: Color = colorScheme.onSurface,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }

    /** 摘要配色：比标题弱一级，使用 onSurfaceVariantSummary */
    @Composable
    fun summaryColor(
        color: Color = colorScheme.onSurfaceVariantSummary,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }

    /** 右侧值/提示文本配色，与摘要同属次级信息层级 */
    @Composable
    fun rightActionColors(
        color: Color = colorScheme.onSurfaceVariantActions,
        disabledColor: Color = colorScheme.disabledOnSecondaryVariant,
    ): BasicComponentColors {
        return BasicComponentColors(
            color = color,
            disabledColor = disabledColor
        )
    }
}

/**
 * 一组“正常/禁用”颜色。
 *
 * 用 @Immutable 声明：字段全是 val，Compose 可跳过对持有该对象的重组；
 * 通过 [color] 按 enabled 统一取色，避免各调用点重复写三元表达式。
 */
@Immutable
class BasicComponentColors(
    private val color: Color,
    private val disabledColor: Color
) {
    /** 按启用状态返回对应颜色；@Stable 表明相同入参总是得到相同结果 */
    @Stable
    fun color(enabled: Boolean): Color = if (enabled) color else disabledColor
}
