package me.weishu.kernelsu.ui.component.filter

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 文本输入过滤器基类：把“过滤逻辑”与 Compose 的 TextFieldValue 状态解耦。
 * 子类只需覆写 [onFilter] 决定接受哪些输入，外部通过 [onValueChange] 获得处理器。
 */
open class BaseFieldFilter() {
    // 内部持有的输入值状态，set/get 均会带上光标位置
    private var inputValue = mutableStateOf(TextFieldValue())

    // 以字符串初始化时把光标置于末尾，符合“继续输入”的直觉
    constructor(value: String) : this() {
        inputValue.value = TextFieldValue(value, TextRange(value.lastIndex + 1))
    }

    /**
     * 过滤入口：返回实际生效的 TextFieldValue。
     * 默认实现丢弃全部输入，子类需覆写。
     */
    protected open fun onFilter(inputTextFieldValue: TextFieldValue, lastTextFieldValue: TextFieldValue): TextFieldValue {
        return TextFieldValue()
    }

    /**
     * 预留的光标位置计算钩子，目前未使用（TODO）。
     */
    protected open fun computePos(): Int {
        // TODO
        return 0
    }

    /**
     * 预留：计算过滤后的新选区，当前返回 null 表示由子类自行处理。
     */
    protected fun getNewTextRange(
        lastTextFiled: TextFieldValue,
        inputTextFieldValue: TextFieldValue
    ): TextRange? {
        return null
    }

    /**
     * 预留：计算过滤后的新文本，当前返回 null 表示由子类自行处理。
     */
    protected fun getNewText(
        lastTextFiled: TextFieldValue,
        inputTextFieldValue: TextFieldValue
    ): TextRange? {

        return null
    }

    /** 外部程序化设置文本（如重置），同时把光标放到末尾 */
    fun setInputValue(value: String) {
        inputValue.value = TextFieldValue(value, TextRange(value.lastIndex + 1))
    }

    /** 读取当前输入值，包含文本与选区 */
    fun getInputValue(): TextFieldValue {
        return inputValue.value
    }

    /**
     * 生成 TextField 的 onValueChange 处理器。
     * 每次输入都经过 [onFilter] 裁决，被拒绝的字符不会进入状态，因此无需额外回滚。
     */
    fun onValueChange(): (TextFieldValue) -> Unit {
        return {
            inputValue.value = onFilter(it, inputValue.value)
        }
    }
}
