package me.weishu.kernelsu.data.script

/** What the manager knows about the running KernelSU, exported to the script like ksud does. */
data class ScriptHostInfo(
    /** Manager 的版本名（与 ksud VERSION_NAME 对齐，去掉前缀 v）。 */
    val ksuVersion: String,
    val ksuVersionCode: Int,
    /** 内核侧协议版本号，脚本可用于判断功能可用性。 */
    val kernelVersionCode: Int,
    /** 是否为晚加载模式，对应环境变量 KSU_LATE_LOAD。 */
    val lateLoad: Boolean,
)

/**
 * Builds the shell command that runs a one-shot script's action.sh. Every variable value is
 * single-quoted; only `$PATH` itself is left to the shell to expand.
 */
object ScriptCommand {
    /** ksud 提供的 busybox 与工具目录，脚本运行时追加到 PATH。 */
    const val BIN_DIR = "/data/adb/ksu/bin"
    /** ksud 内置的 busybox，脚本用它执行 sh 与常用命令。 */
    const val BUSYBOX = "$BIN_DIR/busybox"
    /** 一次性脚本的固定入口文件名。 */
    const val ENTRY = "action.sh"

    /** 环境变量名必须是合法 POSIX 标识符，防止注入额外命令。 */
    private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Quotes [value] for a POSIX shell: `it's` -> `'it'\''s'`. */
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL cannot be passed through a shell" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Environment of a run, in order. [values] are the user's inputs per param key (missing
     * keys use the default); throws [IllegalArgumentException] if any of them is invalid.
     */
    fun environment(
        host: ScriptHostInfo,
        moduleId: String,
        dir: String,
        params: ScriptParams,
        values: Map<String, String>,
    ): List<Pair<String, String>> {
        // 固定变量与 ksud 的约定保持一致，脚本可据此判断运行环境。
        val env = mutableListOf(
            "ASH_STANDALONE" to "1",
            "KSU" to "true",
            "KSU_VER" to host.ksuVersion,
            "KSU_VER_CODE" to host.ksuVersionCode.toString(),
            "KSU_KERNEL_VER_CODE" to host.kernelVersionCode.toString(),
        )
        if (host.lateLoad) env += "KSU_LATE_LOAD" to "1"
        env += "KSU_MODULE" to moduleId
        env += "KSU_ONESHOT" to "1"
        env += "MODPATH" to dir
        for (param in params.params) {
            // 用户未填写时使用参数默认值，并在传入脚本前做一次校验。
            val value = values[param.key] ?: param.defaultValue
            param.validate(value)?.let { throw IllegalArgumentException("${param.key}: $it") }
            env += param.envName to param.canonical(value)
        }
        return env
    }

    /** `cd <dir> && <env...> PATH=... busybox sh ./action.sh </dev/null` */
    fun build(dir: String, env: List<Pair<String, String>>): String = buildString {
        append("cd ").append(quote(dir)).append(" && ")
        for ((name, value) in env) {
            require(ENV_NAME.matches(name) && name != "PATH") { "Invalid variable name: $name" }
            append(name).append('=').append(quote(value)).append(' ')
        }
        // PATH 由 shell 自行展开，其余变量全部单引号固定。
        append("PATH=\"\$PATH\":").append(quote(BIN_DIR)).append(' ')
        append(quote(BUSYBOX)).append(" sh ./").append(ENTRY)
        // libsu feeds commands through the shell's stdin: the script must never read from it.
        append(" </dev/null")
    }
}

/** Result of a run, by exit code. */
enum class ScriptOutcome {
    /** 脚本正常退出（exit 0）。 */
    SUCCESS,

    /** Exit code 2: the script decided it does not apply to this device. */
    NOT_APPLICABLE,
    /** 其他非零退出码。 */
    FAILURE;

    companion object {
        /** 退出码 2 约定为“当前设备不适用”，用于区分普通失败。 */
        const val EXIT_NOT_APPLICABLE = 2

        /** 把退出码映射为结果；除 0 和 2 外都视为失败。 */
        fun of(exitCode: Int): ScriptOutcome = when (exitCode) {
            0 -> SUCCESS
            EXIT_NOT_APPLICABLE -> NOT_APPLICABLE
            else -> FAILURE
        }
    }
}
