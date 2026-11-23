package me.weishu.kernelsu.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import me.weishu.kernelsu.ui.util.listModules
import me.weishu.kernelsu.ui.util.module.sanitizeVersionString
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 通过 ksud 的模块列表 JSON 与模块 updateJson 实现 [ModuleRepository]。 */
class ModuleRepositoryImpl : ModuleRepository {

    companion object {
        /** 日志标签。 */
        private const val TAG = "ModuleRepository"
    }

    /** 读取 ksud 的模块列表并映射为 [Module]；字段缺失时使用安全默认值。 */
    override suspend fun getModules(): Result<List<Module>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = listModules()
            val array = JSONArray(result)
            (0 until array.length())
                .asSequence()
                .map { array.getJSONObject(it) }
                .map { obj ->
                    Module(
                        id = obj.getString("id"),
                        name = obj.optString("name"),
                        author = obj.optString("author", "Unknown"),
                        version = obj.optString("version", "Unknown"),
                        versionCode = obj.optInt("versionCode", 0),
                        description = obj.optString("description"),
                        enabled = obj.getBoolean("enabled"),
                        update = obj.optBoolean("update"),
                        remove = obj.getBoolean("remove"),
                        updateJson = obj.optString("updateJson"),
                        hasWebUi = obj.optBoolean("web"),
                        hasActionScript = obj.optBoolean("action"),
                        // 不同 ksud 版本可能输出数字 0/1 或布尔值，这里两种都接受。
                        metamodule = (obj.optInt("metamodule") != 0) || obj.optBoolean("metamodule"),
                        actionIconPath = obj.optString("actionIcon").takeIf { it.isNotBlank() },
                        webUiIconPath = obj.optString("webuiIcon").takeIf { it.isNotBlank() },
                        preinit = obj.optString("preinit")
                    )
                }.toList()
        }
    }

    /**
     * 请求模块的 updateJson；只有 versionCode 更大且提供 zipUrl 时才返回更新。
     * 已标记 remove/update 或未启用的模块不检查，避免在待处理状态下重复下载。
     */
    override suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isNetworkAvailable(ksuApp)) {
                return@runCatching ModuleUpdateInfo.Empty
            }
            if (module.updateJson.isEmpty() || module.remove || module.update || !module.enabled) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            // updateJson 由模块作者托管，格式为常见的 version/versionCode/zipUrl。
            val url = module.updateJson
            val response = ksuApp.okhttpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()

            val result = if (response.isSuccessful) {
                response.body.string()
            } else {
                ""
            }

            if (result.isEmpty()) {
                return@runCatching ModuleUpdateInfo.Empty
            }

            val updateJson = JSONObject(result)
            var version = updateJson.optString("version", "")
            version = sanitizeVersionString(version)
            val versionCode = updateJson.optInt("versionCode", 0)
            val zipUrl = updateJson.optString("zipUrl", "")
            val changelog = updateJson.optString("changelog", "")

            // 没有更高 versionCode 或没有下载地址都视为无更新。
            if (versionCode <= module.versionCode || zipUrl.isEmpty()) {
                ModuleUpdateInfo.Empty
            } else {
                ModuleUpdateInfo(zipUrl, version, changelog, versionCode)
            }
        }
    }
}
