package me.weishu.kernelsu.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.data.repository.SuperUserRepository
import me.weishu.kernelsu.data.repository.SuperUserRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.SearchStatus
import me.weishu.kernelsu.ui.screen.superuser.GroupedApps
import me.weishu.kernelsu.ui.screen.superuser.SuperUserUiState
import me.weishu.kernelsu.ui.util.PinyinUtil
import me.weishu.kernelsu.ui.util.ownerNameForUid
import me.weishu.kernelsu.ui.util.pickPrimary
import java.text.Collator
import java.util.Locale

// "最近安装"分组的时间窗口：一小时内安装的应用会额外展示，便于用户快速授权
internal const val RECENTLY_INSTALLED_WINDOW_MILLIS = 60 * 60 * 1000L

/**
 * 应用列表排序字段。
 *
 * 枚举顺序参与整数编码（见 [AppSortConfig.toInt]），调整顺序会破坏已保存的设置。
 */
enum class AppSortType {
    NAME, PACKAGE_NAME, INSTALL_TIME, UPDATE_TIME;

    companion object {
        /** 按序号还原排序字段，越界时回退为按名称排序。 */
        fun fromOrdinal(ordinal: Int): AppSortType =
            entries.getOrElse(ordinal) { NAME }
    }
}

/**
 * 排序配置：字段 + 是否倒序。
 *
 * 持久化时压成单个 Int（字段序号 * 2 + 倒序位），避免额外存储结构变化。
 */
data class AppSortConfig(
    val sortType: AppSortType = AppSortType.NAME,
    val reversed: Boolean = false,
) {
    /** 编码为持久化整数，低位表示是否倒序。 */
    fun toInt(): Int = sortType.ordinal * 2 + if (reversed) 1 else 0

    /** 仅替换排序字段，保留倒序状态。 */
    fun withType(type: AppSortType): AppSortConfig = copy(sortType = type)
    /** 翻转倒序状态，用于点击已选中字段时切换升降序。 */
    fun toggleReversed(): AppSortConfig = copy(reversed = !reversed)

    companion object {
        /** 从持久化整数还原配置，保证与 [toInt] 互逆。 */
        fun fromInt(value: Int): AppSortConfig = AppSortConfig(
            sortType = AppSortType.fromOrdinal(value / 2),
            reversed = value % 2 != 0,
        )
    }
}

/**
 * 过滤出最近安装（[RECENTLY_INSTALLED_WINDOW_MILLIS] 内）的分组，按安装时间倒序排列。
 *
 * 时间相同时使用系统语言的 Collator 排序，保证结果稳定；
 * 传入 nowMillis 便于测试固定时间点。
 */
internal fun buildRecentlyInstalledGroups(
    groups: List<GroupedApps>,
    nowMillis: Long = System.currentTimeMillis(),
): List<GroupedApps> {
    val cutoffMillis = nowMillis - RECENTLY_INSTALLED_WINDOW_MILLIS
    val collator = Collator.getInstance(Locale.getDefault())

    return groups.mapNotNull { group ->
        // 同一 uid 可能有多个包，取最新安装时间作为该分组的代表
        val latestInstallTime = group.apps.maxOfOrNull { it.packageInfo.firstInstallTime } ?: return@mapNotNull null
        if (latestInstallTime < cutoffMillis) {
            null
        } else {
            group to latestInstallTime
        }
    }.sortedWith(
        compareByDescending<Pair<GroupedApps, Long>> { it.second }
            .thenBy(collator) { it.first.primary.label }
    ).map { it.first }
}

/**
 * 超级用户页面 ViewModel：加载应用列表、分组、过滤、排序与搜索。
 *
 * 应用扫描结果放在 companion 中做进程级缓存，因为扫描成本高且多个页面/进程入口都会用到；
 * ViewModel 自身只维护与当前页面相关的可见列表。
 */
