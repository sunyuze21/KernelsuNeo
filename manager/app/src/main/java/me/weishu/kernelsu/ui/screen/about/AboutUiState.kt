package me.weishu.kernelsu.ui.screen.about

import androidx.compose.runtime.Immutable

/**
 * 关于页的不可变状态。
 * links 由 [extractLinks] 从 HTML 字符串中解析，UI 层只负责逐条渲染。
 */
@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
    val links: List<LinkInfo>,
)

/** 关于页动作集合：返回上一页或打开外部链接。 */
@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
    val onOpenLink: (String) -> Unit,
)
