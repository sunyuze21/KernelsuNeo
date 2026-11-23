package me.weishu.kernelsu.data.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 验证 oneshot 脚本命令构造：环境变量顺序、POSIX 引号转义与退出码分类。 */
class ScriptCommandTest {

    // 模拟 late-load 之外的内核信息；lateLoad 影响 KSU_LATE_LOAD 是否注入。
    private val host = ScriptHostInfo(ksuVersion = "v3.0.0-1-gabc", ksuVersionCode = 31234, kernelVersionCode = 32500, lateLoad = false)
    // 脚本解压目录，路径含 64 位 hash 子目录，用于验证引号包裹与 cd 目标。
    private val dir = "/data/user/0/me.weishu.kernelsu/files/oneshot/" + "a".repeat(64)

    // 覆盖 bool/enum/string/int 四种参数类型，作为环境变量生成与校验的固定输入。
    private val params = ScriptParamsParser.parse(
        """
        {"schemaVersion":1,"params":[
          {"key":"FLAG","type":"bool","label":"f","default":true},
          {"key":"MODE","type":"enum","label":"m","options":[{"value":"a"},{"value":"b"}]},
          {"key":"NAME","type":"string","label":"n"},
          {"key":"COUNT","type":"int","label":"c","min":0,"max":10}
        ]}
        """.trimIndent()
    )

    // 单引号包裹可安全承载空格、命令替换、反引号与 shell 元字符；NUL 必须拒绝。
    @Test
    fun quotesForPosixShell() {
        assertEquals("''", ScriptCommand.quote(""))
        assertEquals("'abc'", ScriptCommand.quote("abc"))
        assertEquals("'it'\\''s'", ScriptCommand.quote("it's"))
        assertEquals("'\$(id) `id` \"x\" \\ ;&|'", ScriptCommand.quote("\$(id) `id` \"x\" \\ ;&|"))
        assertEquals("''\\'''\\'''", ScriptCommand.quote("''"))
        // NUL 无法出现在 execve 参数中，构造阶段就应失败。
        assertThrows(IllegalArgumentException::class.java) { ScriptCommand.quote("a\u0000b") }
    }

    // 环境变量顺序是脚本契约的一部分，固定顺序便于脚本/文档依赖。
    @Test
    fun buildsEnvironmentInContractOrder() {
        val env = ScriptCommand.environment(host, "my_script", dir, params, mapOf("NAME" to "x'y", "COUNT" to "+3"))
        assertEquals(
            listOf(
                "ASH_STANDALONE" to "1",
                "KSU" to "true",
                "KSU_VER" to "v3.0.0-1-gabc",
                "KSU_VER_CODE" to "31234",
                "KSU_KERNEL_VER_CODE" to "32500",
                "KSU_MODULE" to "my_script",
                "KSU_ONESHOT" to "1",
                "MODPATH" to dir,
                "KSU_PARAM_FLAG" to "true",
                "KSU_PARAM_MODE" to "a",
                "KSU_PARAM_NAME" to "x'y",
                "KSU_PARAM_COUNT" to "3",
            ),
            env,
        )
        // lateLoad 时在 KSU_ONESHOT 之后插入 KSU_LATE_LOAD。
        val late = ScriptCommand.environment(host.copy(lateLoad = true), "my_script", dir, ScriptParams.EMPTY, emptyMap())
        assertEquals("KSU_LATE_LOAD" to "1", late[5])
    }

    // 参数值必须按类型/范围校验；命令行只接受合法变量名且禁止覆盖 PATH。
    @Test
    fun rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCommand.environment(host, "s", dir, params, mapOf("COUNT" to "11"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCommand.environment(host, "s", dir, params, mapOf("MODE" to "c"))
        }
        // 换行会破坏环境变量注入到命令行时的语义。
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCommand.environment(host, "s", dir, params, mapOf("NAME" to "a\nb"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCommand.build(dir, listOf("BAD-NAME" to "x"))
        }
        // PATH 由构造器自行拼接，外部覆盖会改变命令解析结果。
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCommand.build(dir, listOf("PATH" to "/tmp"))
        }
    }

    // 最终命令中每个值都必须被单引号包裹，恶意输入不能逃逸成额外命令。
    @Test
    fun buildsCommandWithEveryValueQuoted() {
        val env = ScriptCommand.environment(
            host, "my_script", dir, params,
            mapOf("NAME" to "'; rm -rf / #", "MODE" to "b", "FLAG" to "false"),
        )
        val cmd = ScriptCommand.build(dir, env)
        assertEquals(
            "cd '$dir' && ASH_STANDALONE='1' KSU='true' KSU_VER='v3.0.0-1-gabc' KSU_VER_CODE='31234' " +
                "KSU_KERNEL_VER_CODE='32500' KSU_MODULE='my_script' KSU_ONESHOT='1' MODPATH='$dir' " +
                "KSU_PARAM_FLAG='false' KSU_PARAM_MODE='b' KSU_PARAM_NAME=''\\''; rm -rf / #' KSU_PARAM_COUNT='0' " +
                "PATH=\"\$PATH\":'/data/adb/ksu/bin' '/data/adb/ksu/bin/busybox' sh ./action.sh </dev/null",
            cmd,
        )
    }

    // 退出码 0 成功、2 不适用，其余（含 127/负数）统一视为失败。
    @Test
    fun classifiesExitCodes() {
        assertEquals(ScriptOutcome.SUCCESS, ScriptOutcome.of(0))
        assertEquals(ScriptOutcome.NOT_APPLICABLE, ScriptOutcome.of(2))
        assertEquals(ScriptOutcome.FAILURE, ScriptOutcome.of(1))
        assertEquals(ScriptOutcome.FAILURE, ScriptOutcome.of(127))
        assertEquals(ScriptOutcome.FAILURE, ScriptOutcome.of(-1))
    }

    // 运行记录以模块 id 为键持久化，读取时需校验 id 防止串记录。
    @Test
    fun runRecordsRoundTrip() {
        // 内存存储替代真实 SharedPreferences，避免测试依赖 Android 环境。
        val storage = object : ScriptRunStorage {
            val map = HashMap<String, String>()
            // 读取上次运行记录；没有记录时返回 null。
            override fun read(key: String) = map[key]
            // 以模块 id 为键覆盖保存，模拟 SharedPreferences 的单值语义。
            override fun write(key: String, value: String) {
                map[key] = value
            }
        }
        val history = ScriptRunHistory(storage)
        assertEquals(null, history.last("a"))
        val record = ScriptRunRecord(id = "a", versionCode = 3, time = 1234L, exitCode = 2)
        history.record(record)
        assertEquals(record, history.last("a"))
        // outcome 由 exitCode 派生，持久化时不单独存储。
        assertEquals(ScriptOutcome.NOT_APPLICABLE, history.last("a")!!.outcome)
        assertEquals(null, history.last("b"))
        assertEquals(null, ScriptRunRecord.decode("garbage", "a"))
        // A record stored under another id is ignored.
        assertEquals(null, ScriptRunRecord.decode(record.encode(), "b"))
    }
}
