package me.weishu.kernelsu.ui.component.miuix.modifier

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.util.fastFirstOrNull

/**
 * 拖拽手势检测，供需要“自己决定是否消费”的组件使用。
 *
 * 与 `detectDragGestures` 的区别：按下即在 Initial 阶段回调 onDragStart 且立刻
 * 上报一次零位移，调用方可以据此提前建立拖拽状态；随后用
 * `positionChangeIgnoreConsumed` 读取位移，即使事件已被子节点消费也能跟踪，
 * 因此适合做父级手势仲裁而不是普通拖动。
 *
 * 手指抬起时回调 onDragEnd，若在拖动过程中失去指针（被取消）则回调 onDragCancel。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )

        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = drag(
            pointerId = down.id,
            onDrag = { onDrag(it, it.positionChangeIgnoreConsumed()) },
        )
        if (upEvent != null) onDragEnd(upEvent) else onDragCancel()
    }
}

/**
 * 持续跟踪指定指针直到抬起。
 *
 * 若进入时该指针已经不在按下状态，返回 null 表示本次手势已结束；
 * 指针切换（多指场景）时跟随新的 id 继续，保证拖动过程中不会因手指交替而中断。
 */
private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

/**
 * 等待“发生位移”或“全部手指抬起”两种事件之一。
 *
 * 在 Initial 阶段读取事件，先于子节点消费，因此能观察到被内部滚动等
 * 消费掉的位移；抬起时若还有其他手指按下，则切换到该手指继续等待，
 * 避免多指交替时误判手势结束。
 */
private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
