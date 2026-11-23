package me.weishu.kernelsu.ui.screen.appprofile

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R

/**
 * profile 的三种编辑模式。
 * Default 表示完全跟随全局默认；Template 使用命名模板；Custom 使用逐项自定义规则。
 * 资源 id 用 @field:StringRes 标注，确保编译期能检查字符串资源类型。
 */
enum class Mode(@field:StringRes private val res: Int) {
    Default(R.string.profile_default),
    Template(R.string.profile_template),
    Custom(R.string.profile_custom);

    // 在 Compose 中按当前语言解析为展示文案；非组合环境不能直接调用。
    val text: String
        @Composable get() = stringResource(res)
}
