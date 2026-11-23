package me.weishu.kernelsu.ui.screen.module

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.ui.util.module.Shortcut

/**
 * 快捷方式编辑页的可变状态。
 *
 * 使用 Compose 状态而非普通字段，确保名称、图标与类型改动能即时反映到预览；
 * 通过 Saver 保存关键字段，旋转屏幕或进程重建后可恢复用户已填内容。
 */
@Stable
class ModuleShortcutState internal constructor(
    moduleId: String?,
    name: String,
    iconUri: String?,
    selectedTypeName: String?,
    supportsActionShortcut: Boolean,
    supportsWebUiShortcut: Boolean,
    defaultActionIconUri: String?,
    defaultWebUiIconUri: String?,
) {
    /** 目标模块 id；绑定前为 null，创建快捷方式前必须校验非空。 */
    var moduleId by mutableStateOf(moduleId)
        private set

    /** 快捷方式显示名称，用户可编辑。 */
    var name by mutableStateOf(name)

    /** 自定义图标 uri；为 null 表示使用模块默认图标。 */
    var iconUri by mutableStateOf(iconUri)
        private set

    /** 当前选中的快捷方式类型；由保存的枚举名还原。 */
    var selectedType by mutableStateOf(selectedTypeName?.let(ShortcutType::valueOf))
        private set

    /** 模块是否提供动作脚本，决定 Action 类型是否可选。 */
    var supportsActionShortcut by mutableStateOf(supportsActionShortcut)
        private set

    /** 模块是否提供 WebUI，决定 WebUI 类型是否可选。 */
    var supportsWebUiShortcut by mutableStateOf(supportsWebUiShortcut)
        private set

    var defaultActionIconUri by mutableStateOf(defaultActionIconUri)
        private set

    var defaultWebUiIconUri by mutableStateOf(defaultWebUiIconUri)
        private set

    /** 异步解码后的预览图；加载完成前保持 null，UI 需提供占位。 */
    var previewIcon by mutableStateOf<ImageBitmap?>(null)
        internal set

    /** 当前模块在该类型下是否已有快捷方式，用于切换“创建/删除”按钮。 */
    var hasExistingShortcut by mutableStateOf(false)
        internal set

    /** 当前类型对应的默认图标，可能回退到另一类型的图标。 */
    val defaultShortcutIconUri: String?
        get() = selectedType?.let(::defaultIconFor)

    /** 将模块信息写入状态；会清空用户已选的类型与图标，避免沿用上一个模块的配置。 */
    fun bindModule(module: Module) {
        moduleId = module.id
        name = module.name
        iconUri = null
        selectedType = null
        supportsActionShortcut = module.hasActionScript
        supportsWebUiShortcut = module.hasWebUi
        // 模块图标路径以 su: 前缀交给底层按 root 文件读取
        defaultActionIconUri = module.actionIconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { "su:$it" }
        defaultWebUiIconUri = module.webUiIconPath
            ?.takeIf { it.isNotBlank() }
            ?.let { "su:$it" }
    }

    /** 切换类型时同步换成该类型的默认图标，除非用户随后再手动选择。 */
    fun selectType(type: ShortcutType) {
        selectedType = type
        iconUri = defaultIconFor(type)
    }

    /** 更新快捷方式名称；空值由后续创建流程决定是否回退到模块名。 */
    fun updateName(value: String) {
        name = value
    }

    /** 更新自定义图标 URI；传入 null 表示清除自定义图标。 */
    fun updateIconUri(value: String?) {
        iconUri = value
    }

    /** 丢弃自定义图标，回到当前类型的默认图标。 */
    fun resetIconToDefault() {
        iconUri = defaultShortcutIconUri
    }

    /** 创建桌面快捷方式；参数不完整时静默返回，避免创建出无效入口。 */
    fun createShortcut(context: Context) {
        val currentModuleId = moduleId
        val type = selectedType
        if (currentModuleId.isNullOrBlank() || name.isBlank() || type == null) {
            return
        }
        createModuleShortcut(
            context = context,
            moduleId = currentModuleId,
            name = name,
            iconUri = iconUri,
            type = type,
        )
        hasExistingShortcut = true
    }

    /** 生成快捷方式指向的 uri，供预览或调试；条件不足时返回 null。 */
    fun buildShortcutUrl(): String? {
        val currentModuleId = moduleId
        val type = selectedType
        if (currentModuleId.isNullOrBlank() || type == null) {
            return null
        }
        return Shortcut.buildShortcutUri(currentModuleId, type).toString()
    }

    /** 删除当前类型对应的快捷方式，并同步“已存在”标记。 */
    fun deleteShortcut(context: Context) {
        val currentModuleId = moduleId
        val type = selectedType
        if (currentModuleId.isNullOrBlank() || type == null) {
            return
        }
        deleteModuleShortcut(context, currentModuleId, type)
        hasExistingShortcut = false
    }

    /** 按类型取默认图标；缺少本类型图标时回退到另一类型，保证预览不为空。 */
    private fun defaultIconFor(type: ShortcutType): String? {
        return when (type) {
            ShortcutType.Action -> defaultActionIconUri ?: defaultWebUiIconUri
            ShortcutType.WebUI -> defaultWebUiIconUri ?: defaultActionIconUri
        }
    }

    companion object {
        /** 只保存可序列化字段；previewIcon 与 hasExistingShortcut 可在恢复后重新计算。 */
        val Saver = listSaver<ModuleShortcutState, Any?>(
            save = {
                listOf(
                    it.moduleId,
                    it.name,
                    it.iconUri,
                    it.selectedType?.name,
                    it.supportsActionShortcut,
                    it.supportsWebUiShortcut,
                    it.defaultActionIconUri,
                    it.defaultWebUiIconUri,
                )
            },
            restore = {
                ModuleShortcutState(
                    moduleId = it[0] as String?,
                    name = it[1] as String,
                    iconUri = it[2] as String?,
                    selectedTypeName = it[3] as String?,
                    supportsActionShortcut = it[4] as Boolean,
                    supportsWebUiShortcut = it[5] as Boolean,
                    defaultActionIconUri = it[6] as String?,
                    defaultWebUiIconUri = it[7] as String?,
                )
            }
        )
    }
}

