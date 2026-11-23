package me.weishu.kernelsu.ui.screen.sulog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.SulogEntry
import me.weishu.kernelsu.ui.util.SulogEventFilter
import me.weishu.kernelsu.ui.util.SulogEventType
import me.weishu.kernelsu.ui.util.SulogFile
import me.weishu.kernelsu.ui.util.toSulogDisplayName
import me.weishu.kernelsu.ui.viewmodel.SulogViewModel

/**
 * SuLog 页面入口：进入时读取最新日志，状态由 ViewModel 持有，
 * 主题切换只替换渲染层。
 */
@Composable
fun SulogScreen() {
    val navigator = LocalNavigator.current
    val uiMode = LocalUiMode.current
    val viewModel = viewModel<SulogViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 仅在首次组合时加载，避免每次重组都重新读文件
    LaunchedEffect(Unit) {
        viewModel.refreshLatest()
    }

    // 把 ViewModel 状态转换为 UI 只读快照，方便两套主题复用
    val state = SulogScreenState(
        isLoading = uiState.isLoading,
        isRefreshing = uiState.isRefreshing,
        sulogStatus = uiState.sulogStatus,
        isSulogEnabled = uiState.isSulogEnabled,
        searchText = uiState.searchText,
        selectedFilters = uiState.selectedFilters,
        files = uiState.files,
        selectedFilePath = uiState.selectedFilePath,
        entries = uiState.entries,
        visibleEntries = uiState.visibleEntries,
        errorMessage = uiState.errorMessage,
    )
    // 回调全部委托给 ViewModel，UI 不直接接触文件或内核接口
    val actions = SulogActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRefresh = viewModel::refreshLatest,
        onEnableSulog = viewModel::enableSulog,
        onCleanFile = viewModel::cleanFile,
        onSearchTextChange = viewModel::setSearchText,
        onToggleFilter = viewModel::toggleFilter,
        onSelectFile = viewModel::refresh,
    )

    // 主题只影响外观，日志数据与过滤逻辑完全共享
    when (uiMode) {
        UiMode.Material -> SulogScreenMaterial(state, actions)
        UiMode.Miuix -> SulogScreenMiuix(state, actions)
    }
}

/** 把事件过滤器枚举映射为本地化名称，供筛选项和标签复用。 */
@Composable
fun sulogFilterLabel(filter: SulogEventFilter): String {
    return when (filter) {
        SulogEventFilter.RootExecve -> stringResource(R.string.sulog_filter_root_execve)
        SulogEventFilter.SuCompat -> stringResource(R.string.sulog_filter_sucompat)
        SulogEventFilter.IoctlGrantRoot -> stringResource(R.string.sulog_filter_ioctl_grant_root)
        SulogEventFilter.DaemonEvent -> stringResource(R.string.sulog_filter_daemon_restart)
    }
}

/**
 * 日志条目标题：root execve 优先展示进程名，未知事件则把类型名
 * 转成可读文本，避免直接暴露内核字段格式。
 */
@Composable
fun sulogEntryTitle(entry: SulogEntry): String {
    return when (entry.eventType) {
        SulogEventType.RootExecve -> entry.fields["comm"] ?: stringResource(R.string.sulog_filter_root_execve)
        SulogEventType.SuCompat -> stringResource(R.string.sulog_filter_sucompat)
        SulogEventType.IoctlGrantRoot -> stringResource(R.string.sulog_filter_ioctl_grant_root)
        SulogEventType.DaemonEvent -> stringResource(R.string.sulog_filter_daemon_restart)
        SulogEventType.Dropped -> "Dropped"
        SulogEventType.Unknown -> entry.fields["type"]?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Unknown"
    }
}

/** 条目的次要说明：守护进程显示 boot_id，丢弃事件显示时间戳，其余显示命令或文件。 */
@Composable
fun sulogEntryDescription(entry: SulogEntry): String? {
    return when (entry.eventType) {
        SulogEventType.DaemonEvent -> entry.fields["boot_id"]?.let { "Boot ID: $it" }
        SulogEventType.Dropped -> entry.fields["ts_ns"]?.let { "Timestamp: $it" }
        else -> entry.fields["argv"] ?: entry.fields["file"]
    }
}

/**
 * 生成条目上的摘要标签：普通事件显示进程名/PID/UID，
 * 守护进程与丢弃事件使用各自的专用字段。
 */
fun sulogEntrySummaryTags(entry: SulogEntry): List<String> {
    val comm = entry.fields["comm"]
    val pid = entry.fields["pid"]
    val uid = entry.fields["uid"]
    return when (entry.eventType) {
        SulogEventType.DaemonEvent -> listOfNotNull(entry.fields["restart"]?.let { "Restart #$it" } ?: "Daemon restarted")
        SulogEventType.Dropped -> listOfNotNull(entry.fields["dropped"]?.let { "$it lost" })
        else -> listOfNotNull(comm?.takeIf { it.isNotBlank() }, pid?.let { "PID $it" }, uid?.let { "UID $it" })
    }
}

/** 详情弹窗正文：保留字段原始顺序，键名加粗、值保持等宽可复制。 */
fun sulogEntryDetailText(entry: SulogEntry) = buildAnnotatedString {
    entry.fields.entries.forEachIndexed { index, (key, value) ->
        if (index > 0) append('\n')
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append("$key: ")
        }
        append(value)
    }
}

/** 从 retval 字段解析执行结果；字段缺失或非数字时不展示状态。 */
fun sulogEntryStatus(entry: SulogEntry): String? {
    return entry.fields["retval"]?.toIntOrNull()?.let(::formatSulogStatus)
}

/** 0 视为成功，其余按退出码展示，便于快速定位失败调用。 */
private fun formatSulogStatus(retval: Int): String {
    return if (retval == 0) "Success" else "Exit $retval"
}

/**
 * 构建日志文件下拉框数据。选中路径可能因文件轮转消失，
 * 此时回退到第一个文件而不是保持 -1，保证 UI 始终有可选项。
 */
fun buildSulogFileSelector(
    files: List<SulogFile>,
    selectedFilePath: String?,
): SulogFileSelector {
    if (files.isEmpty()) {
        return SulogFileSelector(
            items = emptyList(),
            selectedIndex = -1,
        )
    }

    // 找不到原选中文件（可能已轮转）时默认展示最新/第一个文件
    val selectedIndex = files.indexOfFirst { it.path == selectedFilePath }
        .takeIf { it >= 0 }
        ?: 0

    return SulogFileSelector(
        items = files.map { it.name.toSulogDisplayName() },
        selectedIndex = selectedIndex,
    )
}
