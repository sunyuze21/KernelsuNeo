package me.weishu.kernelsu.ui.screen.module

import android.net.Uri
import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.registry.ExpectedFile
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo
import me.weishu.kernelsu.ui.component.SearchStatus

/** 模块页需要用户二次确认的操作请求；确认前不产生任何副作用。 */
sealed interface ModuleConfirmRequest {
    /** 更新请求携带下载地址与文件名，真正下载发生在用户确认之后。 */
    data class Update(
        val module: Module,
        val downloadUrl: String,
        val fileName: String,
        /** Set when the update is a registry release: size + SHA-256 are enforced. */
        val expected: ExpectedFile? = null,
    ) : ModuleConfirmRequest

    /** 卸载请求只记录目标模块，确认后才会真正删除。 */
    data class Uninstall(
        val module: Module,
    ) : ModuleConfirmRequest
}

/** 确认对话框的展示内容；由请求推导，避免各调用点自行拼装文案。 */
@Immutable
data class ModuleConfirmDialogState(
    val request: ModuleConfirmRequest,
    val title: String,
    val content: String? = null,
    val markdown: Boolean = false,
    val html: Boolean = false,
    /** Registry-hosted content: render with JavaScript off and network locked to the registry. */
    val restricted: Boolean = false,
    val confirm: String? = null,
    val dismiss: String? = null,
)

/** 一次性 UI 副作用；由界面层消费后上报，避免重组时重复弹出。 */
sealed interface ModuleEffect {
    /** 轻提示，适合短消息。 */
    data class Toast(
        val message: String,
    ) : ModuleEffect

    /** 底部提示条，适合需要用户注意但不阻塞的反馈。 */
    data class SnackBar(
        val message: String,
    ) : ModuleEffect
}

/** 模块页的完整 UI 快照；标为 Immutable 以便 Compose 跳过无变化的重组。 */
@Immutable
data class ModuleUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    /** 已加载的全部模块，未经搜索与排序。 */
    val modules: List<Module> = emptyList(),
    /** 经过搜索过滤与排序后用于展示的列表。 */
    val moduleList: List<Module> = emptyList(),
    /** 以模块 id 为 key 的可用更新信息。 */
    val updateInfo: Map<String, ModuleUpdateInfo> = emptyMap(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<Module> = emptyList(),
    val sortEnabledFirst: Boolean = false,
    val sortActionFirst: Boolean = false,
    val checkModuleUpdate: Boolean = true,
    /** 安全模式下禁用安装入口，避免用户误操作导致无法启动。 */
    val isSafeMode: Boolean = false,
    /** 已安装 Magisk 时不提供安装入口，防止两套 root 方案冲突。 */
    val magiskInstalled: Boolean = false,
    /** 非空时展示确认对话框；对话框关闭后必须复位为 null。 */
    val confirmDialogState: ModuleConfirmDialogState? = null,
) {
    /** 安装入口仅在非安全模式且未检测到 Magisk 时可见。 */
    val installButtonVisible: Boolean
        get() = !(isSafeMode || magiskInstalled)
}

/** 传给模块页 Composable 的回调集合，集中声明便于 Material/Miuix 两套实现共用。 */
@Immutable
data class ModuleActions(
    val onRefresh: () -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onRequestUpdateConfirmation: (Module, ModuleUpdateInfo) -> Unit,
    val onRequestUninstallConfirmation: (Module) -> Unit,
    val onDismissConfirmRequest: () -> Unit,
    val onConfirmUpdate: (ModuleConfirmRequest.Update) -> Unit,
    val onOpenRepo: () -> Unit,
    val onToggleSortActionFirst: () -> Unit,
    val onToggleSortEnabledFirst: () -> Unit,
    val onOpenWebUi: (Module) -> Unit,
    val onToggleModule: (Module) -> Unit,
    val onUninstallModule: (Module) -> Unit,
    val onUndoUninstallModule: (Module) -> Unit,
    val onOpenFlash: (List<Uri>) -> Unit,
    val onExecuteModuleAction: (Module) -> Unit,
)
