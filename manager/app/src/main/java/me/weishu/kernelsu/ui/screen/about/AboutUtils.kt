package me.weishu.kernelsu.ui.screen.about

import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.runtime.Immutable

/**
 * 解析后的一条链接：展示文本与目标 URL 分离，避免把整个 HTML 直接交给 UI。
 */
@Immutable
data class LinkInfo(
    val fullText: String,
    val url: String
)

/**
 * 从包含 <a> 标签的 HTML 字符串中提取链接。
 * 先按换行拆分，再逐行交给 HTML 解析器，最后取出 [URLSpan] 的 URL；
 * 这样同一个链接文案中声明多个 URL 时也能全部保留。
 */
fun extractLinks(html: String): List<LinkInfo> {
    // 资源字符串可能用 <br/>、<br> 或真实换行分隔条目，需要全部兼容。
    val lines = html.split("<br/>", "<br>", "\n")
    val result = mutableListOf<LinkInfo>()

    for (line in lines) {
        val spanned: Spanned = Html.fromHtml(line, Html.FROM_HTML_MODE_LEGACY)
        // URLSpan 中保存的是点击后真正要打开的地址。
        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        val text = spanned.toString().trim()

        for (span in spans) {
            val url = span.url
            result.add(LinkInfo(text, url))
        }
    }
    return result
}

