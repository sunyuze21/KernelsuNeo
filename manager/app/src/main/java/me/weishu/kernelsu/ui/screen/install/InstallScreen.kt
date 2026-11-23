package me.weishu.kernelsu.ui.screen.install

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.choosekmidialog.ChooseKmiDialog
import me.weishu.kernelsu.ui.component.dialog.DownloadDialog
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.util.LkmSelection
import me.weishu.kernelsu.ui.util.getAvailablePartitions
import me.weishu.kernelsu.ui.util.getCurrentKmi
import me.weishu.kernelsu.ui.util.getDefaultPartition
import me.weishu.kernelsu.ui.util.getSlotSuffix
import me.weishu.kernelsu.ui.util.isAbDevice
import me.weishu.kernelsu.ui.util.probeRemoteBootPartitions
import me.weishu.kernelsu.ui.util.rootAvailable
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState

/**
 * KernelSU 安装入口页：收集设备能力（KMI、分区、A/B、root 状态），
 * 组装安装参数后跳转到刷写页。切换主题时只替换底部 UI 实现，业务状态不变。
 */
@Composable
fun InstallScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    // 两套主题各自使用不同的 Snackbar 宿主，按当前 UiMode 选择
    val snackbarHost = remember { SnackbarHostState() }
    val miuixSnackbarHost = remember { MiuixSnackbarHostState() }
    val uiMode = LocalUiMode.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    // 远程镜像分区探测任务；重新确认下载地址时需要取消旧任务，避免结果串台
    var probeJob by remember { mutableStateOf<Job?>(null) }
    val loadingDialog = rememberLoadingDialog()

    // 以下状态用 rememberSaveable 保存，屏幕旋转后仍保留用户已选的安装方式与高级选项
    var installMethod by rememberSaveable { mutableStateOf<InstallMethod?>(null) }
    var downloadDialogShown by rememberSaveable { mutableStateOf(false) }
    var remotePartitions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var remotePartitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var lkmSelection by rememberSaveable { mutableStateOf<LkmSelection>(LkmSelection.KmiNone) }
    var partitionSelectionIndex by rememberSaveable { mutableIntStateOf(0) }
    // 用户手动改过分区后，不再用默认分区覆盖其选择
    var hasCustomSelected by rememberSaveable { mutableStateOf(false) }
    val showChooseKmiDialog = rememberSaveable { mutableStateOf(false) }
    var advancedOptionsShown by rememberSaveable { mutableStateOf(false) }
    var allowShell by rememberSaveable { mutableStateOf(false) }
    var enableAdb by rememberSaveable { mutableStateOf(false) }
    var forceBackup by rememberSaveable { mutableStateOf(false) }

    // 设备信息在后台读取一次；produceState 会在组合离开时自动取消协程
    val currentKmi by produceState(initialValue = "") { value = getCurrentKmi() }
    val partitions by produceState(initialValue = emptyList()) { value = getAvailablePartitions() }
    val defaultPartition by produceState(initialValue = "") { value = getDefaultPartition() }
    val rootAvailable by produceState(initialValue = false) { value = rootAvailable() }
    val isAbDevice by produceState(initialValue = false) { value = isAbDevice() }
    val isGkiDevice by produceState(initialValue = false) { value = getKernelVersion().isGKI() }

    val selectFileTip = stringResource(id = R.string.select_file_tip, defaultPartition)
    val selectFileTipNoGki = stringResource(id = R.string.select_file_tip_nogki)
    val downloadFileMsg = stringResource(id = R.string.download_dialog_msg)
    // 直接安装依赖 root 与 GKI 内核；不支持时只保留选文件和下载两种方式
    val installMethodOptions = remember(rootAvailable, isAbDevice, isGkiDevice, selectFileTip, selectFileTipNoGki, downloadFileMsg) {
        buildList {
            add(InstallMethod.SelectFile(summary = if (isGkiDevice) selectFileTip else selectFileTipNoGki))
            add(InstallMethod.DownloadFile(summary = downloadFileMsg))
            if (rootAvailable && isGkiDevice) {
                add(InstallMethod.DirectInstall)
                if (isAbDevice) add(InstallMethod.DirectInstallToInactiveSlot)
            }
        }
    }

    // OTA 安装时槽位后缀随目标槽位变化（例如 _a/_b），需要重新查询
    val isOta = installMethod is InstallMethod.DirectInstallToInactiveSlot
    val slotSuffix by produceState(initialValue = "", isOta) { value = getSlotSuffix(isOta) }
    val defaultIndex = remember(partitions, defaultPartition) {
        partitions.indexOf(defaultPartition).coerceAtLeast(0)
    }

    // 分区列表异步返回后校正选中下标，防止越界；用户已手选时尽量保持原选择
    LaunchedEffect(partitions, defaultIndex, hasCustomSelected) {
        if (partitions.isEmpty()) return@LaunchedEffect
        if (!hasCustomSelected) {
            partitionSelectionIndex = defaultIndex.coerceIn(0, partitions.lastIndex)
        } else if (partitionSelectionIndex > partitions.lastIndex) {
            partitionSelectionIndex = partitions.lastIndex
        }
    }

    // 展示名里标注默认分区，帮助用户区分同名的 boot 分区
    val displayPartitions = remember(partitions, defaultPartition) {
        partitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }
    val remoteDisplayPartitions = remember(remotePartitions, defaultPartition) {
        remotePartitions.map { name -> if (defaultPartition == name) "$name (default)" else name }
    }

    // 按当前主题选择对应的提示通道，避免在 Miuix 下弹出 Material Snackbar
    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                miuixSnackbarHost.showSnackbar(message)
            }
        }
    }

    // 把当前选择转换为刷写路由参数；下载方式缺少 url/分区时直接放弃跳转
    val onInstall = {
        installMethod?.let { method ->
            navigator.push(
                Route.Flash(
                    when (method) {
                        is InstallMethod.DownloadFile -> FlashIt.DownloadBoot(
                            url = method.url ?: return@let,
                            partition = method.partition ?: return@let,
                            lkm = lkmSelection,
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            backup = forceBackup
                        )
                        else -> FlashIt.FlashBoot(
                            boot = if (method is InstallMethod.SelectFile) method.uri else null,
                            lkm = lkmSelection,
                            ota = method is InstallMethod.DirectInstallToInactiveSlot,
                            partition = partitions.getOrNull(partitionSelectionIndex),
                            allowShell = allowShell,
                            enableAdb = enableAdb,
                            // 只有选文件方式才允许强制备份，直接安装无原始镜像可备份
                            backup = method is InstallMethod.SelectFile && forceBackup
                        )
                    }
                )
            )
        }
    }

    // KMI 选择确认后写入 LKM 选择，再继续原本的安装流程
    ChooseKmiDialog(
        show = showChooseKmiDialog.value,
        onDismissRequest = { showChooseKmiDialog.value = false },
        onSelected = { kmi ->
            kmi?.let {
                lkmSelection = LkmSelection.KmiString(it)
                onInstall()
            }
        }
    )

    DownloadDialog(
        show = downloadDialogShown,
        onConfirm = { url ->
            downloadDialogShown = false
            // 先取消上一次探测，防止旧请求较晚返回时覆盖本次下载地址
            probeJob?.cancel()
            probeJob = scope.launch {
                try {
                    loadingDialog.showLoading()
                    // 远程 zip 中可能包含多个 boot 分区，需要先探测再让用户确认目标分区
                    val result = probeRemoteBootPartitions(url)
                    if (result.partitions.isEmpty()) {
                        showMessage(resources.getString(R.string.download_no_boot_partition))
                    } else {
                        val defaultIdx = result.partitions.indexOf(defaultPartition).coerceAtLeast(0)
                        remotePartitions = result.partitions
                        remotePartitionSelectionIndex = defaultIdx
                        installMethod = InstallMethod.DownloadFile(
                            url = url,
                            partition = result.partitions[defaultIdx],
                            summary = downloadFileMsg,
                        )
                    }
                } catch (e: CancellationException) {
                    // 协程取消必须原样抛出，否则会被当作普通失败提示
                    throw e
                } catch (e: Exception) {
                    showMessage(
                        resources.getString(R.string.download_probe_failed, e.message ?: "")
                    )
                } finally {
                    loadingDialog.hide()
                }
            }
        },
        onDismiss = { downloadDialogShown = false }
    )

    // LKM 只接受 .ko；选错文件时清空选择并提示，避免把镜像当模块传给刷写流程
    val selectLkmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                if (isKoFile(context, uri)) {
                    lkmSelection = LkmSelection.LkmUri(uri)
                } else {
                    lkmSelection = LkmSelection.KmiNone
                    showMessage(resources.getString(R.string.install_only_support_ko_file))
                }
            }
        }
    }
    // 选择本地 boot 镜像后立即把安装方式切为 SelectFile
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                installMethod = InstallMethod.SelectFile(uri, summary = if (isGkiDevice) selectFileTip else selectFileTipNoGki)
            }
        }
    }

    // 汇总为不可变状态；分区下拉仅对直接安装/OTA/下载方式有意义
    val state = InstallUiState(
        installMethod = installMethod,
        lkmSelection = lkmSelection,
        partitionSelectionIndex = partitionSelectionIndex,
        displayPartitions = displayPartitions,
        remoteDisplayPartitions = remoteDisplayPartitions,
        remotePartitionSelectionIndex = remotePartitionSelectionIndex,
        currentKmi = currentKmi,
        slotSuffix = slotSuffix,
        installMethodOptions = installMethodOptions,
        canSelectPartition = installMethod is InstallMethod.DirectInstall ||
            installMethod is InstallMethod.DirectInstallToInactiveSlot ||
            installMethod is InstallMethod.DownloadFile,
        advancedOptionsShown = advancedOptionsShown,
        allowShell = allowShell,
        enableAdb = enableAdb,
        forceBackup = forceBackup,
        canForceBackup = installMethod is InstallMethod.SelectFile,
    )
    // 所有回调集中在此；下载方式的分区下标与本地分区列表分开维护
    val actions = InstallScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSelectMethod = { method -> installMethod = method },
        onDownloadFile = { downloadDialogShown = true },
        onSelectBootImage = {
            selectImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onUploadLkm = {
            selectLkmLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/octet-stream" })
        },
        onClearLkm = { lkmSelection = LkmSelection.KmiNone },
        onSelectPartition = { index ->
            hasCustomSelected = true
            val method = installMethod
            if (method is InstallMethod.DownloadFile) {
                remotePartitionSelectionIndex = index
                installMethod = method.copy(partition = remotePartitions.getOrNull(index))
            } else {
                partitionSelectionIndex = index
            }
        },
        onNext = {
            // 没有 LKM 且无法确定 KMI 时先弹出选择框；下载流程会自行解析 KMI
            val isLkmSelected = lkmSelection != LkmSelection.KmiNone
            val isKmiUnknown = currentKmi.isBlank()
            val isKmiUnresolved = when (installMethod) {
                // The download flow extracts the KMI itself; no manual
                // selection needed.
                is InstallMethod.DownloadFile -> false
                is InstallMethod.SelectFile -> true
                else -> isKmiUnknown
            }
            if (!isLkmSelected && isKmiUnresolved) {
                showChooseKmiDialog.value = true
            } else {
                onInstall()
            }
        },
        onAdvancedOptionsClicked = {
            advancedOptionsShown = !advancedOptionsShown
        },
        onSelectAllowShell = {
            allowShell = it
        },
        onSelectEnableAdb = {
            enableAdb = it
        },
        onSelectForceBackup = {
            forceBackup = it
        }
    )

    // 仅切换渲染层，状态与回调完全复用，保证两套主题行为一致
    when (LocalUiMode.current) {
        UiMode.Miuix -> InstallScreenMiuix(state, actions, miuixSnackbarHost)
        UiMode.Material -> InstallScreenMaterial(state, actions, snackbarHost)
    }
}
