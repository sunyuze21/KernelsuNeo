package me.weishu.kernelsu.ui.util

import android.icu.text.Transliterator

/** 汉字转拼音，用于应用列表的拼音搜索。 */
object PinyinUtil {

    // 懒加载 ICU 转换器：构造有开销，且部分精简 ROM 可能没有对应数据。
    private val transliterator: Transliterator by lazy {
        // Han-Latin 转拼音，Latin-ASCII 去声调，Lower 统一小写。
        Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
    }

    /**
     * 把输入文本转成小写拼音并去掉空格。
     *
     * Transliterator 非线程安全，因此用 @Synchronized 串行化调用。
     */
    @Synchronized
    fun toPinyin(input: String): String =
        transliterator.transliterate(input).replace(" ", "")
}
