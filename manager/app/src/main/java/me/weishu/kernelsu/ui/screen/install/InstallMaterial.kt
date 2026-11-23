package me.weishu.kernelsu.ui.screen.install

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedCheckboxItem
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedDropdownItem
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedRadioItem
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.util.LkmSelection

/**
 * Material 3 主题下的安装页：分区选择、LKM 上传、高级选项与下一步按钮。
 * 仅负责渲染 [InstallUiState]，所有副作用通过 [InstallScreenActions] 上抛。
 *
 * @author weishu
 * @date 2024/3/12.
 */
@Composable
internal fun InstallScreenMaterial(
    uiState: InstallUiState,
    actions: InstallScreenActions,
    snackBarHost: SnackbarHostState,
) {
    // 列表滚动时顶部大标题渐隐，进入折叠状态
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            TopBar(
                onBack = actions.onBack,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.safeDrawingPadding()) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            SelectInstallMethod(
                state = uiState,
                onSelected = actions.onSelectMethod,
                onDownloadFile = actions.onDownloadFile,
                onSelectBootImage = actions.onSelectBootImage,
            )

            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                content = buildList {
                    // 下载方式使用远端 zip 探测出的分区列表，其余使用本机分区
                    val isDownload = uiState.installMethod is InstallMethod.DownloadFile
                    val partitionItems = if (isDownload) {
                        uiState.remoteDisplayPartitions
                    } else {
                        uiState.displayPartitions
                    }
                    val partitionIndex = if (isDownload) {
                        uiState.remotePartitionSelectionIndex
                    } else {
                        uiState.partitionSelectionIndex
                    }
                    if (partitionItems.isNotEmpty()) add {
                        SegmentedDropdownItem(
                            enabled = uiState.canSelectPartition,
                            items = partitionItems,
                            selectedIndex = partitionIndex,
                            title = if (isDownload) {
                                stringResource(R.string.install_select_partition)
                            } else {
                                "${stringResource(R.string.install_select_partition)} (${uiState.slotSuffix})"
                            },
                            onItemSelected = actions.onSelectPartition,
                            icon = Icons.Filled.Edit
                        )
                    }
                    // 仅选文件安装时提供强制备份，避免对直接安装产生误导
                    if (uiState.canForceBackup) add {
                        SegmentedCheckboxItem(
                            title = stringResource(R.string.install_force_backup),
                            summary = stringResource(R.string.install_force_backup_summary),
                            onCheckedChange = actions.onSelectForceBackup,
                            checked = uiState.forceBackup,
                        )
                    }
                    add {
                        SegmentedListItem(
                            leadingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                    null
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.install_upload_lkm_file)) },
                            supportingContent = {
                                (uiState.lkmSelection as? LkmSelection.LkmUri)?.let {
                                    Text(
                                        stringResource(
                                            R.string.selected_lkm,
                                            it.uri.lastPathSegment ?: "(file)"
                                        )
                                    )
                                }
                            },
                            trailingContent = {
                                if (uiState.lkmSelection is LkmSelection.LkmUri) {
                                    IconButton(onClick = actions.onClearLkm) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(android.R.string.cancel)
                                        )
                                    }
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            },
                            onClick = actions.onUploadLkm
                        )
                    }
                }
            )

            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                item {
                    // 展开状态用箭头 180° 旋转表示，动画仅影响图标
                    val rotationState by animateFloatAsState(
                        targetValue = if (uiState.advancedOptionsShown) 180f else 0f,
                        label = "RotationAnimation"
                    )
                    SegmentedListItem(
                        headlineContent = { Text(stringResource(R.string.advanced_options)) },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.expand),
                                modifier = Modifier.graphicsLayer { rotationZ = rotationState }
                            )
                        },
                        onClick = actions.onAdvancedOptionsClicked
                    )
                }
                item(visible = uiState.advancedOptionsShown) {
                    SegmentedCheckboxItem(
                        title = stringResource(id = R.string.allow_shell),
                        summary = stringResource(id = R.string.allow_shell_summary),
                        checked = uiState.allowShell,
                        onCheckedChange = actions.onSelectAllowShell,
                    )
                }
                item(visible = uiState.advancedOptionsShown) {
                    SegmentedCheckboxItem(
                        title = stringResource(id = R.string.enable_adb),
                        summary = stringResource(id = R.string.enable_adb_summary),
                        checked = uiState.enableAdb,
                        onCheckedChange = actions.onSelectEnableAdb,
                    )
                }
            }
            // 未选择安装方式前禁用“下一步”，避免进入刷写页时参数缺失
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = uiState.installMethod != null,
                onClick = actions.onNext
            ) { Text(stringResource(R.string.install_next)) }
        }
    }
}

/**
 * 渲染安装方式单选列表。选择文件/下载会先触发外部流程，真正的安装方式
 * 由后续回调确定；OTA 安装因会改动非活动槽位，需二次确认。
 */
@Composable
private fun SelectInstallMethod(
    state: InstallUiState,
    onSelected: (InstallMethod) -> Unit,
    onDownloadFile: () -> Unit,
    onSelectBootImage: () -> Unit,
) {
    val confirmDialog = rememberConfirmDialog(
        onConfirm = {
            onSelected(InstallMethod.DirectInstallToInactiveSlot)
        },
        onDismiss = null
    )
    val dialogTitle = stringResource(android.R.string.dialog_alert_title)
    val dialogContent = stringResource(R.string.install_inactive_slot_warning)

    val onClick = { option: InstallMethod ->
        when (option) {
            is InstallMethod.SelectFile -> onSelectBootImage()
            is InstallMethod.DownloadFile -> onDownloadFile()
            is InstallMethod.DirectInstall -> onSelected(option)
            is InstallMethod.DirectInstallToInactiveSlot -> confirmDialog.showConfirm(dialogTitle, dialogContent)
        }
    }

    // 选项数量随设备能力变化，用 size 作为 key 强制重建列表项状态
    key(state.installMethodOptions.size) {
        SegmentedColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            content = state.installMethodOptions.map { option ->
                {
                    SegmentedRadioItem(
                        title = stringResource(option.label),
                        summary = option.summary,
                        selected = option.javaClass == state.installMethod?.javaClass,
                        onClick = { onClick(option) }
                    )
                }
            }
        )
    }
}

/** 安装页顶部栏；滚动行为由调用方注入，便于与列表共享折叠状态。 */
@Composable
private fun TopBar(
    onBack: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.install)) },
        navigationIcon = {
            TopBarBackButton(onClick = onBack)
        },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
