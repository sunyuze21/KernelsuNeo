package me.weishu.kernelsu.data.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖 params.json 的字段解析、默认值、类型约束与值校验错误码。 */
class ScriptParamsTest {

    // 契约示例：四种类型齐全，并故意带 futureField 验证未知字段被忽略。
    private val sample = """
        {
          "schemaVersion": 1,
          "params": [
            {
              "key": "ENABLE_FASTBOOT",
              "type": "bool",
              "label": "Also enable fastboot",
              "description": "Optional longer help text",
              "default": true,
              "futureField": 1
            },
            { "key": "MODE", "type": "enum", "label": "Mode", "default": "a",
              "options": [ { "value": "a", "label": "A" }, { "value": "b" } ] },
            { "key": "NAME", "type": "string", "label": "Name", "default": "", "maxLength": 64,
              "required": false },
            { "key": "COUNT", "type": "int", "label": "Count", "default": 1, "min": 0, "max": 10 }
          ]
        }
    """.trimIndent()

    // 把参数片段包进带 schemaVersion 的完整文档，减少非法用例的样板代码。
    private fun parse(params: String) = ScriptParamsParser.parse("""{"schemaVersion":1,"params":[$params]}""")

    // 断言输入整体非法（任何 ScriptParamsException 均视为通过解析失败）。
    private fun assertInvalid(params: String) {
        assertThrows(ScriptParamsException::class.java) { parse(params) }
    }

    // 示例文档应解析出四种参数类型，并生成与类型一致的默认值映射。
    @Test
    fun parsesContractSample() {
        val p = ScriptParamsParser.parse(sample).params
        assertEquals(listOf("ENABLE_FASTBOOT", "MODE", "NAME", "COUNT"), p.map { it.key })

        val bool = p[0] as ScriptParam.Bool
        assertEquals(true, bool.default)
        assertEquals("Also enable fastboot", bool.label)
        assertEquals("Optional longer help text", bool.description)

        val choice = p[1] as ScriptParam.Choice
        assertEquals(listOf("a", "b"), choice.options.map { it.value })
        assertEquals("A", choice.options[0].label)
        // 第二个选项没有 label，应解析为 null 而不是空串。
        assertNull(choice.options[1].label)

        val text = p[2] as ScriptParam.Text
        assertEquals(64, text.maxLength)

        val int = p[3] as ScriptParam.Integer
        assertEquals(1, int.default)
        assertEquals(0, int.min)
        assertEquals(10, int.max)

        assertEquals(
            mapOf("ENABLE_FASTBOOT" to "true", "MODE" to "a", "NAME" to "", "COUNT" to "1"),
            ScriptParamsParser.parse(sample).defaults,
        )
    }

    // 缺省字段的默认值：bool=false、enum=首项、string=""、int=0 或 min。
    @Test
    fun appliesDefaults() {
        val p = parse(
            """
            {"key":"B","type":"bool","label":"b"},
            {"key":"E","type":"enum","label":"e","options":[{"value":"x"},{"value":"y"}]},
            {"key":"S","type":"string","label":"s"},
            {"key":"I","type":"int","label":"i"},
            {"key":"J","type":"int","label":"j","min":5}
            """
        )
        assertEquals(mapOf("B" to "false", "E" to "x", "S" to "", "I" to "0", "J" to "5"), p.defaults)
        // string 未给 maxLength 时使用全局默认；required 缺省为 false。
        assertEquals(ScriptParamsParser.DEFAULT_MAX_LENGTH, (p.params[2] as ScriptParam.Text).maxLength)
        assertEquals(false, (p.params[2] as ScriptParam.Text).required)
    }