class SuperUserViewModel(
    private val repo: SuperUserRepository = SuperUserRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    companion object {
        // 日志标签，应用扫描失败时用于定位
        private const val TAG = "SuperUserViewModel"

        private val appsLock = Any()
        // 进程级缓存：原始应用列表与用户 id 列表，供静态查询复用
        private var cachedApps: List<AppInfo> = emptyList()
        private var cachedUserIds: List<Int> = emptyList()
        // 正在进行的扫描任务；并发调用方共享同一结果而不是重复扫描
        private var appListScan: Deferred<Result<Pair<List<AppInfo>, List<Int>>>>? = null
        // 扫描不依赖 ViewModel 生命周期，使用独立作用域避免页面销毁时中断
        private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val groupedAppsLock = Any()
        // 分组后的缓存，供 getGroupedApp 按 uid 快速查询（如 WebUI 场景）
        private var cachedGroupedApps: List<GroupedApps> = emptyList()

        /** 返回最近一次扫描得到的原始应用列表（可能为空）。 */
        val apps: List<AppInfo>
            get() = synchronized(appsLock) { cachedApps }

        // Callers join a scan in flight instead of starting a parallel one. useCache = false
        // still rescans when none is running, so a fresh screen sees newly installed apps.
        /**
         * 加载应用列表（含各用户 id）。
         *
         * useCache 为 true 且已有缓存时直接返回；否则复用进行中的扫描或新起一个。
         * 成功结果会写回进程级缓存，因此后续调用可能直接命中。
         */
        suspend fun loadApps(
            useCache: Boolean,
            repo: SuperUserRepository = SuperUserRepositoryImpl(),
        ): Result<Pair<List<AppInfo>, List<Int>>> {
            val scan = synchronized(appsLock) {
                if (useCache && cachedApps.isNotEmpty()) return Result.success(cachedApps to cachedUserIds)
                appListScan?.takeIf { it.isActive } ?: scanScope.async {
                    repo.getAppList()
                        .onSuccess { (apps, ids) ->
                            synchronized(appsLock) {
                                cachedApps = apps
                                cachedUserIds = ids
                            }
                        }
                        .onFailure { Log.e(TAG, "loadApps failed", it) }
                }.also { appListScan = it }
            }
            return scan.await()
        }

        /**
         * 按 uid 查询缓存中的分组信息，未命中返回 null。
         *
         * 加锁是因为缓存可能被后台协程替换，读取必须看到一致快照。
         */
        @JvmStatic
        fun getGroupedApp(uid: Int): GroupedApps? {
            return synchronized(groupedAppsLock) { cachedGroupedApps.find { it.uid == uid } }
        }
    }

    /** 应用模型简写，避免本文件重复书写完整包名。 */
    typealias AppInfo = me.weishu.kernelsu.data.model.AppInfo

    private val _uiState = MutableStateFlow(SuperUserUiState())
    // 对外只读的应用列表状态，扫描/排序结果统一写入这里
    val uiState: StateFlow<SuperUserUiState> = _uiState.asStateFlow()

    // 串行化列表刷新，避免两次刷新交错写入导致状态与缓存不一致
    private val refreshMutex = Mutex()
    private val searchQuery = MutableStateFlow("")
    // 当前排序任务；快速切换排序时取消上一次，防止旧顺序覆盖新选择
    private var sortJob: Job? = null
    // 标记外部（如授权变化）要求下次进入时重新拉取
    var isNeedRefresh = false
        private set

    init {
        // 搜索输入经去抖后触发过滤
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
    }

    /** 请求下次加载时强制刷新，通常由外部修改授权/配置后调用。 */
    fun markNeedRefresh() {
        isNeedRefresh = true
    }

    /**
     * 从设置中恢复显示与排序偏好。
     *
     * 只更新偏好字段，不触发扫描，使界面能先以正确筛选条件渲染缓存数据。
     */
    fun initializePreferences() {
        _uiState.update {
            it.copy(
                showSystemApps = settingsRepo.superuserShowSystemApps,
                showOnlyPrimaryUserApps = settingsRepo.superuserShowOnlyPrimaryUserApps,
                sortConfig = AppSortConfig.fromInt(settingsRepo.superuserSortOption),
            )
        }
    }

    /**
     * 切换排序配置并异步重排当前列表。
     *
     * 返回 Job 便于调用方等待完成（例如滚动到顶部后恢复交互）；
     * 列表为空时直接跳过，避免无意义计算。
     */
    fun updateSortConfig(config: AppSortConfig): Job {
        settingsRepo.superuserSortOption = config.toInt()
        _uiState.update { it.copy(sortConfig = config) }
        sortJob?.cancel()
        return viewModelScope.launch {
            val current = _uiState.value.groupedApps
            if (current.isEmpty()) return@launch
            updateVisibleApps(current)
        }.also { sortJob = it }
    }

    /**
     * 显示条件变化后重新过滤并分组。
     *
     * 过滤需要读取每个应用的 flags，放到 IO 线程执行以防卡顿。
     */
    private fun refilterVisibleApps(): Job = viewModelScope.launch {
        // Re-filter when a filter setting changes
        val grouped = withContext(Dispatchers.IO) {
            buildGroups(filterApps(apps))
        }
        updateVisibleApps(grouped)
    }

    /** 切换"显示系统应用"并立即重新过滤。 */
    fun toggleShowSystemApps(): Job {
        val newValue = !_uiState.value.showSystemApps
        settingsRepo.superuserShowSystemApps = newValue
        _uiState.update { it.copy(showSystemApps = newValue) }
        return refilterVisibleApps()
    }

    /** 切换"仅显示主用户应用"并立即重新过滤。 */
    fun toggleShowOnlyPrimaryUserApps(): Job {
        val newValue = !_uiState.value.showOnlyPrimaryUserApps
        settingsRepo.superuserShowOnlyPrimaryUserApps = newValue
        _uiState.update { it.copy(showOnlyPrimaryUserApps = newValue) }
        return refilterVisibleApps()
    }

    /**
     * 更新搜索状态；仅关键字变化时才推送去抖通道，防止筛选条件变化触发重复搜索。
     */
    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    /** 仅修改搜索关键字，复用 [updateSearchStatus] 的同步逻辑。 */
    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    /**
     * 在分组内搜索匹配的应用。
     *
     * 匹配项排在分组内最前（通过 matchedIdentifiers 标记），使展开分组时优先看到命中的应用；
     * 空关键字返回空列表，由调用方决定是否处于搜索态。
     */
    private fun filterSearchResults(groups: List<GroupedApps>, text: String): List<GroupedApps> {
        if (text.isEmpty()) return emptyList()

        return groups.mapNotNull { group ->
            val matchedIdentifiers = group.apps.filter {
                it.label.contains(text, true) ||
                        it.displayIdentifier.contains(text, true) ||
                        PinyinUtil.toPinyin(it.label).contains(text, true)
            }.mapTo(linkedSetOf()) { it.displayIdentifier }

            if (matchedIdentifiers.isEmpty()) {
                null
            } else {
                val sortedApps = group.apps.sortedWith(
                    compareByDescending { it.displayIdentifier in matchedIdentifiers }
                )
                group.copy(
                    apps = sortedApps,
                    matchedIdentifiers = matchedIdentifiers,
                )
            }
        }
    }

    /**
     * 执行搜索：先置加载态，空关键字则清空结果，否则在 IO 线程过滤并写回。
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

        val result = withContext(Dispatchers.IO) {
            filterSearchResults(_uiState.value.groupedApps, text)
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, result.isEmpty()))
            )
        }
    }

    /** 更新静态查询使用的分组缓存，需加锁保证其他线程读到完整列表。 */
    private fun updateCachedGroupedApps(grouped: List<GroupedApps>) {
        synchronized(groupedAppsLock) {
            cachedGroupedApps = grouped
        }
    }

    /**
     * 排序并同步派生列表（搜索结果、最近安装）。
     *
     * resort 为 false 时保留现有顺序（例如仅刷新某应用的授权状态），
     * 以免用户正在浏览的列表突然跳动。
     */
    private suspend fun updateVisibleApps(grouped: List<GroupedApps>, resort: Boolean = true) {
        val sortConfig = _uiState.value.sortConfig
        val searchText = _uiState.value.searchStatus.searchText
        val (sorted, searchResults, recentlyInstalledResults) = withContext(Dispatchers.IO) {
            val s = if (resort) sortGroups(grouped, sortConfig) else grouped
            Triple(s, filterSearchResults(s, searchText), buildRecentlyInstalledGroups(s))
        }
        _uiState.update {
            it.copy(
                groupedApps = sorted,
                recentlyInstalledResults = recentlyInstalledResults,
                searchResults = searchResults,
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchResultStatusFor(searchText, searchResults.isEmpty())
                )
            )
        }
    }

    /**
     * 按用户设置过滤可见应用。
     *
     * 规则：排除 Manager 自身；WebView zygote 始终展示（供 WebUI 调试）；
     * 已授权或自定义配置的应用始终展示，避免用户找不到已配置项；
     * 其余按"仅主用户/是否系统应用"过滤，但 uid 2000（shell）例外。
     */
    private fun filterApps(list: List<AppInfo>): List<AppInfo> {
        val currentState = _uiState.value

        return list.filter {
            if (it.packageName == ksuApp.packageName) return@filter false
            // UID 1053 is the single system-wide WebView zygote (primary user only).
            if (it.isWebViewZygote) return@filter true
            if (it.allowSu || it.hasCustomProfile) {
                return@filter true
            }
            // 多用户 uid 形如 userId * 100000 + appId，除以 100000 得到 userId
            val userFilter = !currentState.showOnlyPrimaryUserApps || it.uid / 100000 == 0
            val isSystemApp = it.packageInfo.applicationInfo!!.flags.and(ApplicationInfo.FLAG_SYSTEM) != 0
            val typeFilter = it.uid == 2000
                    || currentState.showSystemApps
                    || !isSystemApp
            userFilter && typeFilter
        }
    }

    /** 构建未经过滤的分组缓存（只排除 Manager 自身），用于静态 uid 查询。 */
    private fun buildCachedGroups(apps: List<AppInfo>): List<GroupedApps> {
        return buildGroups(apps.filter { it.packageName != ksuApp.packageName })
    }

    /**
     * 把应用列表按 uid 分组，并计算展示所需的派生字段。
     *
     * 组内排序规则：已授权 > 有自定义配置 > 其余，再按名称；
     * umount 回调可注入以减少重复的内核调用（批量刷新时会预先算好映射）。
     */
    private fun buildGroups(
        apps: List<AppInfo>,
        umount: (Int) -> Boolean = { Natives.uidShouldUmount(it) },
    ): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val comparator = compareBy<AppInfo> {
            when {
                it.allowSu -> 0
                it.hasCustomProfile -> 1
                else -> 2
            }
        }.thenBy(collator) { it.label }
        return apps.groupBy { it.uid }.map { (uid, list) ->
            val sorted = list.sortedWith(comparator)
            val primary = pickPrimary(sorted)
            val shouldUmount = umount(uid)
            // 仅当同一 uid 有多个包（如多开）时才需要展示归属者名称
            val ownerName = if (sorted.size > 1) ownerNameForUid(uid, sorted) else null

            GroupedApps(
                uid = uid,
                apps = sorted,
                primary = primary,
                anyAllowSu = sorted.any { it.allowSu },
                anyCustom = sorted.any { it.hasCustomProfile },
                shouldUmount = shouldUmount,
                ownerName = ownerName
            )
        }
    }

    /**
     * 分组优先级：已授权最前，其次有自定义配置，再次多包分组，最后普通应用。
     *
     * 该优先级优先于用户选择的排序字段，保证重要条目不会被淹没。
     */
    private fun groupRank(group: GroupedApps): Int = when {
        group.anyAllowSu -> 0
        group.anyCustom -> 1
        group.apps.size > 1 -> 2
        else -> 3
    }

    /**
     * 按配置对分组排序。
     *
     * 先按 [groupRank] 分桶，桶内再按字段排序；倒序只作用于桶内比较，
     * 避免把已授权应用排到列表末尾。
     */
    private fun sortGroups(groups: List<GroupedApps>, config: AppSortConfig): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val base: Comparator<GroupedApps> = when (config.sortType) {
            AppSortType.PACKAGE_NAME -> compareBy { it.primary.displayIdentifier }
            AppSortType.INSTALL_TIME -> compareBy { it.primary.packageInfo.firstInstallTime }
            AppSortType.UPDATE_TIME -> compareBy { it.primary.packageInfo.lastUpdateTime }
            AppSortType.NAME -> Comparator { a, b -> collator.compare(a.primary.label, b.primary.label) }
        }
        val secondary = if (config.reversed) base.reversed() else base

        return groups.sortedWith(Comparator { a, b ->
            val ra = groupRank(a)
            val rb = groupRank(b)
            if (ra != rb) ra - rb else secondary.compare(a, b)
        })
    }

    /**
     * 完整拉取应用列表并重建分组。
     *
     * 加锁避免与配置文件刷新并发；先构建未过滤缓存再构建可见列表，
     * umount 状态复用缓存结果，减少对内核的重复查询。
     */
    suspend fun fetchAppList() {
        refreshMutex.withLock {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            loadApps(useCache = false, repo = repo).onSuccess { (newApps, ids) ->
                val (cachedGroups, grouped) = withContext(Dispatchers.IO) {
                    val cached = buildCachedGroups(newApps)
                    val umountByUid = cached.associate { it.uid to it.shouldUmount }
                    cached to buildGroups(filterApps(newApps)) { umountByUid[it] ?: Natives.uidShouldUmount(it) }
                }

                updateCachedGroupedApps(cachedGroups)
                updateVisibleApps(grouped)
                _uiState.update { it.copy(userIds = ids, isRefreshing = false, hasLoaded = true) }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        hasLoaded = true,
                        error = e
                    )
                }
            }

            isNeedRefresh = false
        }
    }

    /**
     * 仅刷新各应用的配置档（授权/自定义配置），不重新扫描安装列表。
     *
     * 比全量扫描快得多，适合从配置页返回时调用；
     * resort 为 false 时按 uid 复用原顺序，避免列表跳动。
     */
    private suspend fun refreshAppList(resort: Boolean = true) {
        refreshMutex.withLock {
            val currentApps = synchronized(appsLock) { cachedApps }
            if (currentApps.isEmpty()) return

            repo.refreshProfiles(currentApps).onSuccess { updatedApps ->
                // Update cache for static method
                synchronized(appsLock) { cachedApps = updatedApps }

                val (cachedGroups, grouped) = withContext(Dispatchers.IO) {
                    val cached = buildCachedGroups(updatedApps)
                    val umountByUid = cached.associate { it.uid to it.shouldUmount }
                    val visible = buildGroups(filterApps(updatedApps)) {
                        umountByUid[it] ?: Natives.uidShouldUmount(it)
                    }
                    val result = if (resort) {
                        visible
                    } else {
                        // 复用旧顺序；缺失的分组才回退到实时查询 umount 状态
                        val byUid = visible.associateBy { it.uid }
                        _uiState.value.groupedApps.map { group ->
                            byUid[group.uid] ?: group.copy(shouldUmount = Natives.uidShouldUmount(group.uid))
                        }
                    }
                    cached to result
                }
                updateCachedGroupedApps(cachedGroups)

                updateVisibleApps(grouped, resort = resort)
                _uiState.update { it.copy(isRefreshing = false) }
                isNeedRefresh = false
            }
        }
    }

    /**
     * 页面入口：根据当前状态决定全量扫描还是仅刷新配置。
     *
     * force 为 true 时必定重新扫描；否则已有数据时走轻量刷新，
     * 返回 Job 便于调用方等待完成。
     */
    fun loadAppList(force: Boolean = false, resort: Boolean = true): Job {
        return viewModelScope.launch {
            if (force || _uiState.value.groupedApps.isEmpty()) {
                fetchAppList()
            } else {
                refreshAppList(resort)
            }
        }
    }

}
