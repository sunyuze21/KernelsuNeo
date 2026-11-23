package me.weishu.kernelsu.data.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * params.json (schemaVersion 1) of a one-shot script: the parameters the manager asks for
 * before running action.sh. Each value reaches the script as `KSU_PARAM_<KEY>`.
 */

/** params.json 解析失败；消息直接展示给开发者，说明具体哪个字段非法。 */
class ScriptParamsException(message: String) : Exception(message)

/** Why a parameter value is rejected. */
enum class ParamError {
    /** 必填项为空。 */
    REQUIRED,
    /** 文本超过 maxLength。 */
    TOO_LONG,
    /** 文本包含 NUL / 换行 / 回车，无法安全传入环境变量。 */
    INVALID_CHARACTERS,
    /** 不是 int32 整数。 */
    NOT_AN_INTEGER,
    /** 小于声明的 min。 */
    BELOW_MIN,
    /** 大于声明的 max。 */
    ABOVE_MAX,
    /** 不在枚举选项中。 */
    INVALID_OPTION,
    /** 不是 "true"/"false"。 */
    INVALID_BOOLEAN,
}

/**
 * One declared parameter. Values are handled in their canonical string form, which is also
 * what the script receives: `true`/`false`, a decimal integer, an enum value or the raw string.
 */
sealed class ScriptParam {
    /** 参数 key；会拼成 `KSU_PARAM_<KEY>` 环境变量。 */
    abstract val key: String
    abstract val label: String
    abstract val description: String?

    /** Canonical form of the default value. */
    abstract val defaultValue: String

    /** Returns null when [value] is acceptable. */
    abstract fun validate(value: String): ParamError?

    /** Canonical value passed to the script; only call with a value [validate] accepted. */
    open fun canonical(value: String): String = value

    /** 传给脚本的环境变量名，统一加 KSU_PARAM_ 前缀。 */
    val envName: String get() = ENV_PREFIX + key

    /** 布尔参数，脚本收到 "true"/"false"。 */
    data class Bool(
        override val key: String,
        override val label: String,
        override val description: String?,
        val default: Boolean,
    ) : ScriptParam() {
        override val defaultValue: String get() = default.toString()
        /** 只接受规范化的 "true"/"false"。 */
        override fun validate(value: String): ParamError? =
            if (value == "true" || value == "false") null else ParamError.INVALID_BOOLEAN
    }

    /** 枚举选项；[label] 为空时 UI 直接展示 value。 */
    data class EnumOption(val value: String, val label: String?)

    /** 单选参数，value 必须精确匹配某个选项。 */
    data class Choice(
        override val key: String,
        override val label: String,
        override val description: String?,
        val options: List<EnumOption>,
        val default: String,
    ) : ScriptParam() {
        override val defaultValue: String get() = default
        /** value 必须精确匹配某个枚举值。 */
        override fun validate(value: String): ParamError? =
            if (options.any { it.value == value }) null else ParamError.INVALID_OPTION
    }

    /** 文本参数；空串是否合法取决于 [required]。 */
    data class Text(
        override val key: String,
        override val label: String,
        override val description: String?,
        val default: String,
        val maxLength: Int,
        val required: Boolean,
    ) : ScriptParam() {
        override val defaultValue: String get() = default

        /** Empty is valid unless [required]. */
        override fun validate(value: String): ParamError? = when {
            value.isEmpty() -> if (required) ParamError.REQUIRED else null
            // 换行/NUL 会破坏 shell 环境变量或日志，直接拒绝。
            value.any { it == '\u0000' || it == '\n' || it == '\r' } -> ParamError.INVALID_CHARACTERS
            value.length > maxLength -> ParamError.TOO_LONG
            else -> null
        }
    }

