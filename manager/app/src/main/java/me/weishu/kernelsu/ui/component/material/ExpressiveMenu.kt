package me.weishu.kernelsu.ui.component.material

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset

/**
 * 以任意像素偏移为锚点弹出的下拉菜单。
 *
 * DropdownMenuPopup 自身只能相对父容器定位，这里用一个 `layout` 占位盒把菜单
 * 撑到父布局的可用宽度、高度设为 0，再通过内部的 `place` 把真实弹层平移到
 * `anchorOffset`。高度置 0 是为了避免这个占位盒参与父级测量后挤占空间。
 * 菜单内容额外包一层可滚动 Column，防止条目过多时超出屏幕。
 *
 * @param expanded 是否展开
 * @param onDismissRequest 点击外部或返回键时的关闭回调
 * @param anchorOffset 相对父布局左上角的像素偏移
 * @param content 菜单条目内容
 */
@Composable
fun OffsetAnchoredExpressiveMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorOffset: IntOffset = IntOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            layout(width, 0) {
                placeable.place(anchorOffset.x, anchorOffset.y)
            }
        }
    ) {
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
        ) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                modifier = Modifier.verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/**
 * 记录手指按下位置的修饰符扩展。
 *
 * 只监听 `awaitFirstDown` 而不消费事件，因此不会打断子节点自身的点击/长按手势；
 * 典型用途是在菜单展开前拿到触点，用于计算气泡的锚点偏移。
 */
fun Modifier.trackPressPosition(onPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress(down.position)
    }
}
