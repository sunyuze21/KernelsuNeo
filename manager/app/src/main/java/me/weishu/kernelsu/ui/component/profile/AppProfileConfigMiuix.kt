package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.miuix.EditText
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Miuix 版应用 profile 配置：名称输入 + “卸载模块”开关。
 * 与 Material 版一致，所有变更通过 copy 后回调，不在此持有可变的 profile。
 */
@Composable
fun AppProfileConfigMiuix(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            EditText(
                title = stringResource(R.string.profile_name),
                value = profile.name,
                onValueChange = { onProfileChange(profile.copy(name = it)) },
                enabled = enabled,
            )
        }

        SwitchPreference(
            title = stringResource(R.string.profile_umount_modules),
            summary = stringResource(R.string.profile_umount_modules_summary),
            // 未启用自定义 profile 时回退到内核默认值
            checked = if (enabled) {
                profile.umountModules
            } else {
                Natives.isDefaultUmountModules()
            },
            enabled = enabled,
            onCheckedChange = {
                // 用户显式修改后不再继承默认策略
                onProfileChange(
                    profile.copy(
                        umountModules = it,
                        nonRootUseDefault = false
                    )
                )
            }
        )
    }
}

/**
 * 设计预览：固定名称、禁用状态下检查布局，不参与运行时逻辑。
 */
@Preview
@Composable
private fun AppProfileConfigPreview() {
    var profile by remember { mutableStateOf(Natives.Profile("")) }
    AppProfileConfigMiuix(fixedName = true, enabled = false, profile = profile) {
        profile = it
    }
}