    /** 整数参数，取值范围为 int32；min/max 为 null 表示不限制。 */
    data class Integer(
        override val key: String,
        override val label: String,
        override val description: String?,
        val default: Int,
        val min: Int?,
        val max: Int?,
    ) : ScriptParam() {
        override val defaultValue: String get() = default.toString()

        /** 先解析为 int32，再检查 min/max 边界。 */
        override fun validate(value: String): ParamError? {
            val number = parseInteger(value) ?: return ParamError.NOT_AN_INTEGER
            return when {
                min != null && number < min -> ParamError.BELOW_MIN
                max != null && number > max -> ParamError.ABOVE_MAX
                else -> null
            }
        }

        /** 规范化整数（去掉前导 +/0），确保脚本拿到统一格式。 */
        override fun canonical(value: String): String = parseInteger(value)!!.toString()

        companion object {
            /** 最多 10 位数字，超出 int32 的值会在 toIntOrNull 处被拒绝。 */
            private val INTEGER = Regex("^[+-]?[0-9]{1,10}$")

            /** Values are int32 like min / max / default; anything outside is not an integer. */
            fun parseInteger(value: String): Int? = value.trim().takeIf(INTEGER::matches)?.toIntOrNull()
        }
    }

    companion object {
        /** 参数字段统一使用该前缀，避免与 ksud 的 KSU_* 变量冲突。 */
        const val ENV_PREFIX = "KSU_PARAM_"
    }
}

/** 一次运行可用的全部参数声明；[params] 顺序即 UI 展示顺序。 */
data class ScriptParams(val params: List<ScriptParam>) {
    /** key 到默认值的映射，供 UI 预填。 */
    val defaults: Map<String, String> get() = params.associate { it.key to it.defaultValue }

    /** Error per key for [values] (missing values fall back to the default). */
    fun validate(values: Map<String, String>): Map<String, ParamError> =
        params.mapNotNull { p -> p.validate(values[p.key] ?: p.defaultValue)?.let { p.key to it } }.toMap()

    companion object {
        /** 没有 params.json 时使用。 */
        val EMPTY = ScriptParams(emptyList())
    }
}

/** Parses and validates params.json; any violation of schemaVersion 1 throws [ScriptParamsException]. */
object ScriptParamsParser {
    /** 唯一支持的 params.json 版本。 */
    const val SCHEMA_VERSION = 1
    /** 单次运行最多声明的参数数量。 */
    const val MAX_PARAMS = 16
    /** 枚举参数最多选项数。 */
    const val MAX_OPTIONS = 32
    /** label/description 的最大字符数。 */
    const val MAX_TEXT_LENGTH = 200
    /** 未声明 maxLength 时文本参数的默认上限。 */
    const val DEFAULT_MAX_LENGTH = 256
    /** 文本参数 maxLength 的硬上限。 */
    const val MAX_MAX_LENGTH = 1024

    /** key 必须是大写字母开头的大写标识符，最终拼进环境变量。 */
    private val KEY = Regex("^[A-Z][A-Z0-9_]{0,31}$")
    /** 枚举 value 只允许安全字符，避免破坏 shell/UI。 */
    private val OPTION_VALUE = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val JSON = Json { ignoreUnknownKeys = true }

    /** params.json 的顶层结构。 */
    @Serializable
    private class RawParams(val schemaVersion: Int, val params: List<RawParam>)

    /** 单个参数的原始声明；具体字段依 [type] 而定。 */
    @Serializable
    private class RawParam(
        val key: String,
        val type: String,
        val label: String,
        val description: String? = null,
        // Its type depends on [type].
        val default: JsonElement? = null,
        val options: List<RawOption>? = null,
        val maxLength: Int? = null,
        val required: Boolean = false,
        val min: Int? = null,
        val max: Int? = null,
    )

    /** 枚举选项的原始声明。 */
    @Serializable
    private class RawOption(val value: String, val label: String? = null)

    /** 直接以异常终止解析，调用方无需继续。 */
    private fun fail(message: String): Nothing = throw ScriptParamsException(message)

