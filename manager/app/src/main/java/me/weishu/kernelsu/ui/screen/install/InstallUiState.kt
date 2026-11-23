package me.weishu.kernelsu.ui.screen.install

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.util.LkmSelection

/**
 * 安装页的不可变 UI 状态快照，由 [InstallScreen] 统一计算后交给
 * Material/Miuix 两套实现渲染，保证两种主题共享同一份数据来源。
 */
@Immutable
internal data class InstallUiState(
    val installMethod: InstallMethod?,
    val lkmSelection: LkmSelection,
    val partitionSelectionIndex: Int,
    val displayPartitions: List<String>,
    val remotePartitionSelectionIndex: Int,
    val remoteDisplayPartitions: List<String>,
    val currentKmi: String,
    val slotSuffix: String,
    val installMethodOptions: List<InstallMethod>,
    val canSelectPartition: Boolean,
    val advancedOptionsShown: Boolean,
    val allowShell: Boolean,
    val enableAdb: Boolean,
    val forceBackup: Boolean,
    val canForceBackup: Boolean,
)

/**
 * 安装页向外暴露的回调集合；拆成独立数据类便于两套 UI 以相同签名复用，
 * 也避免每次重组时把整组 lambda 当作不稳定参数。
 */
@Immutable
internal data class InstallScreenActions(
    val onBack: () -> Unit,
    val onSelectMethod: (InstallMethod) -> Unit,
    val onDownloadFile: () -> Unit,
    val onSelectBootImage: () -> Unit,
    val onUploadLkm: () -> Unit,
    val onClearLkm: () -> Unit,
    val onSelectPartition: (Int) -> Unit,
    val onNext: () -> Unit,
    val onAdvancedOptionsClicked: () -> Unit,
    val onSelectAllowShell: (Boolean) -> Unit,
    val onSelectEnableAdb: (Boolean) -> Unit,
    val onSelectForceBackup: (Boolean) -> Unit,
)
