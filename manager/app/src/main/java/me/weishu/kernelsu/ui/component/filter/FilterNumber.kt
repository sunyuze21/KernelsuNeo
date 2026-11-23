package me.weishu.kernelsu.ui.component.filter

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 整数输入过滤器：只允许数字与位置正确的负号，并把结果约束在 [minValue]..[maxValue]。
 * 越界字符会被即时丢弃而不是事后校验，从而避免输入框短暂出现非法值。
 */
class FilterNumber(
    private val value: Int,
    private val minValue: Int = Int.MIN_VALUE,
    private val maxValue: Int = Int.MAX_VALUE,
) : BaseFieldFilter(value.toString()) {

    /** 交给通用过滤流程，范围由构造参数决定 */
    override fun onFilter(
        inputTextFieldValue: TextFieldValue,
        lastTextFieldValue: TextFieldValue
    ): TextFieldValue {
        return filterInputNumber(inputTextFieldValue, lastTextFieldValue, minValue, maxValue)
    }

    /**
     * 逐字符重建文本：非法字符直接跳过，数字累计时实时检查范围，
     * 超界则删除刚加入的字符。最后按光标位置重算选区，尽量保持编辑体验。
     */
    private fun filterInputNumber(
        inputTextFieldValue: TextFieldValue,
        lastInputTextFieldValue: TextFieldValue,
        minValue: Int = Int.MIN_VALUE,
        maxValue: Int = Int.MAX_VALUE,
    ): TextFieldValue {
        val inputString = inputTextFieldValue.text
        lastInputTextFieldValue.text

        val newString = StringBuilder()
        // 仅当允许负数时才放行负号
        val supportNegative = minValue < 0
        var isNegative = false

        // 只允许负号在首位，并且只允许一个负号
        if (supportNegative && inputString.isNotEmpty() && inputString.first() == '-') {
            isNegative = true
            newString.append('-')
        }

        for ((i, c) in inputString.withIndex()) {
            if (i == 0 && isNegative) continue // 首字符已经处理
            when (c) {
                in '0'..'9' -> {
                    newString.append(c)
                    // 检查是否超出范围
                    val tempText = newString.toString()
                    // 只在不是单独 '-' 时做判断（因为 '-' toInt 会异常）
                    if (tempText != "-" && tempText.isNotEmpty()) {
                        try {
                            val tempValue = tempText.toInt()
                            if (tempValue !in minValue..maxValue) {
                                // 超界就撤销这个字符，保持输入始终合法
                                newString.deleteCharAt(newString.lastIndex)
                            }
                        } catch (e: NumberFormatException) {
                            // 超出int范围
                            newString.deleteCharAt(newString.lastIndex)
                        }
                    }
                }
                // 忽略其他字符（包括点号）
            }
        }

        val textRange: TextRange
        if (inputTextFieldValue.selection.collapsed) { // 表示的是光标范围
            if (inputTextFieldValue.selection.end != inputTextFieldValue.text.length) { // 光标没有指向末尾
                // 按被过滤掉的字符数修正光标，避免光标跳到错误位置
                var newPosition = inputTextFieldValue.selection.end + (newString.length - inputString.length)
                if (newPosition < 0) {
                    newPosition = inputTextFieldValue.selection.end
                }
                textRange = TextRange(newPosition)
            } else { // 光标指向了末尾
                textRange = TextRange(newString.length)
            }
        } else {
            // 存在选中区间时统一把光标放到末尾，简化选区映射
            textRange = TextRange(newString.length)
        }

        return lastInputTextFieldValue.copy(
            text = newString.toString(),
            selection = textRange
        )
    }
}
