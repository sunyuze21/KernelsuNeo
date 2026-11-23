package me.weishu.kernelsu.ui.screen.template

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.TemplateInfo

/**
 * 模板列表页的展示状态。
 *
 * [templates] 保存最近一次成功拉取的完整结果，[templateList] 是当前要渲染的
 * 列表；保留两者便于刷新时做对比/占位，[error] 只表示加载失败原因。
 */
@Immutable
data class TemplateUiState(
    val isRefreshing: Boolean = false,
    // 由网络状态实时推导，true 时列表为空需展示重试入口
    val offline: Boolean = false,
    val templates: List<TemplateInfo> = emptyList(),
    val templateList: List<TemplateInfo> = emptyList(),
    val error: Throwable? = null,
)

/** 模板列表页的用户操作回调。 */
@Immutable
data class TemplateActions(
    val onBack: () -> Unit,
    // 参数为 true 表示用户主动刷新，需要绕过缓存
    val onRefresh: (Boolean) -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onCreateTemplate: () -> Unit,
    val onOpenTemplate: (TemplateInfo) -> Unit,
)
