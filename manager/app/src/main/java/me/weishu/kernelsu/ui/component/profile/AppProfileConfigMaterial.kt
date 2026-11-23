package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField

/**
 * Material 版应用 profile 配置：名称与“卸载模块”开关。
 * 所有修改都通过 copy 生成新 Profile 回调上抛，保持状态单向流动；
 * 未启用（无自定义 profile）时开关显示内核默认值且不可改。
 */
@Composable
fun AppProfileConfigMaterial(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            SegmentedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                content = listOf {
                    SegmentedTextField(
                        value = profile.name,
                        onValueChange = { onProfileChange(profile.copy(name = it)) },
                        label = stringResource(R.string.profile_name),
                        // 未启用时名称只读，避免用户误以为已保存
                        readOnly = !enabled,
                        singleLine = true
                    )
                }
            )
        }

        SegmentedColumn(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            content = listOf {
                SegmentedSwitchItem(
                    title = stringResource(R.string.profile_umount_modules),
                    summary = stringResource(R.string.profile_umount_modules_summary),
                    // 未启用时展示默认策略，让用户知道当前实际生效的行为
                    checked = if (enabled) {
                        profile.umountModules
                    } else {
                        Natives.isDefaultUmountModules()
                    },
                    enabled = enabled,
                    onCheckedChange = {
                        // 手动改动即脱离“跟随默认”，需清除 nonRootUseDefault
                        onProfileChange(
                            profile.copy(
                                umountModules = it,
                                nonRootUseDefault = false
                            )
                        )
                    }
                )
            }
        )
    }
}
