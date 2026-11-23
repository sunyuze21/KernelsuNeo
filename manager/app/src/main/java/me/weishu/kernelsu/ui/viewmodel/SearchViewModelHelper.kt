package me.weishu.kernelsu.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.SearchStatus
import kotlin.time.Duration.Companion.milliseconds

// 搜索输入去抖时长：过短会造成输入过程中频繁计算，过长则显得迟钝
private const val SEARCH_DEBOUNCE_MILLIS = 150L

/**
 * 启动搜索关键字收集协程：去抖 + 去重后交给 [onQuery] 执行实际搜索。
 *
 * 使用 collectLatest，新关键字到来时会取消上一次尚未完成的搜索，
 * 避免慢请求的结果覆盖用户最新输入的结果。
 */
@OptIn(FlowPreview::class)
fun CoroutineScope.launchSearchQueryCollector(
    searchQuery: StateFlow<String>,
    onQuery: suspend (String) -> Unit,
): Job {
    return launch {
        searchQuery
            .debounce(SEARCH_DEBOUNCE_MILLIS.milliseconds)
            .distinctUntilChanged()
            .collectLatest(onQuery)
    }
}

/**
 * 根据当前关键字推导"加载中/默认"结果状态。
 *
 * 空关键字表示未搜索，避免页面显示无意义的空结果提示。
 */
fun searchLoadingStatusFor(text: String): SearchStatus.ResultStatus {
    return if (text.isEmpty()) {
        SearchStatus.ResultStatus.DEFAULT
    } else {
        SearchStatus.ResultStatus.LOAD
    }
}

/**
 * 根据关键字与结果数量推导最终展示状态：空关键字为默认、无结果为 EMPTY、否则展示列表。
 */
fun searchResultStatusFor(text: String, isEmpty: Boolean): SearchStatus.ResultStatus {
    return when {
        text.isEmpty() -> SearchStatus.ResultStatus.DEFAULT
        isEmpty -> SearchStatus.ResultStatus.EMPTY
        else -> SearchStatus.ResultStatus.SHOW
    }
}
