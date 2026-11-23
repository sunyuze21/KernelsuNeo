package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.data.model.RepoModuleDetail
import me.weishu.kernelsu.data.registry.ModuleNotInIndexException
import me.weishu.kernelsu.data.repository.ModuleRepoRepository
import me.weishu.kernelsu.data.repository.ModuleRepoRepositoryImpl

// 中文说明：模块仓库详情页 ViewModel，按模块 id 拉取仓库详情与 README。
// 通过 job 串行化请求，快速切换模块时会取消上一个请求，避免旧结果覆盖新页面。
/** Loads the registry detail (and README) of one module for the detail screen. */
class ModuleRepoDetailViewModel(
    private val repo: ModuleRepoRepository = ModuleRepoRepositoryImpl(),
) : ViewModel() {

    /**
     * 详情页状态。
     *
     * [loaded] 与 [loadFailed] 分开表示"已结束"和"成功/失败"，便于界面区分加载中与错误态。
     */
    data class State(
        val moduleId: String? = null,
        val detail: RepoModuleDetail? = null,
        val loaded: Boolean = false,
        /** Not in the current index (e.g. suspended): never offer installation. */
        val notInIndex: Boolean = false,
        val loadFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    // 对外只读的详情状态；写入只能通过 load 内部的 update
    val state: StateFlow<State> = _state.asStateFlow()

    // 当前正在执行的加载任务，用于去重与取消
    private var job: Job? = null

    // 中文说明：加载指定模块的详情。
    // 非强制模式下，若同一模块已加载或正在加载则直接返回，避免重复请求；
    // 切换模块时会先重置状态，防止短暂显示上一个模块的数据。
    /** Loads [moduleId] unless it is already loaded; [force] reloads (retry). */
    fun load(moduleId: String, force: Boolean = false) {
        val current = _state.value
        if (!force && current.moduleId == moduleId && (current.loaded || job?.isActive == true)) return
        if (current.moduleId != moduleId) _state.value = State(moduleId = moduleId)
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loaded = false) }
            repo.fetchDetail(moduleId)
                .onSuccess { detail ->
                    _state.update { it.copy(detail = detail, notInIndex = false, loadFailed = false, loaded = true) }
                }
                .onFailure { e ->
                    _state.update {
                        // 模块被下架属于"正常但不可安装"，与网络错误需要区分展示
                        if (e is ModuleNotInIndexException) {
                            it.copy(detail = null, notInIndex = true, loaded = true)
                        } else {
                            // 已有旧详情时保留内容，仅标记加载失败，便于用户重试
                            it.copy(loadFailed = it.detail == null, loaded = true)
                        }
                    }
                }
        }
    }
}
