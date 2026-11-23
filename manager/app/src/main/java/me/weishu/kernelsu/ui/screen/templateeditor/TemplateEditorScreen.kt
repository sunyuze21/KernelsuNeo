package me.weishu.kernelsu.ui.screen.templateeditor

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import me.weishu.kernelsu.R
import me.weishu.kernelsu.toOrdinalList
import me.weishu.kernelsu.toRootProfileFlags
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.deleteAppProfileTemplate
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel

/**
 * app profile 模板编辑器。
 *
 * 新建时 id 可编辑且需要查重/格式校验；编辑既有模板时 id 固定，只编辑其他字段。
 * Miuix 采用“改即存”的自动保存策略，Material 则通过顶栏保存按钮显式提交，
 * 因此两条路径的校验与失败提示必须保持一致。
 */
@Composable
fun TemplateEditorScreen(template: TemplateViewModel.TemplateInfo, readOnly: Boolean) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uiMode = LocalUiMode.current
    // id 为空表示新建；autoSave 仅用于 Miuix 的已有模板编辑。
    val isCreation = template.id.isBlank()
    val autoSave = uiMode == UiMode.Miuix && !isCreation

    // 编辑中的草稿需要跨配置变更保留，但不能写回列表直到保存成功。
    var currentTemplate by rememberSaveable { mutableStateOf(template) }
    var idErrorHint by remember { mutableStateOf("") }

    val saveTemplateFailed = stringResource(id = R.string.app_profile_template_save_failed)
    val idConflictError = stringResource(id = R.string.app_profile_template_id_exist)
    val idInvalidError = stringResource(id = R.string.app_profile_template_id_invalid)

    // 编辑器内统一用 Toast 反馈，避免依赖两种 UI 的 Snackbar 宿主。
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 返回时通过导航结果通知列表刷新；只读模式没有可保存的内容，直接出栈。
    fun finishEditing() {
        if (!readOnly) {
            navigator.setResult("template_edit", true)
        } else {
            navigator.pop()
        }
    }

    // 自动保存失败时保留旧草稿，防止用户输入被静默丢弃。
    fun updateTemplate(updatedTemplate: TemplateViewModel.TemplateInfo) {
        if (autoSave) {
            if (!saveTemplate(updatedTemplate)) {
                return
            }
        }
        currentTemplate = updatedTemplate
    }

    val uiState = TemplateEditorUiState(
        template = currentTemplate,
        initialTemplate = template,
        readOnly = readOnly,
        isCreation = isCreation,
        idErrorHint = idErrorHint,
    )

    // 显式保存路径：先做 id 校验，再落盘，失败时给出具体原因。
    fun saveCurrentTemplate() {
        if (uiMode == UiMode.Miuix) {
            when (idCheck(currentTemplate.id)) {
                1 -> {
                    showToast(idConflictError)
                    return
                }

                2 -> {
                    showToast(idInvalidError)
                    return
                }
            }
        }

        if (saveTemplate(currentTemplate, isCreation)) {
            navigator.setResult("template_edit", true)
        } else {
            showToast(saveTemplateFailed)
        }
    }

    val actions = TemplateEditorActions(
        onBack = dropUnlessResumed { finishEditing() },
        onDelete = {
            // 删除成功同样需要通知列表刷新，避免残留已删除项。
            if (deleteAppProfileTemplate(currentTemplate.id)) {
                navigator.setResult("template_edit", true)
            }
        },
        onSave = ::saveCurrentTemplate,
        onNameChange = { value ->
            updateTemplate(currentTemplate.copy(name = value))
        },
        onIdChange = { value ->
            // id 输入实时校验：先查重再查格式，保证错误提示优先级稳定。
            idErrorHint = if (isTemplateExist(value)) {
                idConflictError
            } else if (!isValidTemplateId(value)) {
                idInvalidError
            } else {
                ""
            }
            currentTemplate = currentTemplate.copy(id = value)
        },
        onAuthorChange = { value ->
            updateTemplate(currentTemplate.copy(author = value))
        },
        onDescriptionChange = { value ->
            updateTemplate(currentTemplate.copy(description = value))
        },
        onProfileChange = { profile ->
            // 原生 Profile 的 rules 是换行分隔字符串，模板模型存成行列表；
            // flags 则需要在原生枚举与序号列表间转换。
            updateTemplate(
                currentTemplate.copy(
                    uid = profile.uid,
                    gid = profile.gid,
                    groups = profile.groups,
                    capabilities = profile.capabilities,
                    context = profile.context,
                    namespace = profile.namespace,
                    rules = profile.rules.split("\n"),
                    flags = profile.flags.toRootProfileFlags().toOrdinalList(),
                )
            )
        },
    )

    // 两端共享同一份校验与保存逻辑，仅渲染层不同。
    when (uiMode) {
        UiMode.Miuix -> TemplateEditorScreenMiuix(
            state = uiState,
            actions = actions,
        )

        UiMode.Material -> TemplateEditorScreenMaterial(
            state = uiState,
            actions = actions,
        )
    }
}
