package me.weishu.kernelsu.ui.screen.sulog

import me.weishu.kernelsu.ui.util.SulogEntry
import me.weishu.kernelsu.ui.util.SulogEventFilter
import me.weishu.kernelsu.ui.util.SulogFile

/**
 * SuLog 页面状态。[entries] 是当前文件的原始日志，
 * [visibleEntries] 是应用搜索与事件过滤后的结果，UI 只渲染后者。
 * [sulogStatus] 使用 "unsupported"/"managed"/"forced"/"supported" 字符串
 * 区分内核特性状态，用于决定是否展示警告横幅。
 */
data class SulogScreenState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,
    val searchText: String = "",
    val selectedFilters: Set<SulogEventFilter> = emptySet(),
    val files: List<SulogFile> = emptyList(),
    val selectedFilePath: String? = null,
    val entries: List<SulogEntry> = emptyList(),
    val visibleEntries: List<SulogEntry> = emptyList(),
    val errorMessage: String? = null,
)

/** SuLog 页面回调；[onSelectFile] 传入日志文件路径，切换时需要重新读取。 */
data class SulogActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onEnableSulog: () -> Unit,
    val onCleanFile: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onToggleFilter: (SulogEventFilter) -> Unit,
    val onSelectFile: (String) -> Unit,
)

/** 日志文件下拉框的展示数据；[selectedIndex] 为 -1 表示没有可选文件。 */
data class SulogFileSelector(
    val items: List<String>,
    val selectedIndex: Int,
)
