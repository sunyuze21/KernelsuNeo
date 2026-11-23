package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

/** shell 词法单元类型，用于决定高亮颜色。 */
enum class ShellTokenKind { COMMENT, STRING, VARIABLE, KEYWORD }

/** 源码区间 [start, end) 及其高亮类型，区间之间不重叠且按出现顺序排列。 */
data class ShellToken(val kind: ShellTokenKind, val start: Int, val end: Int)

/**
 * 代码高亮配色。
 *
 * 颜色是固定值而非主题强调色，保证代码片段在任意主题下都可读。
 */
@Immutable
data class ShellHighlightColors(
    val comment: Color,
    val string: Color,
    val variable: Color,
    val keyword: Color,
) {
    // 预置的浅色/深色两套配色及选择逻辑。
    companion object {
        // 浅色背景配色（GitHub Light 风格）。
        private val light = ShellHighlightColors(
            comment = Color(0xFF6A737D),
            string = Color(0xFF032F62),
            variable = Color(0xFFE36209),
            keyword = Color(0xFFD73A49),
        )
        // 深色背景配色（GitHub Dark 风格）。
        private val dark = ShellHighlightColors(
            comment = Color(0xFF8B949E),
            string = Color(0xFFA5D6FF),
            variable = Color(0xFFFFA657),
            keyword = Color(0xFFFF7B72),
        )

        /** Fixed code colors that stay readable on [background], independent of the app accent. */
        // 说明：按背景亮度二选一，不随应用强调色变化，避免代码可读性不可控。
        fun forBackground(background: Color): ShellHighlightColors =
            if (background.luminance() < 0.5f) dark else light
    }
}

// POSIX shell 保留字；只在“命令位置”出现时才高亮，避免误标普通参数。
private val shellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "select", "function", "in", "!", "{", "}",
)

/** Keywords after which the next word is a command again. */
// 说明：这些关键字之后的下一个词重新处于命令位置，需要继续识别关键字/赋值。
private val commandPrefixKeywords = setOf("if", "then", "else", "elif", "do", "while", "until", "!", "{")

// 变量/命令名的首字符规则（简化版 POSIX）。
private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
// 变量/命令名的后续字符规则。
private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9'

/**
 * Lexes POSIX shell source just enough for highlighting: comments, quoted strings, parameter
 * expansions (`$x`, `${x}`, `$(`, `$1`, `$?`, ...), reserved words in command position and the
 * name of `NAME=value` assignments. Tokens are non-overlapping and in source order; variables
 * inside double quotes are emitted between the string pieces around them.
 *
 * 说明：只做高亮所需的轻量词法分析，不验证语法；遇到未闭合的引号会一直
 * 延伸到文本末尾，保证不会越界。
 */
