package me.weishu.kernelsu.ui.screen.scriptrun

import android.net.Uri
import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.data.script.ParamError
import me.weishu.kernelsu.data.script.PreparedScript
import me.weishu.kernelsu.data.script.ScriptRunRecord

/** Navigation argument of the script run page. */
@Parcelize
data class ScriptRunArg(
    val uri: Uri,
    /** Registry flow: the ZIP must be a verified registry download with this module.prop id. */
    val registryId: String? = null,
    /** Local ZIP from "install from storage": also offer installing it as a module. */
    val offerInstall: Boolean = false,
    /** Shown in the title area until module.prop has been read. */
    val displayName: String? = null,
) : Parcelable

/** 脚本运行页的生命周期阶段；仅 READY 且无参数错误时才允许执行。 */
enum class ScriptRunPhase {
    PREPARING,
    FAILED,
    READY,
    RUNNING,
    FINISHED,
}

/**
 * 脚本运行页的完整状态快照。
 *
 * [values] 保存每个参数键的原始输入字符串，[errors] 只记录校验失败项：
 * 校验在 ViewModel 中随输入实时重算，UI 仅负责展示，避免两端规则不一致。
 * 运行期间 [output] 持续累积，[exitCode] 在结束后才有值。
 */
@Immutable
data class ScriptRunUiState(
    val phase: ScriptRunPhase = ScriptRunPhase.PREPARING,
    val displayName: String? = null,
    val offerInstall: Boolean = false,
    val prepared: PreparedScript? = null,
    /** Localized reason the script cannot be run (phase FAILED). */
    val error: String? = null,
    /** Current input per param key. */
    val values: Map<String, String> = emptyMap(),
    val errors: Map<String, ParamError> = emptyMap(),
    val lastRun: ScriptRunRecord? = null,
    val output: String = "",
    val exitCode: Int? = null,
) {
    // 必须同时满足“已就绪”和“无参数错误”，防止绕过输入校验直接执行。
    val canRun: Boolean get() = phase == ScriptRunPhase.READY && errors.isEmpty()
}

/** 脚本运行页的用户操作回调。 */
@Immutable
data class ScriptRunActions(
    val onBack: () -> Unit,
    // 参数键 -> 新值；校验由 ViewModel 负责，此处不做格式假设
    val onValueChange: (key: String, value: String) -> Unit,
    val onRun: () -> Unit,
    val onInstallAsModule: () -> Unit,
)
