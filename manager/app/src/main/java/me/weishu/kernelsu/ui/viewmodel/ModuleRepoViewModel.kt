package me.weishu.kernelsu.ui.viewmodel

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.registry.UnsupportedSchemaException
import me.weishu.kernelsu.data.repository.ModuleRepoRepository
import me.weishu.kernelsu.data.repository.ModuleRepoRepositoryImpl
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.screen.modulerepo.ModuleRepoUiState
import me.weishu.kernelsu.ui.screen.modulerepo.RepoSort
import me.weishu.kernelsu.ui.util.PinyinUtil
import me.weishu.kernelsu.ui.util.isNetworkAvailable

/**
 * 模块仓库列表 ViewModel：负责拉取索引、排序、搜索与离线状态。
 *
 * 排序发生在客户端，且列表与搜索结果必须保持同一顺序，因此所有写操作都通过
 * [listMutex] 串行化，避免并发排序用旧快照覆盖新结果。
 */
class ModuleRepoViewModel(
    private val repo: ModuleRepoRepository = ModuleRepoRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    companion object {
        // 日志标签，仓库拉取失败时用于定位
        private const val TAG = "ModuleRepoViewModel"
    }

    /** 简写仓库模块类型，避免本文件反复书写完整包名。 */
    typealias RepoModule = me.weishu.kernelsu.data.model.RepoModule

    private val _uiState = MutableStateFlow(ModuleRepoUiState())
    // 对外只读的仓库列表状态，排序/搜索变化都会经它通知界面
    val uiState: StateFlow<ModuleRepoUiState> = _uiState.asStateFlow()

    // 搜索输入的去抖通道，文本变化后由协程收集再执行实际过滤
    private val searchQuery = MutableStateFlow("")

    // Serializes the sorted writes of modules / searchResults, so a slower sort never replaces
    // a newer list or order with one computed from an older snapshot.
    private val listMutex = Mutex()

    init {
        // 恢复上次的排序方式；null 表示旧版本无记录，由 RepoSort 决定默认值
        val initial = RepoSort.fromStored(settingsRepo.moduleRepoSortOrder, null)
        _uiState.update {
            it.copy(
                sortOrder = initial,
                offline = !isNetworkAvailable(ksuApp)
            )
        }

        // 收集搜索输入并转交给 applySearchText，避免每次按键都直接算过滤
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
    }

    /** 按指定顺序排序模块列表，仅做纯计算，调用方负责线程与加锁。 */
    private fun sortModules(list: List<RepoModule>, order: RepoSort): List<RepoModule> = order.sort(list)

    /**
     * 按关键字过滤模块。
     *
     * 除 id/名称/作者/简介/标签外还匹配拼音，方便中文用户用拼音首字母检索；
     * 空关键字返回空列表，由调用方决定搜索态。
     */
    private fun filterModules(modules: List<RepoModule>, text: String): List<RepoModule> {
        if (text.isEmpty()) return emptyList()

        return modules.filter {
            it.moduleId.contains(text, true) ||
                    it.moduleName.contains(text, true) ||
                    it.authors.contains(text, true) ||
                    it.summary.contains(text, true) ||
                    it.tags.any { tag -> tag.contains(text, true) } ||
                    PinyinUtil.toPinyin(it.moduleName).contains(text, true)
        }
    }

    /**
     * 根据当前关键字重新计算结果。
     *
     * 先置为加载态再计算；过滤与排序在 IO 线程完成，
     * 加锁保证结果基于最新列表与排序，而非过期快照。
     */
    private suspend fun applySearchText(text: String) {
        _uiState.update {
            it.copy(
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchLoadingStatusFor(text)
                )
            )
        }

        if (text.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    searchResults = emptyList(),
                    searchStatus = state.searchStatus.copy(resultStatus = SearchStatus.ResultStatus.DEFAULT)
                )
            }
            return
        }

        listMutex.withLock {
            val result = withContext(Dispatchers.IO) {
                sortModules(filterModules(_uiState.value.modules, text), _uiState.value.sortOrder)
            }

            _uiState.update {
                it.copy(
                    searchResults = result,
                    searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, result.isEmpty()))
                )
            }
        }
    }

    /**
     * 列表或排序变化后同步刷新搜索结果。
     *
     * 这是同步计算（调用方已持有锁或处于主线程），保证搜索列表不会与模块列表不一致。
     */
    private fun refreshSearchResults() {
        val state = _uiState.value
        val text = state.searchStatus.searchText
        val results = sortModules(filterModules(state.modules, text), state.sortOrder)
        _uiState.update {
            it.copy(
                searchResults = results,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, results.isEmpty()))
            )
        }
    }

    /**
     * 拉取仓库索引。
     *
     * 刷新中重复调用会被忽略；失败与"使用缓存"两种情况都会提示，
     * 因为用户需要知道当前列表可能不是最新的。
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    error = null,
                    offline = !isNetworkAvailable(ksuApp)
                )
            }
            val result = repo.fetchModules()

            // 结果回主线程再更新状态并弹 Toast，避免在后台线程操作 UI
            withContext(Dispatchers.Main) {
                result.onSuccess { list ->
                    if (list.error != null) {
                        Toast.makeText(ksuApp, errorMessage(list.error, cached = true), Toast.LENGTH_SHORT).show()
                    }
                    listMutex.withLock {
                        val order = _uiState.value.sortOrder
                        val sorted = withContext(Dispatchers.Default) { sortModules(list.modules, order) }
                        _uiState.update {
                            it.copy(
                                hasLoaded = true,
                                modules = sorted,
                                stale = list.stale,
                                error = list.error,
                                offline = !isNetworkAvailable(ksuApp)
                            )
                        }
                        // 模块数据变了，已有搜索结果也必须重算
                        refreshSearchResults()
                    }
                    _uiState.update { it.copy(isRefreshing = false) }
                }.onFailure { e ->
                    Log.e(TAG, "fetch modules failed", e)
                    Toast.makeText(ksuApp, errorMessage(e, cached = false), Toast.LENGTH_SHORT).show()
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = e,
                            offline = !isNetworkAvailable(ksuApp)
                        )
                    }
                }
            }
        }
    }

    /**
     * 把异常翻译为用户可读文案，按"协议不支持 > 使用缓存 > 离线 > 通用失败"优先级判断。
     */
    private fun errorMessage(e: Throwable, cached: Boolean): String = when {
        e is UnsupportedSchemaException -> ksuApp.getString(R.string.module_repo_unsupported_schema)
        cached -> ksuApp.getString(R.string.module_repo_using_cache)
        !isNetworkAvailable(ksuApp) -> ksuApp.getString(R.string.network_offline)
        else -> ksuApp.getString(R.string.module_repo_load_failed)
    }

    /**
     * 切换排序方式并立即重排已有数据。
     *
     * 排序结果异步计算，因此需要在锁内重新读取状态：此时用户可能已再次切换排序。
     */
    fun setSortOrder(order: RepoSort) {
        settingsRepo.moduleRepoSortOrder = order.name
        viewModelScope.launch {
            listMutex.withLock {
                // Checked under the lock: an earlier change may still be pending.
                val state = _uiState.value
                if (state.sortOrder == order) return@withLock
                val (sortedModules, sortedSearch) = withContext(Dispatchers.Default) {
                    sortModules(state.modules, order) to sortModules(state.searchResults, order)
                }
                _uiState.update {
                    it.copy(
                        sortOrder = order,
                        modules = sortedModules,
                        searchResults = sortedSearch,
                    )
                }
            }
        }
    }

    /**
     * 更新搜索状态；仅当关键字变化时才向去抖通道推送，避免重复触发过滤。
     */
    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    /**
     * 只修改关键字并复用 [updateSearchStatus] 的同步逻辑。
     */
    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }
}