    /** 解析并校验整个 params.json；schemaVersion、数量、key 唯一性都在这里检查。 */
    fun parse(source: String): ScriptParams {
        val raw = try {
            JSON.decodeFromString(RawParams.serializer(), source.removePrefix("\uFEFF"))
        } catch (e: SerializationException) {
            fail("Malformed params.json: ${e.message}")
        } catch (e: IllegalArgumentException) {
            fail("Malformed params.json: ${e.message}")
        }
        if (raw.schemaVersion != SCHEMA_VERSION) fail("Unsupported schemaVersion: ${raw.schemaVersion}")
        if (raw.params.size > MAX_PARAMS) fail("At most $MAX_PARAMS params are allowed")

        val seen = HashSet<String>()
        val params = raw.params.map { p ->
            // key 重复会让环境变量互相覆盖，必须拒绝。
            parseParam(p).also { if (!seen.add(it.key)) fail("Duplicate key: ${it.key}") }
        }
        return ScriptParams(params)
    }

    /** 按 type 构建具体参数并校验其默认值；类型未知直接失败。 */
    private fun parseParam(raw: RawParam): ScriptParam {
        val key = raw.key
        if (!KEY.matches(key)) fail("Invalid key: $key")
        val label = checkText(raw.label, "$key.label")
        val description = raw.description?.let { checkText(it, "$key.description") }
        // JsonNull 与缺失等价；其余必须是标量。
        val default = raw.default?.takeIf { it !is JsonNull }?.let {
            it as? JsonPrimitive ?: fail("$key.default must be a ${raw.type}")
        }

        val param = when (raw.type) {
            "bool" -> ScriptParam.Bool(
                key, label, description,
                default = default?.let { it.takeIf { !it.isString }?.booleanOrNull ?: fail("$key.default must be a boolean") }
                    ?: false,
            )

            "enum" -> {
                val raws = raw.options ?: fail("$key.options is required")
                if (raws.size !in 1..MAX_OPTIONS) fail("$key.options must have 1..$MAX_OPTIONS entries")
                val values = HashSet<String>()
                val options = raws.map { o ->
                    if (!OPTION_VALUE.matches(o.value)) fail("$key: invalid option value ${o.value}")
                    if (!values.add(o.value)) fail("$key: duplicate option ${o.value}")
                    ScriptParam.EnumOption(o.value, o.label?.let { checkText(it, "$key.${o.value}.label") })
                }
                // 未声明默认值时使用第一个选项，保证总有合法值。
                val defaultValue = default?.let { it.takeIf { it.isString }?.content ?: fail("$key.default must be a string") }
                    ?: options.first().value
                ScriptParam.Choice(key, label, description, options, defaultValue)
            }

            "string" -> {
                val maxLength = raw.maxLength ?: DEFAULT_MAX_LENGTH
                if (maxLength !in 1..MAX_MAX_LENGTH) fail("$key.maxLength must be 1..$MAX_MAX_LENGTH")
                val defaultValue = default?.let { it.takeIf { it.isString }?.content ?: fail("$key.default must be a string") }
                    ?: ""
                // An empty default is always fine, even when required: the user has to fill it in.
                ScriptParam.Text(key, label, description, defaultValue, maxLength, raw.required)
            }

            "int" -> {
                if (raw.min != null && raw.max != null && raw.min > raw.max) fail("$key: min > max")
                // 未指定默认值时取 min（有界）或 0。
                val defaultValue = default?.let { it.takeIf { !it.isString }?.intOrNull ?: fail("$key.default must be an integer") }
                    ?: raw.min ?: 0
                ScriptParam.Integer(key, label, description, defaultValue, raw.min, raw.max)
            }

            else -> fail("$key: unsupported type ${raw.type}")
        }
        // REQUIRED 允许空默认值（等待用户填写），其他校验错误都说明声明本身有问题。
        param.validate(param.defaultValue)?.let { if (it != ParamError.REQUIRED) fail("$key.default is invalid: $it") }
        return param
    }

    /** Trimmed first, then 1..[MAX_TEXT_LENGTH] characters. */
    private fun checkText(text: String, what: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_LENGTH) fail("$what must be 1..$MAX_TEXT_LENGTH characters")
        return trimmed
    }
}
