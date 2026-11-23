package me.weishu.kernelsu.data.model

import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.profile.Capabilities
import me.weishu.kernelsu.profile.Groups
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * root profile 模板，可本地保存或从 kernelsu.org 同步。
 * [local] 为 true 表示用户自己的模板（可导出），远端同步下来的模板为 false。
 */
@Immutable
@Parcelize
data class TemplateInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val author: String = "",
    /** 是否为本地模板；远端模板只在缓存中存在，不会被导出。 */
    val local: Boolean = true,
    /** mount namespace 策略，取值为 [Natives.Profile.Namespace] 的 ordinal。 */
    val namespace: Int = Natives.Profile.Namespace.INHERITED.ordinal,
    val uid: Int = Natives.ROOT_UID,
    val gid: Int = Natives.ROOT_GID,
    /** 补充组 GID 列表。 */
    val groups: List<Int> = mutableListOf(),
    /** Linux capability 编号列表。 */
    val capabilities: List<Int> = mutableListOf(),
    /** SELinux context，默认进入 KernelSU 域。 */
    val context: String = Natives.KERNEL_SU_DOMAIN,
    /** 额外的 sepolicy 规则文本，逐条应用。 */
    val rules: List<String> = mutableListOf(),
    /** root profile 标志的 ordinal 列表。 */
    val flags: List<Int> = mutableListOf(
        Natives.Profile.RootProfileFlag.NO_NEW_PRIVS.ordinal // default no new privs for new template
    )
) : Parcelable {
    companion object {
        /** 日志标签。 */
        private const val TAG = "TemplateInfo"

        /**
         * 从模板 JSON 解析 [TemplateInfo]；非法字段会被忽略或回退到默认值。
         * 整体解析失败时返回 null，调用方应跳过该模板而不是中断列表。
         */
        fun fromJSON(templateJson: JSONObject): TemplateInfo? {
            return runCatching {
                val groupsJsonArray = templateJson.optJSONArray("groups")
                val capabilitiesJsonArray = templateJson.optJSONArray("capabilities")
                val flagsJsonArray = templateJson.optJSONArray("flags")
                // context / namespace 缺失时使用内核默认值。
                val context = templateJson.optString("context").takeIf { it.isNotEmpty() }
                    ?: Natives.KERNEL_SU_DOMAIN
                val namespace = templateJson.optString("namespace").takeIf { it.isNotEmpty() }
                    ?: Natives.Profile.Namespace.INHERITED.name

                val rulesJsonArray = templateJson.optJSONArray("rules")
                val templateInfo = TemplateInfo(
                    id = templateJson.getString("id"),
                    name = getLocaleString(templateJson, "name"),
                    description = getLocaleString(templateJson, "description"),
                    author = templateJson.optString("author"),
                    local = templateJson.optBoolean("local"),
                    namespace = Natives.Profile.Namespace.valueOf(
                        namespace.uppercase()
                    ).ordinal,
                    uid = templateJson.optInt("uid", Natives.ROOT_UID),
                    gid = templateJson.optInt("gid", Natives.ROOT_GID),
                    groups = getEnumOrdinals(groupsJsonArray, Groups::class.java).map { it.gid },
                    capabilities = getEnumOrdinals(
                        capabilitiesJsonArray, Capabilities::class.java
                    ).map { it.cap },
                    context = context,
                    rules = rulesJsonArray?.mapCatching<String, String>({ it }, {
                        Log.e(TAG, "ignore invalid rule: $it", it)
                    }).orEmpty(),
                    // flags 缺失时默认 NO_NEW_PRIVS，保持安全默认。
                    flags = flagsJsonArray?.let {
                        getEnumOrdinals(
                            it,
                            Natives.Profile.RootProfileFlag::class.java
                        ).map { flag -> flag.ordinal }
                    } ?: listOf(Natives.Profile.RootProfileFlag.NO_NEW_PRIVS.ordinal)
                )
                templateInfo
            }.onFailure {
                Log.e(TAG, "ignore invalid template: $it", it)
            }.getOrNull()
        }

        /** 读取带多语言覆写的字段：先精确匹配 language_country，再退化到 language。 */
        private fun getLocaleString(json: JSONObject, key: String): String {
            val fallback = json.getString(key)
            val locale = Locale.getDefault()
            val localeKey = "${locale.language}_${locale.country}"
            json.optJSONObject("locales")?.let {
                // check locale first
                it.optJSONObject(localeKey)?.let { json ->
                    return json.optString(key, fallback)
                }
                // fallback to language
                it.optJSONObject(locale.language)?.let { json ->
                    return json.optString(key, fallback)
                }
            }
            return fallback
        }

        /** 逐项转换 JSON 数组；单个元素失败时记录日志并跳过，不影响其余元素。 */
        @Suppress("UNCHECKED_CAST")
        private fun <T, R> JSONArray.mapCatching(
            transform: (T) -> R, onFail: (Throwable) -> Unit
        ): List<R> {
            return List(length()) { i -> get(i) as T }.mapNotNull { element ->
                runCatching {
                    transform(element)
                }.onFailure(onFail).getOrNull()
            }
        }

        /** 把 JSON 中的枚举名（大小写不敏感）转成枚举 ordinal；未知项被丢弃。 */
        private inline fun <reified T : Enum<T>> getEnumOrdinals(
            jsonArray: JSONArray?, enumClass: Class<T>
        ): List<T> {
            return jsonArray?.mapCatching<String, T>({ name ->
                enumValueOf(name.uppercase())
            }, {
                Log.e(TAG, "ignore invalid enum ${enumClass.simpleName}: $it", it)
            }).orEmpty()
        }
    }

    /** 序列化为模板 JSON；空的可选字段会被省略，枚举以名称形式保存。 */
    fun toJSON(): JSONObject {
        val template = this
        return JSONObject().apply {

            put("id", template.id)
            put("name", template.name.ifBlank { template.id })
            put("description", template.description.ifBlank { template.id })
            if (template.author.isNotEmpty()) {
                put("author", template.author)
            }
            put("namespace", Natives.Profile.Namespace.entries[template.namespace].name)
            put("uid", template.uid)
            put("gid", template.gid)

            if (template.groups.isNotEmpty()) {
                put(
                    "groups", JSONArray(
                        Groups.entries.filter {
                            template.groups.contains(it.gid)
                        }.map {
                            it.name
                        }
                    ))
            }

            if (template.capabilities.isNotEmpty()) {
                put(
                    "capabilities", JSONArray(
                        Capabilities.entries.filter {
                            template.capabilities.contains(it.cap)
                        }.map {
                            it.name
                        }
                    ))
            }

            if (template.context.isNotEmpty()) {
                put("context", template.context)
            }

            if (template.rules.isNotEmpty()) {
                put("rules", JSONArray(template.rules))
            }

            put(
                "flags", JSONArray(
                    Natives.Profile.RootProfileFlag.entries.filter {
                        template.flags.contains(it.ordinal)
                    }.map {
                        it.name
                    }
                )
            )
        }
    }
}
