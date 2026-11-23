package me.weishu.kernelsu.ui.screen.flash

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * 刷写页不可变状态。[showRebootAction] 由刷写结果决定按钮是“重启”还是“关闭”；
 * [showJailbreakWarning] 为 true 时任务尚未启动，必须先确认警告。
 */
@Immutable
data class FlashUiState(
    val text: String,
    val showRebootAction: Boolean,
    val flashingStatus: FlashingStatus,
    val showJailbreakWarning: Boolean,
    @param:StringRes val rebootLabelRes: Int,
    val actionInProgress: Boolean = false,
)

/** 刷写页回调集合；[onReboot] 触发前需先置位 [FlashUiState.actionInProgress] 防重复点击。 */
@Immutable
data class FlashScreenActions(
    val onBack: () -> Unit,
    val onSaveLog: () -> Unit,
    val onReboot: () -> Unit,
    val onClose: () -> Unit = {},
    val onConfirmJailbreakWarning: () -> Unit,
    val onDismissJailbreakWarning: () -> Unit,
)
