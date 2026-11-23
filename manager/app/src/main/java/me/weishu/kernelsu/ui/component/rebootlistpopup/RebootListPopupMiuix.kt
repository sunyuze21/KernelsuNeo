package me.weishu.kernelsu.ui.component.rebootlistpopup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.KsuIsValid
import me.weishu.kernelsu.ui.component.ListPopupDefaults
import me.weishu.kernelsu.ui.component.miuix.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Miuix 版重启弹窗。用 OverlayListPopup 贴合锚点展示，
 * [alignment] 决定菜单相对图标的方位；[modifier] 透传给触发按钮以便外部定位。
 */
@Composable
fun RebootListPopupMiuix(
    modifier: Modifier = Modifier,
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.TopEnd
) {
    // 弹窗显隐状态；同时作为按钮按压态，形成打开中的视觉反馈
    val showTopPopup = remember { mutableStateOf(false) }
    KsuIsValid {
        val onReboot = rememberRebootAction()
        IconButton(
            modifier = modifier,
            onClick = { showTopPopup.value = true },
            holdDownState = showTopPopup.value
        ) {
            Icon(
                imageVector = MiuixIcons.Close2,
                contentDescription = stringResource(id = R.string.reboot),
                tint = colorScheme.onBackground
            )
        }
        OverlayListPopup(
            show = showTopPopup.value,
            // 复用全局菜单定位策略，保证与其它下拉菜单一致的边界处理
            popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
            alignment = alignment,
            onDismissRequest = {
                showTopPopup.value = false
            },
            content = {
                // 在弹窗内容中构建选项，避免每次重组都重新读取 PowerManager
                val rebootOptions = getRebootListOption()

                ListPopupColumn {
                    rebootOptions.forEachIndexed { idx, option ->
                        RebootDropdownItem(
                            option = option,
                            showTopPopup = showTopPopup,
                            optionSize = rebootOptions.size,
                            index = idx,
                            onReboot = onReboot
                        )
                    }
                }
            }
        )
    }
}

/**
 * 单个重启菜单项。选择后先关闭弹窗再执行重启，避免动作与弹窗状态竞争。
 */
@Composable
fun RebootDropdownItem(
    option: RebootListOption,
    showTopPopup: MutableState<Boolean>,
    optionSize: Int,
    index: Int,
    onReboot: (String) -> Unit,
) {
    DropdownItem(
        text = stringResource(option.labelRes),
        optionSize = optionSize,
        onSelectedIndexChange = {
            showTopPopup.value = false
            onReboot(option.reason)
        },
        index = index
    )
}
