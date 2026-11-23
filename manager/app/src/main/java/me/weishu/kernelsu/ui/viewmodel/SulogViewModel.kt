package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.util.SulogEntry
import me.weishu.kernelsu.ui.util.SulogEventFilter
import me.weishu.kernelsu.ui.util.SulogFile
import me.weishu.kernelsu.ui.util.SulogFileCleanAction
import me.weishu.kernelsu.ui.util.buildVisibleSulogEntries
import me.weishu.kernelsu.ui.util.cleanSulogFile
import me.weishu.kernelsu.ui.util.defaultSulogEventFilters
import me.weishu.kernelsu.ui.util.deleteSulogFile
import me.weishu.kernelsu.ui.util.listSulogFiles
import me.weishu.kernelsu.ui.util.parseSulogLines
import me.weishu.kernelsu.ui.util.readSulogFile
import me.weishu.kernelsu.ui.util.resolveSulogFileCleanAction

/**
 * sulog（内核授权日志）页面状态。
 *
 * 同时保存原始 [entries] 与过滤后的 [visibleEntries]：
 * 过滤在内存中进行，切换筛选条件无需重新读文件。
 */
data class SulogUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,
    val searchText: String = "",
    val selectedFilters: Set<SulogEventFilter> = defaultSulogEventFilters(),
    val files: List<SulogFile> = emptyList(),
    val selectedFilePath: String? = null,
    val entries: List<SulogEntry> = emptyList(),
    val visibleEntries: List<SulogEntry> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * sulog 页面 ViewModel：读取日志文件、解析、筛选并支持启停日志功能。
 *
 * 文件读写都放在 IO 线程；刷新任务通过 [refreshJob] 串行化，新的刷新会取消旧任务，
 * 防止慢读把旧文件内容写回状态。
 */
class SulogViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SulogUiState())
    // 对外只读的日志页状态，可见日志由筛选管道异步补充
    val uiState: StateFlow<SulogUiState> = _uiState.asStateFlow()
    // 原始日志行解析结果，作为筛选管道的输入
    private val entriesFlow = MutableStateFlow<List<SulogEntry>>(emptyList())
    private val searchTextFlow = MutableStateFlow("")
    private val selectedFiltersFlow = MutableStateFlow(defaultSulogEventFilters())

    // 当前刷新任务；取消后其 IO 读取结果会被丢弃
    private var refreshJob: Job? = null

    init {
        // 恢复用户上次的筛选条件；枚举名不匹配（版本变化）时自动忽略
        val savedFilters = repo.suLogFilters
            ?.mapNotNull { raw -> SulogEventFilter.entries.firstOrNull { it.name == raw } }
            ?.toSet()
            ?.ifEmpty { defaultSulogEventFilters() }
            ?: defaultSulogEventFilters()
        selectedFiltersFlow.value = savedFilters
        _uiState.update { it.copy(selectedFilters = savedFilters) }

        // 三个输入流任一变化都重新计算可见日志；计算在 Default 线程，避免阻塞主线程
        viewModelScope.launch(Dispatchers.Default) {
            combine(entriesFlow, searchTextFlow, selectedFiltersFlow) { entries, searchText, selectedFilters ->
                buildVisibleSulogEntries(entries, searchText, selectedFilters)
            }.collect { visibleEntries ->
                _uiState.update { it.copy(visibleEntries = visibleEntries) }
            }
        }
    }

    /**
     * 重新读取日志状态与当前文件内容。
     *
     * [preferredFilePath] 用于刷新后尽量停留在同一文件；若该文件已不存在则回到最新文件。
     * 加载期间只覆盖 isLoading/errorMessage，保留搜索与筛选状态。
     */
    fun refresh(preferredFilePath: String? = _uiState.value.selectedFilePath) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val sulogStatus = repo.getSulogStatus()
                // forced 表示日志被内核强制开启，此时以运行时值为准；否则看持久化配置
                val isSulogEnabled = if (sulogStatus == "forced") {
                    repo.getSulogValue() == 1L
                } else {
                    repo.getSulogPersistValue() == 1L
                }
                val files = listSulogFiles()
                // 文件列表可能已被其他操作改变，及时响应取消
                currentCoroutineContext().ensureActive()
                val selectedFile = when {
                    files.isEmpty() -> null
                    preferredFilePath != null -> files.firstOrNull { it.path == preferredFilePath } ?: files.first()
                    else -> files.first()
                }
                val logLines = selectedFile?.let { readSulogFile(it.path) }.orEmpty()
                val entries = parseSulogLines(logLines)
                val currentState = _uiState.value
                // 解析可能耗时较长，写状态前再检查一次取消
                currentCoroutineContext().ensureActive()
                entriesFlow.value = entries
                SulogUiState(
                    isLoading = false,
                    sulogStatus = sulogStatus,
                    isSulogEnabled = isSulogEnabled,
                    searchText = currentState.searchText,
                    selectedFilters = currentState.selectedFilters,
                    files = files,
                    selectedFilePath = selectedFile?.path,
                    entries = entries,
                    visibleEntries = currentState.visibleEntries,
                )
            }.onSuccess { state ->
                _uiState.value = state
            }.onFailure { error ->
                // 协程取消不是错误，必须继续抛出以保持结构化并发语义
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message,
                    )
                }
            }
        }
    }

    /**
     * 强制回到最新日志文件后刷新，用于"查看最新"入口。
     */
    fun refreshLatest() {
        refresh(preferredFilePath = null)
    }

    /**
     * 开启 sulog 功能并持久化到内核配置。
     *
     * 步骤包含写配置与执行 ksud 特性保存，任一失败都会导致开关不生效，
     * 因此完成后统一刷新以呈现真实状态。
     */
    fun enableSulog() {
        val preferredFilePath = _uiState.value.selectedFilePath
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            if (repo.setSulogEnabled(true)) {
                repo.execKsudFeatureSave()
            }
            refresh(preferredFilePath)
        }
    }

    /**
     * 清理当前选中的日志文件。
     *
     * 按文件策略决定是清空内容还是删除文件（例如当前活跃文件不能删），
     * 清理后保持在同一路径刷新，便于用户确认结果。
     */
    fun cleanFile() {
        val currentState = _uiState.value
        val path = currentState.selectedFilePath ?: return
        val cleanAction = resolveSulogFileCleanAction(currentState.files, path)
        viewModelScope.launch(Dispatchers.IO) {
            when (cleanAction) {
                SulogFileCleanAction.Clear -> cleanSulogFile(path)
                SulogFileCleanAction.Delete -> deleteSulogFile(path)
            }
            refresh(path)
        }
    }

    /**
     * 更新搜索关键字：同时写入筛选管道与 UI 状态，两者必须保持一致。
     */
    fun setSearchText(searchText: String) {
        searchTextFlow.value = searchText
        _uiState.update { currentState ->
            currentState.copy(
                searchText = searchText,
            )
        }
    }

    /**
     * 切换单个事件类型筛选，并把新的筛选集合持久化。
     *
     * 注意 repo 写入发生在 update 块内：update 可能因状态竞争被重试，
     * 因此这里只做幂等的集合写入，不触发外部可见的副作用。
     */
    fun toggleFilter(filter: SulogEventFilter) {
        _uiState.update { currentState ->
            val selectedFilters = currentState.selectedFilters.toMutableSet().apply {
                if (!add(filter)) remove(filter)
            }
            selectedFiltersFlow.value = selectedFilters
            repo.suLogFilters = selectedFilters.map { it.name }.toSet()
            currentState.copy(
                selectedFilters = selectedFilters,
            )
        }
    }
}
