package me.weishu.kernelsu.ui.screen.executemoduleaction

import androidx.compose.runtime.Immutable

/**
 * 模块 action 执行页的展示状态。
 *
 * [text] 是已累积的 stdout 文本（可能被 ANSI 清屏指令重置），
 * [isComplete] 用于禁用返回并展示关闭按钮；脚本执行中不允许退出，
 * 避免用户在脚本仍写日志时销毁页面。
 */
@Immutable
data class ExecuteModuleActionUiState(
    val text: String,
    val isComplete: Boolean = false,
    /** Top bar title; defaults to "Action". */
    val title: String? = null,
    /** Outcome shown below the output once complete (one-shot scripts). */
    val resultNotice: ActionResultNotice? = null,
)

/** action 执行结果的三种语义，用于选择不同的提示配色。 */
enum class ActionResultKind { SUCCESS, NOT_APPLICABLE, FAILURE }

/** 执行结束后展示在输出末尾的一次性结果提示。 */
@Immutable
data class ActionResultNotice(val kind: ActionResultKind, val text: String)

/** 模块 action 页的用户操作回调。 */
@Immutable
data class ExecuteModuleActionScreenActions(
    val onBack: () -> Unit,
    val onSaveLog: () -> Unit,
    val onClose: () -> Unit = {},
)
