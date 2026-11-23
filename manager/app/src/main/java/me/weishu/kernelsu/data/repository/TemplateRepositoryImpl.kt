package me.weishu.kernelsu.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.TemplateInfo
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.getAppProfileTemplate
import me.weishu.kernelsu.ui.util.listAppProfileTemplates
import me.weishu.kernelsu.ui.util.setAppProfileTemplate
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 通过 ksud 的 profile 模板命令读取/写入模板，并从 kernelsu.org 同步远端模板。 */
class TemplateRepositoryImpl : TemplateRepository {

    companion object {
        /** 日志标签。 */
        private const val TAG = "TemplateRepository"
        /** 远端模板索引：JSON 数组，元素为模板 id。 */
        private const val TEMPLATE_INDEX_URL = "https://kernelsu.org/templates/index.json"
        /** 单个模板的下载地址模板，参数为模板 id。 */
        private const val TEMPLATE_URL = "https://kernelsu.org/templates/%s"
    }

    /** 列出模板；本地为空或 [sync] 为 true 时先拉远端，再逐个读取详情。 */
    override suspend fun getTemplates(sync: Boolean): Result<List<TemplateInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val localTemplateIds = listAppProfileTemplates()
            Log.i(TAG, "localTemplateIds: $localTemplateIds")
            if (localTemplateIds.isEmpty() || sync) {
                fetchRemoteTemplates()
            }
            listAppProfileTemplates().mapNotNull { getTemplateInfoById(it) }
        }
    }

    /** 导入模板 JSON；支持数组或单个对象，非法项记录日志后跳过。 */
    override suspend fun importTemplates(jsonString: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 单个对象也包成数组，统一下面的处理流程。
            val array = try {
                JSONArray(jsonString)
            } catch (e: Exception) {
                try {
                    val json = JSONObject(jsonString)
                    JSONArray().apply { put(json) }
                } catch (e: Exception) {
                    throw Exception("invalid templates: $jsonString")
                }
            }

            (0 until array.length()).forEach { i ->
                runCatching {
                    val template = array.getJSONObject(i)
                    val id = template.getString("id")
                    // 导入的模板归用户所有，导出时会包含它们。
                    template.put("local", true)
                    setAppProfileTemplate(id, template.toString())
                }.onFailure { e ->
                    Log.e(TAG, "ignore invalid template: $array", e)
                }
            }
        }
    }

    /** 只导出本地模板；没有可导出的模板时以 failure 返回。 */
    override suspend fun exportTemplates(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val templates = listAppProfileTemplates()
                .mapNotNull { getTemplateInfoById(it) }
                .filter { it.local }
            if (templates.isEmpty()) {
                throw Exception("No templates to export")
            }
            JSONArray(templates.map { it.toJSON() }).toString()
        }
    }

    /** 按 id 读取并解析单个模板；不存在或解析失败时以 failure 返回。 */
    override suspend fun getTemplate(id: String): Result<TemplateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            getTemplateInfoById(id) ?: throw Exception("Template not found: $id")
        }
    }

    /** 下载远端模板索引并逐个写入本地；远端条目强制 local=false。 */
    private fun fetchRemoteTemplates() {
        runCatching {
            ksuApp.okhttpClient.newCall(
                Request.Builder().url(TEMPLATE_INDEX_URL).build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return
                }
                val remoteTemplateIds = JSONArray(response.body.string())
                Log.i(TAG, "fetchRemoteTemplates: $remoteTemplateIds")
                (0 until remoteTemplateIds.length()).forEach { i ->
                    val id = remoteTemplateIds.getString(i)
                    Log.i(TAG, "fetch template: $id")
                    // 单个模板下载失败不影响其余模板。
                    val templateJson = ksuApp.okhttpClient.newCall(
                        Request.Builder().url(TEMPLATE_URL.format(id)).build()
                    ).runCatching {
                        execute().use { response ->
                            if (!response.isSuccessful) {
                                return@forEach
                            }
                            response.body.string()
                        }
                    }.getOrNull() ?: return@forEach
                    Log.i(TAG, "template: $templateJson")

                    // validate remote template
                    runCatching {
                        val json = JSONObject(templateJson)
                        TemplateInfo.fromJSON(json)?.let {
                            // force local template
                            json.put("local", false)
                            setAppProfileTemplate(id, json.toString())
                        }
                    }.onFailure {
                        Log.e(TAG, "ignore invalid template: $it", it)
                        return@forEach
                    }
                }
            }
        }.onFailure { Log.e(TAG, "fetchRemoteTemplates: $it", it) }
    }

    /** 从 ksud 读取模板 JSON 并解析；读取或解析失败返回 null。 */
    private fun getTemplateInfoById(id: String): TemplateInfo? {
        return runCatching {
            TemplateInfo.fromJSON(JSONObject(getAppProfileTemplate(id)))
        }.onFailure {
            Log.e(TAG, "ignore invalid template: $it", it)
        }.getOrNull()
    }
}