    // schemaVersion、顶层结构、参数数量、key 命名与类型都必须符合契约。
    @Test
    fun rejectsSchemaViolations() {
        assertThrows(ScriptParamsException::class.java) { ScriptParamsParser.parse("""{"schemaVersion":2,"params":[]}""") }
        assertThrows(ScriptParamsException::class.java) { ScriptParamsParser.parse("""{"schemaVersion":1}""") }
        assertThrows(ScriptParamsException::class.java) { ScriptParamsParser.parse("not json") }
        assertThrows(ScriptParamsException::class.java) { ScriptParamsParser.parse("[]") }

        // 最多 16 个参数，17 个即拒绝。
        val tooMany = (1..17).joinToString(",") { """{"key":"K$it","type":"bool","label":"x"}""" }
        assertInvalid(tooMany)
        assertEquals(16, parse((1..16).joinToString(",") { """{"key":"K$it","type":"bool","label":"x"}""" }).params.size)

        // key 必须是大写字母开头的 shell 变量名，且总长受限、不能重复。
        assertInvalid("""{"key":"lower","type":"bool","label":"x"}""")
        assertInvalid("""{"key":"1ABC","type":"bool","label":"x"}""")
        assertInvalid("""{"key":"A${"B".repeat(32)}","type":"bool","label":"x"}""")
        assertInvalid("""{"key":"A","type":"bool","label":"x"},{"key":"A","type":"bool","label":"y"}""")
        assertInvalid("""{"key":"A","type":"float","label":"x"}""")
        assertInvalid("""{"key":"A","type":"bool"}""")
    }

