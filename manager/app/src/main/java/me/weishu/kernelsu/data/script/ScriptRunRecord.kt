package me.weishu.kernelsu.data.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The last run of a one-shot script; kept locally, scripts have no installed state. */
@Serializable
data class ScriptRunRecord(
    val id: String,
    val versionCode: Int? = null,
    /** Epoch millis. */
    val time: Long,
    val exitCode: Int,
) {
    /** 退出码对应的结果；仅用于展示。 */
    val outcome: ScriptOutcome get() = ScriptOutcome.of(exitCode)

    /** 序列化为 JSON 字符串，写入本地存储。 */
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Null for missing or unreadable data, or a record of another id. */
        fun decode(text: String?, id: String): ScriptRunRecord? =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.id == id }
    }
}

/** Key-value storage the run history is kept in (SharedPreferences on the device). */
interface ScriptRunStorage {
    /** 读取指定 key；不存在时返回 null。 */
    fun read(key: String): String?
    /** 覆盖写入指定 key。 */
    fun write(key: String, value: String)
}

/** 按脚本 id 保存最近一次运行记录；脚本本身没有安装状态，历史是唯一的本地状态。 */
class ScriptRunHistory(private val storage: ScriptRunStorage) {
    /** 读取脚本 [id] 的上次运行记录；无记录或记录损坏时返回 null。 */
    fun last(id: String): ScriptRunRecord? = ScriptRunRecord.decode(storage.read(KEY_PREFIX + id), id)

    /** 覆盖保存脚本最近一次运行记录。 */
    fun record(record: ScriptRunRecord) = storage.write(KEY_PREFIX + record.id, record.encode())

    private companion object {
        /** 存储 key 前缀，隔离其他偏好项。 */
        const val KEY_PREFIX = "last_run:"
    }
}