fun shellTokens(src: String): List<ShellToken> {
    val tokens = mutableListOf<ShellToken>()
    var i = 0
    val n = src.length
    // 是否处于命令位置：决定普通单词能否被识别为关键字。
    var commandPosition = true
    // Previous reserved word on this command, to recognise `in` of for / case / select.
    var pendingIn = false
    var wordsSinceFor = 0

    // 从 '$' 起解析参数展开的结束位置；无法识别时返回原位置（不高亮）。
    fun variableEnd(from: Int): Int {
        // src[from] == '$'
        val next = from + 1
        if (next >= n) return next
        val c = src[next]
        return when {
            c == '{' -> {
                val close = src.indexOf('}', next + 1)
                if (close < 0) n else close + 1
            }

            c == '(' -> next + 1
            isNameStart(c) -> {
                var j = next + 1
                while (j < n && isNameChar(src[j])) j++
                j
            }

            c in '0'..'9' || c in "?@#$!*-" -> next + 1
            else -> from
        }
    }

    while (i < n) {
        val c = src[i]
        when {
            c == '\n' -> {
                commandPosition = true
                pendingIn = false
                i++
            }

            c == ' ' || c == '\t' -> i++

            c == '\\' -> {
                i += 2
                commandPosition = false
            }

            c == '#' -> {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                tokens += ShellToken(ShellTokenKind.COMMENT, i, end)
                i = end
            }

            c == ';' || c == '&' || c == '|' || c == '(' || c == ')' -> {
                commandPosition = true
                pendingIn = false
                i++
            }

            c == '\'' -> {
                val close = src.indexOf('\'', i + 1)
                val end = if (close < 0) n else close + 1
                tokens += ShellToken(ShellTokenKind.STRING, i, end)
                i = end
                commandPosition = false
                if (pendingIn) wordsSinceFor++
            }

            c == '"' -> {
                var segmentStart = i
                var j = i + 1
                while (j < n && src[j] != '"') {
                    when {
                        src[j] == '\\' -> j += 2
                        src[j] == '$' -> {
                            val end = variableEnd(j)
                            if (end > j) {
                                if (j > segmentStart) tokens += ShellToken(ShellTokenKind.STRING, segmentStart, j)
                                tokens += ShellToken(ShellTokenKind.VARIABLE, j, end)
                                segmentStart = end
                                j = end
                            } else {
                                j++
                            }
                        }

                        else -> j++
                    }
                }
                val end = minOf(j + 1, n)
                if (end > segmentStart) tokens += ShellToken(ShellTokenKind.STRING, segmentStart, end)
                i = end
                commandPosition = false
                if (pendingIn) wordsSinceFor++
            }

            c == '$' -> {
                val end = variableEnd(i)
                if (end > i) {
                    tokens += ShellToken(ShellTokenKind.VARIABLE, i, end)
                    // `$(` opens a command substitution.
                    commandPosition = src[end - 1] == '('
                    if (pendingIn) wordsSinceFor++
                    i = end
                } else {
                    i++
                    commandPosition = false
                }
            }

            else -> {
                var j = i
                while (j < n && src[j] !in " \t\n;&|()'\"\\$") j++
                if (j == i) j = i + 1
                val word = src.substring(i, j)
                if (commandPosition && word in shellKeywords) {
                    tokens += ShellToken(ShellTokenKind.KEYWORD, i, j)
                    commandPosition = word in commandPrefixKeywords
                    pendingIn = word == "for" || word == "case" || word == "select"
                    wordsSinceFor = 0
                } else if (pendingIn && word == "in" && wordsSinceFor == 1) {
                    tokens += ShellToken(ShellTokenKind.KEYWORD, i, j)
                    pendingIn = false
                    commandPosition = false
                } else {
                    val eq = word.indexOf('=')
                    if (commandPosition && eq > 0 && isNameStart(word[0]) && (1 until eq).all { isNameChar(word[it]) }) {
                        // NAME=value keeps command position: `A=1 B=2 cmd`.
                        tokens += ShellToken(ShellTokenKind.VARIABLE, i, i + eq)
                    } else {
                        commandPosition = false
                    }
                    if (pendingIn) wordsSinceFor++
                }
                i = j
            }
        }
    }
    return tokens
}

/** 把 shell 源码转成带颜色的 [AnnotatedString]，供 Compose Text 渲染。 */
fun highlightShell(src: String, colors: ShellHighlightColors): AnnotatedString {
    val builder = AnnotatedString.Builder(src)
    for (token in shellTokens(src)) {
        val style = when (token.kind) {
            ShellTokenKind.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
            ShellTokenKind.STRING -> SpanStyle(color = colors.string)
            ShellTokenKind.VARIABLE -> SpanStyle(color = colors.variable)
            ShellTokenKind.KEYWORD -> SpanStyle(color = colors.keyword)
        }
        builder.addStyle(style, token.start, token.end)
    }
    return builder.toAnnotatedString()
}

/** 记忆化高亮结果；只有源码或配色变化时才重新做词法分析。 */
@Composable
fun rememberShellHighlight(src: String, colors: ShellHighlightColors): AnnotatedString =
    remember(src, colors) { highlightShell(src, colors) }