    // label/description 必须是非空、去空白后不超长的单语言字符串。
    @Test
    fun validatesText() {
        assertInvalid("""{"key":"A","type":"bool","label":""}""")
        assertInvalid("""{"key":"A","type":"bool","label":"${"x".repeat(201)}"}""")
        assertInvalid("""{"key":"A","type":"bool","label":{"en":"x"}}""")
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[{"value":"a","label":{"en":"A"}}]}""")
        assertInvalid("""{"key":"A","type":"bool","label":1}""")
        assertInvalid("""{"key":"A","type":"bool","label":"x","description":""}""")
        // Trimmed before the length check, like the validator's zod schema.
        assertInvalid("""{"key":"A","type":"bool","label":"   "}""")
        // 前后空白会被裁剪，200 字符仍在限制内。
        assertEquals("x".repeat(200), parse("""{"key":"A","type":"bool","label":" ${"x".repeat(200)}  "}""").params[0].label)
    }

    // 解析器需容忍 UTF-8 BOM，否则 Windows 编辑器产生的文件会整体解析失败。
    @Test
    fun dropsBom() {
        assertEquals(0, ScriptParamsParser.parse("\uFEFF{\"schemaVersion\":1,\"params\":[]}").params.size)
    }

    // 各类型的专属约束：bool 默认值必须为布尔、enum 选项数量/唯一性、string 长度、int 范围与 int32 边界。
    @Test
    fun validatesTypeSpecificFields() {
        assertInvalid("""{"key":"A","type":"bool","label":"x","default":"true"}""")
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[]}""")
        // enum 选项值不能含空格，且不能重复。
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[{"value":"a b"}]}""")
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[{"value":"a"},{"value":"a"}]}""")
        // 默认值必须存在于选项中。
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[{"value":"a"}],"default":"b"}""")
        val options = (1..33).joinToString(",") { """{"value":"v$it"}""" }
        assertInvalid("""{"key":"A","type":"enum","label":"x","options":[$options]}""")

        assertInvalid("""{"key":"A","type":"string","label":"x","maxLength":0}""")
        assertInvalid("""{"key":"A","type":"string","label":"x","maxLength":1025}""")
        // 默认值长度不能超过 maxLength，也不能含换行。
        assertInvalid("""{"key":"A","type":"string","label":"x","maxLength":2,"default":"abc"}""")
        assertInvalid("""{"key":"A","type":"string","label":"x","default":"a\nb"}""")
        assertInvalid("""{"key":"A","type":"string","label":"x","required":"yes"}""")
        // A required field may start out empty: the user has to fill it in.
        parse("""{"key":"A","type":"string","label":"x","required":true}""")

        assertInvalid("""{"key":"A","type":"int","label":"x","default":1.5}""")
        assertInvalid("""{"key":"A","type":"int","label":"x","default":"1"}""")
        // min 不能大于 max，默认值必须落在区间内。
        assertInvalid("""{"key":"A","type":"int","label":"x","min":5,"max":1}""")
        assertInvalid("""{"key":"A","type":"int","label":"x","min":1,"max":3,"default":4}""")
        assertInvalid("""{"key":"A","type":"int","label":"x","max":-1}""")
        // int32 only.
        assertInvalid("""{"key":"A","type":"int","label":"x","max":2147483648}""")
        assertInvalid("""{"key":"A","type":"int","label":"x","min":-2147483649}""")
        assertInvalid("""{"key":"A","type":"int","label":"x","default":2147483648}""")
        // 恰好等于 int32 边界应被接受。
        parse("""{"key":"A","type":"int","label":"x","min":-2147483648,"max":2147483647}""")

        // `pattern` is not part of the format: like any unknown field it is ignored, never enforced.
        val text = parse("""{"key":"A","type":"string","label":"x","pattern":"(a+)+$"}""").params[0]
        // 未知字段既不参与校验也不应导致正则回溯，任意文本都通过。
        assertNull(text.validate("a".repeat(64) + "!"))
    }

    // 运行时值校验：空值/非法布尔/越界/非整数/控制字符分别返回对应错误码。
    @Test
    fun validatesValues() {
        val p = ScriptParamsParser.parse(sample)
        val bool = p.params[0]
        assertNull(bool.validate("false"))
        assertEquals(ParamError.INVALID_BOOLEAN, bool.validate("yes"))

        val choice = p.params[1]
        assertNull(choice.validate("b"))
        assertEquals(ParamError.INVALID_OPTION, choice.validate("c"))

        val text = p.params[2]
        // 非必填 string 允许空串；超过 maxLength=64 报 TOO_LONG。
        assertNull(text.validate(""))
        assertNull(text.validate("abc"))
        assertEquals(ParamError.TOO_LONG, text.validate("a".repeat(65)))
        assertEquals(ParamError.INVALID_CHARACTERS, text.validate("a\nb"))
        assertEquals(ParamError.INVALID_CHARACTERS, text.validate("a\u0000"))

        val required = parse("""{"key":"R","type":"string","label":"x","required":true}""").params[0]
        assertEquals(ParamError.REQUIRED, required.validate(""))
        // 引号/反引号/美元符是合法字符，后续由 shell quote 处理而不是在这里拒绝。
        assertNull(required.validate("it's \$fine `really`"))

        val count = p.params[3]
        assertNull(count.validate("10"))
        // 允许前后空白，canonical 会去除前导 '+' 并输出规范十进制。
        assertNull(count.validate(" 7 "))
        assertEquals("7", count.canonical("+7"))
        assertEquals(ParamError.ABOVE_MAX, count.validate("11"))
        assertEquals(ParamError.BELOW_MIN, count.validate("-1"))
        assertEquals(ParamError.NOT_AN_INTEGER, count.validate("1.0"))
        assertEquals(ParamError.NOT_AN_INTEGER, count.validate(""))
        // 超出 long/int 范围同样按非整数处理，避免溢出后误判。
        assertEquals(ParamError.NOT_AN_INTEGER, count.validate("99999999999999999999"))
        assertEquals(ParamError.NOT_AN_INTEGER, count.validate("2147483648"))
        assertEquals(ParamError.INVALID_CHARACTERS, text.validate("a\rb"))

        // 默认值集合应当整体合法；批量校验只返回出错字段。
        assertTrue(p.validate(p.defaults).isEmpty())
        assertEquals(mapOf("COUNT" to ParamError.ABOVE_MAX), p.validate(mapOf("COUNT" to "42")))
    }
}
