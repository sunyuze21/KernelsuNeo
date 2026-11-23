package me.weishu.kernelsu.ui.screen.templateeditor

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.TemplateInfo

/**
 * 模板编辑器的展示状态。
 *
 * [template] 是正在编辑的草稿，[initialTemplate] 保留进入页面时的原始值，
 * 用于标题摘要和判断是否为新模板；[idErrorHint] 仅在新建时展示。
 */
@Immutable
data class TemplateEditorUiState(
    val template: TemplateInfo,
    val initialTemplate: TemplateInfo,
    val readOnly: Boolean,
    val isCreation: Boolean,
    val idErrorHint: String = "",
) {
    // “id@author”形式的副标题，author 为空时省略 @ 后缀。
    val titleSummary: String
        get() = initialTemplate.id + if (initialTemplate.author.isNotEmpty()) "@${initialTemplate.author}" else ""
}

/** 模板编辑器的用户操作回调。 */
@Immutable
data class TemplateEditorActions(
    val onBack: () -> Unit,
    val onDelete: () -> Unit,
    val onSave: () -> Unit,
    val onNameChange: (String) -> Unit,
    // id 变更会触发查重与格式校验，由页面层统一处理
    val onIdChange: (String) -> Unit,
    val onAuthorChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onProfileChange: (Natives.Profile) -> Unit,
)
