package me.weishu.kernelsu.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.repository.TemplateRepository
import me.weishu.kernelsu.data.repository.TemplateRepositoryImpl
import me.weishu.kernelsu.profile.Capabilities
import me.weishu.kernelsu.profile.Groups
import me.weishu.kernelsu.ui.screen.template.TemplateUiState
import me.weishu.kernelsu.ui.util.getAppProfileTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

// 本文件的日志标签；模板解析失败等异常都会带上它，便于过滤日志
const val TAG = "TemplateViewModel"

/**
 * 应用配置模板 ViewModel：负责模板列表的读取、排序、导入导出。
 *
 * 模板数据来自仓库（可能同时包含本地与内置模板），ViewModel 只维护内存状态，
 * 具体持久化交由 [TemplateRepository]。
 */
class TemplateViewModel(
    private val repo: TemplateRepository = TemplateRepositoryImpl()
) : ViewModel() {

    /** 模板模型简写，避免在 UI 路由与状态里重复写完整包名。 */
    typealias TemplateInfo = me.weishu.kernelsu.data.model.TemplateInfo

    private val _uiState = MutableStateFlow(TemplateUiState())
    // 对外只读的模板状态，列表与排序结果由 fetchTemplates 写入
    val uiState: StateFlow<TemplateUiState> = _uiState.asStateFlow()

    /**
     * 获取模板列表并按"本地优先、其次按系统语言排序"重组。
     *
     * [sync] 为 true 时强制从磁盘重新同步；结果回主线程再更新状态，
     * 避免后台线程触发 Compose 状态写入。
     */
    suspend fun fetchTemplates(sync: Boolean = false) {
        _uiState.update { it.copy(isRefreshing = true, error = null) }

        val result = repo.getTemplates(sync)

        withContext(Dispatchers.Main) {
            result.onSuccess { templates ->
                // Collator 会遵循系统语言的排序规则，保证中文/英文 id 顺序符合直觉
                val comparator = compareBy(TemplateInfo::local).reversed().then(
                    compareBy(
                        Collator.getInstance(Locale.getDefault()), TemplateInfo::id
                    )
                )
                val sorted = templates.sortedWith(comparator)

                _uiState.update {
                    it.copy(
                        templates = templates,
                        templateList = sorted,
                        isRefreshing = false
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = e
                    )
                }
            }
        }
    }

    /**
     * 导入模板 JSON 文本，成功/失败分别通过回调通知调用方展示结果。
     *
     * 回调是 suspend 的，调用方可直接在其中弹出对话框或写数据库。
     */
    suspend fun importTemplates(
        templates: String,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (String) -> Unit
    ) {
        repo.importTemplates(templates)
            .onSuccess { onSuccess() }
            .onFailure { onFailure(it.message ?: "Unknown error") }
    }

    /**
     * 导出全部模板为 JSON 文本。
     *
     * 无模板可导出时走 [onTemplateEmpty]，与其他错误区分，便于给出不同提示。
     */
    suspend fun exportTemplates(onTemplateEmpty: suspend () -> Unit, callback: suspend (String) -> Unit) {
        repo.exportTemplates()
            .onSuccess { callback(it) }
            .onFailure { onTemplateEmpty() }
    }
}

/**
 * 按 id 读取单个模板（通常用于从路由恢复模板对象）。
 *
 * 模板内容可能已损坏或被删除，失败时仅记录日志并返回 null，不向上抛异常。
 */
fun getTemplateInfoById(id: String): me.weishu.kernelsu.data.model.TemplateInfo? {
    return runCatching {
        me.weishu.kernelsu.data.model.TemplateInfo.fromJSON(JSONObject(getAppProfileTemplate(id)))
    }.onFailure {
        Log.e(TAG, "ignore invalid template: $it", it)
    }.getOrNull()
}

/**
 * 生成一份示例模板并打印到日志，供开发者参考字段格式。
 *
 * 不参与运行时逻辑，仅作调试辅助。
 */
@Suppress("unused")
fun generateTemplates() {
    val templateJson = JSONObject()
    templateJson.put("id", "com.example")
    templateJson.put("name", "Example")
    templateJson.put("description", "This is an example template")
    templateJson.put("local", true)
    templateJson.put("namespace", Natives.Profile.Namespace.INHERITED.name)
    templateJson.put("uid", 0)
    templateJson.put("gid", 0)

    templateJson.put("groups", JSONArray().apply { put(Groups.INET.name) })
    templateJson.put("capabilities", JSONArray().apply { put(Capabilities.CAP_NET_RAW.name) })
    templateJson.put("context", "u:r:ksu:s0")
    templateJson.put("flags", JSONArray().apply { put(Natives.Profile.RootProfileFlag.NO_NEW_PRIVS.name) })
    Log.i(TAG, "$templateJson")
}
