package me.weishu.kernelsu.ui.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo
import me.weishu.kernelsu.data.registry.UpdateVerification
import me.weishu.kernelsu.data.repository.ModuleRepoRepository
import me.weishu.kernelsu.data.repository.ModuleRepoRepositoryImpl
import me.weishu.kernelsu.data.repository.ModuleRepository
import me.weishu.kernelsu.data.repository.ModuleRepositoryImpl
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.screen.module.ModuleConfirmDialogState
import me.weishu.kernelsu.ui.screen.module.ModuleConfirmRequest
import me.weishu.kernelsu.ui.screen.module.ModuleEffect
import me.weishu.kernelsu.ui.screen.module.ModuleUiState
import me.weishu.kernelsu.ui.util.PinyinUtil
import me.weishu.kernelsu.ui.util.hasMagisk
import okhttp3.Request
import java.text.Collator
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import me.weishu.kernelsu.ui.util.toggleModule as toggleModuleUtil
import me.weishu.kernelsu.ui.util.undoUninstallModule as undoUninstallModuleUtil
import me.weishu.kernelsu.ui.util.uninstallModule as uninstallModuleUtil

/**
 * 模块列表 ViewModel：加载模块、排序、搜索、更新检测与启停/卸载操作。
 *
 * 模块来自内核的挂载列表，操作会改变磁盘状态，因此任何写操作成功后都重新拉取列表，
 * 而不是就地修改内存数据，避免界面与真实状态不一致。
 */
