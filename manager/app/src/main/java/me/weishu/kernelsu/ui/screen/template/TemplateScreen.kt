package me.weishu.kernelsu.ui.screen.template

import android.content.ClipData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.isNetworkAvailable
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState

/**
 * 应用配置模板列表页。
 *
 * 模板即一组 app profile 的文本表示，页面支持从剪贴板导入/导出、新建和编辑。
 * 编辑页通过导航结果（[requestKey]）回传成功标记，成功后列表需要重新拉取，
 * 以保证本地/远端模板状态一致。
 */
@Composable
fun AppProfileTemplateScreen() {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val viewModel = viewModel<TemplateViewModel>()
    val screenState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 导航结果的频道名，编辑页返回时也用它回传是否保存成功。
    val requestKey = "template_edit"
    val snackBarHost = remember { SnackbarHostState() }
    val miuixSnackbarHost = remember { MiuixSnackbarHostState() }

    LaunchedEffect(Unit) {
        // 已有缓存时不再请求，避免进入页面重复触发网络/IO。
        if (screenState.templateList.isEmpty()) {
            viewModel.fetchTemplates()
        }
    }

    LaunchedEffect(Unit) {
        navigator.observeResult<Boolean>(requestKey).collect { success ->
            if (success) {
                // Miuix 的结果不会自动消费，需手动清理以免下次进入重复刷新。
                if (uiMode == UiMode.Miuix) {
                    navigator.clearResult(requestKey)
                }
                viewModel.fetchTemplates()
            }
        }
    }

    val importEmptyText = stringResource(R.string.app_profile_template_import_empty)
    val importSuccessText = stringResource(R.string.app_profile_template_import_success)
    val exportEmptyText = stringResource(R.string.app_profile_template_export_empty)

    // 两套 UI 的提示组件不同，这里统一成同一个回调供业务逻辑调用。
    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackBarHost.showSnackbar(message)
            } else {
                miuixSnackbarHost.showSnackbar(message)
            }
        }
    }

    // offline 由网络状态实时推导，而不是持久化，避免显示过期状态。
    val uiState = screenState.copy(offline = !isNetworkAvailable(context))
    val actions = TemplateActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRefresh = { forceSync ->
            scope.launch {
                // forceSync=true 表示用户下拉刷新，需要绕过缓存。
                viewModel.fetchTemplates(forceSync)
            }
        },
        onImport = {
            scope.launch {
                // 只读取剪贴板首项文本；空内容直接提示，不触发解析。
                clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()?.let { templateText ->
                    if (templateText.isEmpty()) {
                        showMessage(importEmptyText)
                        return@let
                    }
                    viewModel.importTemplates(
                        templateText,
                        onSuccess = {
                            showMessage(importSuccessText)
                            // 导入成功后重新拉取，确保列表包含新模板。
                            viewModel.fetchTemplates(false)
                        },
                        onFailure = { showMessage(it) },
                    )
                }
            }
        },
        onExport = {
            scope.launch {
                viewModel.exportTemplates(
                    onTemplateEmpty = {
                        showMessage(exportEmptyText)
                    },
                    callback = { templateText ->
                        // 导出结果写入系统剪贴板，供其他应用或设备粘贴。
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("template", templateText))
                        )
                    },
                )
            }
        },
        onCreateTemplate = {
            // Miuix 依赖导航结果回传，Material 直接 push 即可。
            when (uiMode) {
                UiMode.Miuix -> navigator.navigateForResult(
                    Route.TemplateEditor(TemplateViewModel.TemplateInfo(), false),
                    requestKey,
                )

                UiMode.Material -> navigator.push(
                    Route.TemplateEditor(TemplateViewModel.TemplateInfo(), false)
                )
            }
        },
        onOpenTemplate = { template ->
            // 远端模板（local=false）以只读方式打开，第二个参数即 isRemote。
            when (uiMode) {
                UiMode.Miuix -> navigator.navigateForResult(
                    Route.TemplateEditor(template, !template.local),
                    requestKey,
                )

                UiMode.Material -> navigator.push(
                    Route.TemplateEditor(template, !template.local)
                )
            }
        },
    )

    // 同一份状态分派到两套实现，提示组件各自持有。
    when (uiMode) {
        UiMode.Miuix -> AppProfileTemplateScreenMiuix(
            state = uiState,
            actions = actions,
            snackBarHost = miuixSnackbarHost,
        )

        UiMode.Material -> AppProfileTemplateScreenMaterial(
            state = uiState,
            actions = actions,
            snackBarHost = snackBarHost,
        )
    }
}
