package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 顶栏返回按钮。
 *
 * 统一使用主题的 `surfaceContainerHighest` 作为按钮底色，让返回键在滚动内容上方
 * 保持可辨识度；左右留白由外层 TopAppBar 的标题对齐决定，因此这里只补 `10.dp`
 * 的水平内边距，避免与 TopAppBar 自身的导航图标间距叠加后偏移。
 *
 * @param onClick 点击返回时的回调，由调用方负责处理导航栈
 * @param modifier 外部修饰符，通常用于指定尺寸或定位
 * @param contentDescription 无障碍描述，供 TalkBack 朗读
 */
@Composable
fun TopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    IconButton(
        modifier = modifier.padding(horizontal = 10.dp),
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
        )
    }
}
