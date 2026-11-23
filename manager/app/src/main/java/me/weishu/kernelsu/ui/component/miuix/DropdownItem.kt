package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 下拉菜单中的单个选项。
 *
 * 首项/末项额外加大上下留白，让圆角容器内的文字不贴边；中间项保持 12.dp，
 * 使条目密度稳定。回调通过 `rememberUpdatedState` 读取最新 lambda，
 * 避免菜单长驻时捕获到过期的选中逻辑。
 */
@Composable
fun DropdownItem(
    modifier: Modifier = Modifier,
    text: String,
    optionSize: Int,
    index: Int,
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    onSelectedIndexChange: (Int) -> Unit
) {
    val currentOnSelectedIndexChange = rememberUpdatedState(onSelectedIndexChange)
    // 首尾项补足留白，避免文字紧贴菜单容器的圆角边缘
    val additionalTopPadding = if (index == 0) 20f.dp else 12f.dp
    val additionalBottomPadding = if (index == optionSize - 1) 20f.dp else 12f.dp

    Row(
        modifier = modifier
            .clickable { currentOnSelectedIndexChange.value(index) }
            .background(dropdownColors.containerColor)
            .padding(horizontal = 20.dp)
            .padding(
                top = additionalTopPadding,
                bottom = additionalBottomPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = dropdownColors.contentColor,
        )
    }
}
