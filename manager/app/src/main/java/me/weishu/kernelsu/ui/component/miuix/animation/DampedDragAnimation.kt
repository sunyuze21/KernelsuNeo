package me.weishu.kernelsu.ui.component.miuix.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.miuix.modifier.inspectDragGestures
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * 带阻尼的拖动状态机，封装值动画、按压缩放与速度追踪。
 *
 * 设计目标是把“手势 → 数值 → 视觉反馈”拆成互不阻塞的几条动画：
 * 位移用高刚度弹簧快速跟手，按压缩放用较软的弹簧做形变，速度则单独跟踪，
 * 供调用方做甩动/回弹判定。所有动画共用一个 [MutatorMutex]，
 * 保证程序化跳转与用户拖动不会互相覆盖。
 *
 * @param animationScope 动画协程作用域，通常与组件生命周期一致
 * @param valueRange 数值允许范围，所有写入都会被钳制
 * @param visibilityThreshold 值动画的可见阈值，越小越精确但计算略多
 * @param canDrag 判断某个触点是否允许拖动，用于避开不可拖区域
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    // 值动画使用临界阻尼（1f）尽快收敛；可见阈值直接复用入参，控制停帧时机
    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    // 速度动画更软、阈值放大十倍：速度只需“看起来”在减速，不必精确归零
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    // 按压与形变使用极小阈值，避免缩放停在 0.999 之类的中间态
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    // X/Y 用不同阻尼制造轻微异步的形变，比整体等比缩放更“有弹性”
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    // 每条动画独立维护 Animatable，读取时互不触发布局；调用方自行组合成视觉状态
    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    // 互斥锁保证 animateToValue 与用户拖动不会同时修改同一条动画
    private val mutatorMutex = MutatorMutex()

    // 按住/松开各持有一个 Job，重复调用时先取消旧任务，避免动画叠加
    private var pressJob: Job? = null
    private var releaseJob: Job? = null

    // 速度追踪基于单调时钟，不受系统时间调整影响
    private val velocityTracker = VelocityTracker()
    private val startMark = TimeSource.Monotonic.markNow()

    // 只读快照：外部读取这些值来驱动绘制，不应该直接改动画对象
    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /**
     * 可直接挂到组件上的拖动手势修饰符。
     *
     * 只有“当前与上一帧触点都在可拖动区域内”才回调 onDrag，
     * 防止从禁用区域滑入后突然接管手势；按下即 press()，让缩放反馈先于位移出现。
     */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            }
        ) { change, dragAmount ->
            val position = change.position
            val previousPosition = change.previousPosition

            val isInside = canDrag(position)
            val wasInside = canDrag(previousPosition)

            if (isInside && wasInside) {
                onDrag(size, dragAmount)
            }
        }
    }

    /** 进入按压态：重置速度追踪并并行播放进度与 X/Y 缩放，重复按压时先取消旧动画 */
    fun press() {
        releaseJob?.cancel()
        pressJob?.cancel()
        velocityTracker.resetTracking()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    /**
     * 松开/取消时恢复原状。
     *
     * 先等一帧再判断值是否仍在运动：拖动松手瞬间值动画可能还没启动，
     * 立即回缩放会让“松手后继续滑行”的元素看起来被突然弹回。
     * 若仍在运动，则等到接近目标值（2.5% 容差）后再恢复，视觉上更连贯。
     */
    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /**
     * 直接把值动画到目标位置，并持续回写速度。
     *
     * 使用 UNDISPATCHED 启动，保证在同一帧内就开始推进动画，避免出现一帧延迟；
     * 目标值先按 valueRange 钳制，防止调用方传入越界值。
     */
    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
        }
    }

    /**
     * 程序化吸附到某个值（例如点击分页指示器）。
     *
     * 整体包在 mutatorMutex 中，与用户拖动互斥；期间模拟一次按压并立即释放，
     * 让“跳转”也带有缩放反馈，而不是生硬地平移。
     */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    /**
     * 用位移轨迹计算当前速度并归一化。
     *
     * 速度按 valueRange 宽度归一化，使不同尺寸/量程的组件得到一致的甩动阈值；
     * 分母做 1e-6 兜底，避免范围为 0 时除零产生 NaN。
     */
    private fun updateVelocity() {
        velocityTracker.addPosition(
            startMark.elapsedNow().inWholeMilliseconds,
            Offset(value, 0f),
        )
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            velocityAnimation.snapTo(targetVelocity)
        }
    }
}
