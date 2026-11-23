package me.weishu.kernelsu.ui.util

import me.weishu.kernelsu.ui.util.ShellTokenKind.COMMENT
import me.weishu.kernelsu.ui.util.ShellTokenKind.KEYWORD
import me.weishu.kernelsu.ui.util.ShellTokenKind.STRING
import me.weishu.kernelsu.ui.util.ShellTokenKind.VARIABLE
import org.junit.Assert.assertEquals
import org.junit.Test

/** 覆盖 shell 词法高亮：注释起始、引号/变量展开与关键字的命令位置判定。 */
class ShellHighlightTest {

    // 把 token 的 [start,end) 区间还原为文本，便于直接断言高亮结果。
    private fun lex(src: String): List<Pair<ShellTokenKind, String>> =
        shellTokens(src).map { it.kind to src.substring(it.start, it.end) }

    // '#' 只有位于词首才是注释；命令中间或变量中的 '#' 不能吞掉后续内容。
    @Test
    fun commentsOnlyStartAWord() {
        assertEquals(
            listOf(COMMENT to "#!/system/bin/sh", COMMENT to "# note"),
            lex("#!/system/bin/sh\necho a#b # note"),
        )
        // $# 是特殊变量（参数个数），不能被识别为注释。
        assertEquals(listOf(VARIABLE to "$#"), lex("echo $#"))
    }

    // 单引号内不展开；双引号内的 ${...}/$1 仍作为变量 token，转义引号属于字符串。
    @Test
    fun stringsAndExpansions() {
        assertEquals(listOf(STRING to "'a \$x'"), lex("echo 'a \$x'"))
        assertEquals(
            listOf(STRING to "\"a ", VARIABLE to "\${B:-c}", STRING to " \\\"", VARIABLE to "\$1", STRING to "\""),
            lex("echo \"a \${B:-c} \\\"\$1\""),
        )
        // $( 开启命令替换，内部仍按关键字规则高亮。
        assertEquals(
            listOf(VARIABLE to "\$(", KEYWORD to "if"),
            lex("x \$(if"),
        )
        assertEquals(listOf(VARIABLE to "\$HOME", VARIABLE to "\$?"), lex("cd \$HOME; echo \$?"))
    }

    // 关键字仅在命令起始位置高亮；作为参数出现时保持普通文本。
    @Test
    fun keywordsOnlyInCommandPosition() {
        assertEquals(
            listOf(KEYWORD to "if", KEYWORD to "then", KEYWORD to "fi"),
            lex("if [ -f x ]; then echo done if; fi"),
        )
        assertEquals(
            listOf(KEYWORD to "for", KEYWORD to "in", KEYWORD to "do", VARIABLE to "\$f", KEYWORD to "done"),
            lex("for f in a b; do echo \$f; done"),
        )
        assertEquals(
            listOf(KEYWORD to "case", STRING to "\"", VARIABLE to "\$x", STRING to "\"", KEYWORD to "in", KEYWORD to "esac"),
            lex("case \"\$x\" in a) echo in ;; esac"),
        )
    }

    // 命令位置上的 NAME=value 中被高亮的是变量名，而不是 value；后续参数里的赋值不再算变量赋值。
    @Test
    fun assignmentsInCommandPosition() {
        assertEquals(
            listOf(VARIABLE to "A", VARIABLE to "B", VARIABLE to "\$A"),
            lex("A=1 B=x echo C=\$A"),
        )
    }
}