class ModuleViewModel(
    private val repo: ModuleRepository = ModuleRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
    private val registryRepo: ModuleRepoRepository = ModuleRepoRepositoryImpl(),
) : ViewModel() {

    companion object {
        // 日志标签，用于过滤模块加载与更新检查相关日志
        private const val TAG = "ModuleViewModel"
    }

    /**
     * 更新信息的缓存签名：只有这些字段变化时才需要重新请求更新。
     *
     * 启用状态、update/remove 标记都会影响远端判断，因此一并纳入。
     */
    private data class ModuleUpdateSignature(
        val updateJson: String,
        val versionCode: Int,
        val enabled: Boolean,
        val update: Boolean,
        val remove: Boolean
    )

    /** 签名 + 更新结果的缓存条目，用于跳过重复的网络检查。 */
    private data class ModuleUpdateCache(
        val signature: ModuleUpdateSignature,
        val info: ModuleUpdateInfo
    )

    private val _uiState = MutableStateFlow(ModuleUiState())
    // 对外只读的模块列表状态，所有写操作都集中在本 ViewModel 内
    val uiState: StateFlow<ModuleUiState> = _uiState.asStateFlow()

    // One-shot UI events (toast/snackbar): buffered Channel, never dropped/duplicated/overwritten
    private val _moduleEvent = Channel<ModuleEffect>(Channel.BUFFERED)
    val moduleEvent: Flow<ModuleEffect> = _moduleEvent.receiveAsFlow()

    // 保护更新缓存的并发访问：多个页面可能同时触发更新检查
    private val updateInfoMutex = Mutex()
    private var updateInfoCache: MutableMap<String, ModuleUpdateCache> = mutableMapOf()
    // 正在请求中的模块 id，避免同一模块并发重复请求
    private val updateInfoInFlight = mutableSetOf<String>()
    private val searchQuery = MutableStateFlow("")

    // 当前列表加载任务；新的加载会取消旧的，防止旧结果覆盖新数据
    private var fetchJob: Job? = null

    // 外部（如安装完成）要求下次进入时强制刷新
    var isNeedRefresh = false
        private set

    init {
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
    }

    /** 标记下次加载需要强制刷新。 */
    fun markNeedRefresh() {
        isNeedRefresh = true
    }

    /**
     * 从设置恢复排序偏好并重建列表。
     *
     * 必须在列表加载前调用，否则会先按默认顺序渲染再跳变。
     */
    fun initializePreferences() {
        _uiState.update {
            it.copy(
                checkModuleUpdate = settingsRepo.checkModuleUpdate,
                sortEnabledFirst = settingsRepo.moduleSortEnabledFirst,
                sortActionFirst = settingsRepo.moduleSortActionFirst,
            )
        }
        updateModuleList()
    }

    /** 切换"可执行模块优先"排序并持久化，随后按新顺序重建列表。 */
    fun toggleSortActionFirst() {
        val newValue = !_uiState.value.sortActionFirst
        settingsRepo.moduleSortActionFirst = newValue
        _uiState.update { it.copy(sortActionFirst = newValue) }
        updateModuleList()
    }

    /** 切换"已启用优先"排序并持久化，随后按新顺序重建列表。 */
    fun toggleSortEnabledFirst() {
        val newValue = !_uiState.value.sortEnabledFirst
        settingsRepo.moduleSortEnabledFirst = newValue
        _uiState.update { it.copy(sortEnabledFirst = newValue) }
        updateModuleList()
    }

    /**
     * 刷新环境信息：是否安装了 Magisk、是否处于安全模式。
     *
     * 这些信息会影响模块操作的可用性，需在页面展示前获取。
     */
    fun refreshEnvironmentState() {
        viewModelScope.launch {
            val magiskInstalled = withContext(Dispatchers.IO) { hasMagisk() }
            val isSafeMode = Natives.isSafeMode
            _uiState.update {
                it.copy(
                    magiskInstalled = magiskInstalled,
                    isSafeMode = isSafeMode,
                )
            }
        }
    }

    /** 更新搜索状态；仅关键字变化时推送去抖通道，避免重复搜索。 */
    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    /** 仅修改搜索关键字。 */
    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    /**
     * 按关键字过滤模块。
     *
     * 支持 id/名称/描述/作者与名称拼音；空关键字返回空列表，由调用方决定搜索态。
     */
    private fun filterModules(modules: List<Module>, text: String): List<Module> {
        if (text.isEmpty()) return emptyList()

        return modules.filter {
            it.id.contains(text, true) || it.name.contains(text, true) ||
                    it.description.contains(text, true) || it.author.contains(text, true) ||
                    PinyinUtil.toPinyin(it.name).contains(text, true)
        }
    }

    /**
     * 执行搜索过滤。
     *
     * 关键字被清空时回到完整列表（而不是空结果），保证退出搜索后列表可见。
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
            updateModuleList()
            return
        }

        val result = withContext(Dispatchers.IO) {
            val state = _uiState.value
            filterModules(state.modules, text).sortedWith(moduleComparator(state))
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchResultStatusFor(text, result.isEmpty())
                )
            )
        }
    }

    /**
     * 重建可见模块列表。
     *
     * resort 为 false 时保持现有顺序（启停/卸载后不跳位），仅刷新内容并追加新增项。
     */
    private fun updateModuleList(resort: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val searchText = state.searchStatus.searchText
            val shorted = if (resort || state.moduleList.isEmpty()) {
                state.modules.sortedWith(moduleComparator(state))
            } else {
                // Order-preserving reload: keep order, refresh data, drop removed, append new (no re-sort on toggle/uninstall)
                val byId = state.modules.associateBy { it.id }
                val existingIds = state.moduleList.mapTo(HashSet()) { it.id }
                state.moduleList.mapNotNull { byId[it.id] } + state.modules.filter { it.id !in existingIds }
            }
            val searchResults = filterModules(shorted, searchText)

            _uiState.update {
                it.copy(
                    moduleList = shorted,
                    searchResults = searchResults,
                    searchStatus = it.searchStatus.copy(
                        resultStatus = searchResultStatusFor(searchText, searchResults.isEmpty())
                    )
                )
            }
        }
    }

    /**
     * 模块排序比较器。
     *
     * 元模块且已启用者永远置顶；随后按"已启用优先/可执行优先"两个开关组合分档，
     * 同档内按模块 id 使用系统语言排序，保证顺序稳定。
     */
    private fun moduleComparator(state: ModuleUiState): Comparator<Module> {
        return compareBy<Module>(
            {
                val executable = it.hasWebUi || it.hasActionScript
                when {
                    it.metamodule && it.enabled -> 0
                    state.sortEnabledFirst && state.sortActionFirst -> when {
                        it.enabled && executable -> 1
                        it.enabled -> 2
                        executable -> 3
                        else -> 4
                    }

                    state.sortEnabledFirst && !state.sortActionFirst -> if (it.enabled) 1 else 2
                    !state.sortEnabledFirst && state.sortActionFirst -> if (executable) 1 else 2
                    else -> 1
                }
            },
            { if (state.sortEnabledFirst) !it.enabled else 0 },
            { if (state.sortActionFirst) !(it.hasWebUi || it.hasActionScript) else 0 },
        ).thenBy(Collator.getInstance(Locale.getDefault()), Module::id)
    }

    /**
     * 读取模块目录并更新状态。
     *
     * 读取失败时降级为空列表而非抛错，界面仍可展示错误之外的已有信息；
     * 结果回主线程后再触发列表重建，避免并发写状态。
     */
    suspend fun loadModuleList(resort: Boolean = true) {
        val parsedModules = withContext(Dispatchers.IO) {
            repo.getModules().getOrElse {
                Log.e(TAG, "fetchModuleList: ", it)
                emptyList()
            }
        }

        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    modules = parsedModules,
                )
            }
            // Trigger recalculation of moduleList
            updateModuleList(resort)
            isNeedRefresh = false
        }
    }

    /**
     * 刷新模块列表，可选同时检查更新。
     *
     * 使用 fetchJob 保证同一时刻只有一个加载任务；无论成功失败都在 finally 中
     * 结束刷新态并标记已加载，避免界面一直转圈。
     */
    fun fetchModuleList(checkUpdate: Boolean = false, resort: Boolean = true) {
        fetchJob?.cancel()
        _uiState.update { it.copy(isRefreshing = true) }
        fetchJob = viewModelScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()

                loadModuleList(resort)

                if (checkUpdate) syncModuleUpdateInfo(_uiState.value.modules)

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}, modules: ${_uiState.value.modules}")
            } finally {
                _uiState.update { it.copy(isRefreshing = false, hasLoaded = true) }
            }
        }
    }

    /** 生成用于判断"是否需要重新检查更新"的签名。 */
    private fun Module.toSignature(): ModuleUpdateSignature {
        return ModuleUpdateSignature(
            updateJson = updateJson,
            versionCode = versionCode,
            enabled = enabled,
            update = update,
            remove = remove
        )
    }

    /**
     * 同步所有模块的可用更新信息。
     *
     * 只请求签名变化且未在请求中的模块；单模块请求超时（5s）视为无更新，
     * 防止某个坏链接拖慢整个列表的检查。
     */
    suspend fun syncModuleUpdateInfo(modules: List<Module>) {
        if (!_uiState.value.checkModuleUpdate) return

        val modulesToFetch = mutableListOf<Triple<String, Module, ModuleUpdateSignature>>()
        val removedIds = mutableSetOf<String>()

        updateInfoMutex.withLock {
            val ids = modules.map { it.id }.toSet()
            // 清理已卸载模块的缓存，避免无限增长
            updateInfoCache.keys.filter { it !in ids }.forEach { removedId ->
                removedIds += removedId
                updateInfoCache.remove(removedId)
                updateInfoInFlight.remove(removedId)
            }

            modules.forEach { module ->
                val signature = module.toSignature()
                val cached = updateInfoCache[module.id]
                // add 成功表示抢占到该模块的请求权，避免并发重复请求
                if ((cached == null || cached.signature != signature) && updateInfoInFlight.add(module.id)) {
                    modulesToFetch += Triple(module.id, module, signature)
                }
            }
        }

        val fetchedEntries = coroutineScope {
            modulesToFetch.map { (id, module, signature) ->
                async {
                    val info = withTimeoutOrNull(5_000L.milliseconds) {
                        withContext(Dispatchers.IO) { checkUpdate(module) }
                    } ?: ModuleUpdateInfo.Empty
                    id to ModuleUpdateCache(signature, info)
                }
            }.awaitAll()
        }

        val changedEntries = mutableListOf<Pair<String, ModuleUpdateInfo>>()
        updateInfoMutex.withLock {
            fetchedEntries.forEach { (id, entry) ->
                val existing = updateInfoCache[id]
                if (existing == null || existing.signature != entry.signature || existing.info != entry.info) {
                    updateInfoCache[id] = entry
                    changedEntries += id to entry.info
                }
                updateInfoInFlight.remove(id)
            }
        }

        // 无变化时不必写状态，减少一次无意义的 Compose 重组
        if (removedIds.isEmpty() && changedEntries.isEmpty()) {
            return
        }

        withContext(Dispatchers.Main) {
            _uiState.update { state ->
                val newMap = state.updateInfo.toMutableMap()
                removedIds.forEach { newMap.remove(it) }
                changedEntries.forEach { (id, info) ->
                    newMap[id] = info
                }
                state.copy(updateInfo = newMap)
            }
        }
    }

    /**
     * 请求更新前的确认信息（含更新日志）。
     *
     * 若仓库校验判定更新源不可信，则拒绝并提示；只有校验通过才构建确认对话框，
     * 保证用户下载的包来自预期来源。
     */
    fun requestUpdateConfirmation(module: Module, updateInfo: ModuleUpdateInfo) {
        viewModelScope.launch {
            val verification = registryRepo.resolveUpdate(
                moduleId = module.id,
                updateJsonUrl = module.updateJson,
                zipUrl = updateInfo.downloadUrl,
                versionCode = updateInfo.versionCode,
            )
            if (verification is UpdateVerification.Rejected) {
                Log.w(TAG, "refusing update of ${module.id}: ${verification.reason}")
                emitEffect(ModuleEffect.Toast(ksuApp.getString(R.string.module_update_unverifiable, module.name)))
                return@launch
            }
            val dialogState = buildUpdateConfirmDialogState(module, updateInfo, verification)
            _uiState.update { it.copy(confirmDialogState = dialogState) }
        }
    }

    /**
     * 构建卸载确认对话框；元模块需使用更严格的警告文案，提示卸载风险。
     */
    fun requestUninstallConfirmation(module: Module) {
        val res = ksuApp.resources
        _uiState.update {
            it.copy(
                confirmDialogState = ModuleConfirmDialogState(
                    request = ModuleConfirmRequest.Uninstall(module),
                    title = res.getString(R.string.module),
                    content = (if (module.metamodule) res.getString(R.string.metamodule_uninstall_confirm) else res.getString(R.string.module_uninstall_confirm)).format(
                        module.name
                    ),
                    confirm = res.getString(R.string.uninstall),
                    dismiss = res.getString(android.R.string.cancel),
                )
            )
        }
    }

    /** 关闭当前确认对话框（用户取消）。 */
    fun dismissConfirmRequest() {
        _uiState.update { it.copy(confirmDialogState = null) }
    }

    /**
     * 发送一次性 UI 事件（Toast/Snackbar）。
     *
     * 使用 trySend 避免在无缓冲消费时阻塞界面线程；通道已满则丢弃最旧事件。
     */
    fun emitEffect(effect: ModuleEffect) {
        _moduleEvent.trySend(effect)
    }

    /**
     * 启用/禁用模块。
     *
     * 成功后以"保持顺序"的方式刷新，避免刚点击的条目跳走；
     * 并提示需要重启才能生效。
     */
    fun toggleModule(module: Module) {
        viewModelScope.launch {
            val res = ksuApp.resources
            val success = withContext(Dispatchers.IO) {
                toggleModuleUtil(module.id, !module.enabled)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
                emitEffect(ModuleEffect.SnackBar(res.getString(R.string.reboot_to_apply)))
            } else {
                val message = if (module.enabled) R.string.module_failed_to_disable else R.string.module_failed_to_enable
                emitEffect(ModuleEffect.SnackBar(res.getString(message).format(module.name)))
            }
        }
    }

    /**
     * 卸载模块：无论成功失败都关闭对话框，并用 Snackbar 告知结果。
     */
    fun uninstallModule(module: Module) {
        viewModelScope.launch {
            val res = ksuApp.resources
            val success = withContext(Dispatchers.IO) {
                uninstallModuleUtil(module.id)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
            }
            _uiState.update { it.copy(confirmDialogState = null) }
            emitEffect(
                ModuleEffect.SnackBar(
                    res.getString(
                        if (success) R.string.module_uninstall_success else R.string.module_uninstall_failed
                    ).format(module.name)
                )
            )
        }
    }

    /**
     * 撤销卸载：依赖 ksud 的"延迟删除"机制恢复模块目录。
     */
    fun undoUninstallModule(module: Module) {
        viewModelScope.launch {
            val res = ksuApp.resources
            val success = withContext(Dispatchers.IO) {
                undoUninstallModuleUtil(module.id)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
            }
            emitEffect(
                ModuleEffect.SnackBar(
                    res.getString(
                        if (success) R.string.module_undo_uninstall_success else R.string.module_undo_uninstall_failed
                    ).format(module.name)
                )
            )
        }
    }

    /**
     * 构建更新确认对话框状态，并按来源安全地获取更新日志。
     *
     * 仓库提供的日志优先且始终使用禁用 JavaScript 的受限渲染；
     * 普通 URL 仅在非仓库地址时才直接请求，最后才回退到默认提示文案。
     */
    private suspend fun buildUpdateConfirmDialogState(
        module: Module,
        updateInfo: ModuleUpdateInfo,
        verification: UpdateVerification,
    ): ModuleConfirmDialogState {
        val res = ksuApp.resources
        val changelogUrl = updateInfo.changelog

        // 更新日志的三种展示形态：纯文本 / HTML / 受限渲染
        var changelog = ""
        var html = false
        // Registry-hosted content is always shown in the restricted (JavaScript-off) renderer.
        var restricted = false

        withContext(Dispatchers.IO) {
            if (verification is UpdateVerification.Registry) {
                // A registry release: only ever show the registry's own release notes.
                verification.release.changelogHtml?.takeIf { it.isNotBlank() }?.let {
                    changelog = it
                    html = true
                    restricted = true
                }
                // 仓库未内嵌日志时尝试按仓库 URL 拉取纯文本
                if (changelog.isBlank() && registryRepo.isRegistryUrl(changelogUrl)) {
                    registryRepo.fetchRegistryText(changelogUrl)?.let {
                        changelog = it
                        restricted = true
                    }
                }
                return@withContext
            }
            if (changelogUrl.isBlank()) return@withContext
            // "#branch@path" 形式表示仓库内的日志文件
            if (changelogUrl.startsWith("#") && changelogUrl.contains('@')) {
                val parts = changelogUrl.substring(1).split('@', limit = 2)
                if (parts.size == 2) {
                    registryRepo.fetchChangelogHtml(parts[0], parts[1])?.let {
                        changelog = it
                        html = true
                        restricted = true
                    }
                }
                return@withContext
            }
            if (registryRepo.isRegistryUrl(changelogUrl)) {
                registryRepo.fetchRegistryText(changelogUrl)?.let {
                    changelog = it
                    restricted = true
                }
                return@withContext
            }
            // 外部地址：失败时保持空字符串，由下方回退为默认文案
            changelog = runCatching {
                ksuApp.okhttpClient.newCall(
                    Request.Builder().url(changelogUrl).build()
                ).execute().use { it.body.string() }
            }.getOrDefault("")
        }

        val registry = verification as? UpdateVerification.Registry
        return ModuleConfirmDialogState(
            request = ModuleConfirmRequest.Update(
                module = module,
                // 优先使用仓库给出的下载地址，确保与校验信息配套
                downloadUrl = registry?.zipUrl ?: updateInfo.downloadUrl,
                fileName = "${module.name}-${updateInfo.version}.zip",
                expected = registry?.expected,
            ),
            title = if (changelog.isNotBlank()) res.getString(R.string.module_changelog) else res.getString(R.string.module_update),
            content = changelog.ifBlank { res.getString(R.string.module_start_downloading).format(module.name) },
            markdown = changelog.isNotBlank() && !html,
            html = changelog.isNotBlank() && html,
            restricted = changelog.isNotBlank() && restricted,
            confirm = res.getString(R.string.module_update),
        )
    }

    /**
     * 查询单个模块的更新信息，失败时返回 Empty 表示"无更新"，避免中断整批检查。
     */
    private suspend fun checkUpdate(m: Module): ModuleUpdateInfo {
        return repo.checkUpdate(m).getOrDefault(ModuleUpdateInfo.Empty)
    }
}