/** 记住并恢复快捷方式编辑状态，同时负责图标解码与“已存在”查询等异步副作用。 */
@Composable
fun rememberModuleShortcutState(
    context: Context,
): ModuleShortcutState {
    val state = rememberSaveable(saver = ModuleShortcutState.Saver) {
        ModuleShortcutState(
            moduleId = null,
            name = "",
            iconUri = null,
            selectedTypeName = null,
            supportsActionShortcut = false,
            supportsWebUiShortcut = false,
            defaultActionIconUri = null,
            defaultWebUiIconUri = null,
        )
    }

    // 图标解码放到 IO 线程；iconUri 变化时旧任务随 LaunchedEffect 取消
    LaunchedEffect(state.iconUri) {
        val uri = state.iconUri
        if (uri.isNullOrBlank()) {
            state.previewIcon = null
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            Shortcut.loadShortcutBitmap(context, uri)
        }
        state.previewIcon = bitmap?.asImageBitmap()
    }

    // 模块或类型切换后重新查询系统是否存在对应快捷方式
    LaunchedEffect(state.moduleId, state.selectedType) {
        val moduleId = state.moduleId
        val type = state.selectedType
        if (moduleId.isNullOrBlank() || type == null) {
            state.hasExistingShortcut = false
            return@LaunchedEffect
        }
        state.hasExistingShortcut = withContext(Dispatchers.IO) {
            hasModuleShortcut(context, moduleId, type)
        }
    }

    return remember(state) { state }
}
