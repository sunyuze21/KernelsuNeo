// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.miuix.effect

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 给任意修饰符链附加动态背景绘制。
 *
 * 参数以 lambda 传入的两项（colorStage/alpha）在绘制阶段才求值，
 * 因此动画更新不会触发重组，只会 invalidate 绘制。
 */
internal fun Modifier.bgEffectDraw(
    painter: BgEffectPainter,
    preset: BgEffectConfig.Config,
    deviceType: DeviceType,
    isDarkTheme: Boolean,
    surface: Color,
    effectBackground: Boolean,
    isFullSize: Boolean,
    playing: Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then BgEffectElement(
    painter = painter,
    preset = preset,
    deviceType = deviceType,
    isDarkTheme = isDarkTheme,
    surface = surface,
    effectBackground = effectBackground,
    isFullSize = isFullSize,
    playing = playing,
    colorStage = colorStage,
    alpha = alpha,
)

/**
 * bgEffectDraw 的节点元素。
 *
 * 所有字段参与 data class 的 equals，参数不变时节点被复用而不是重建，
 * 已运行的动画 Job 也就不会被打断。
 */
@SuppressLint("ModifierNodeInspectableProperties")
private data class BgEffectElement(
    val painter: BgEffectPainter,
    val preset: BgEffectConfig.Config,
    val deviceType: DeviceType,
    val isDarkTheme: Boolean,
    val surface: Color,
    val effectBackground: Boolean,
    val isFullSize: Boolean,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<BgEffectNode>() {

    /** 创建绘制节点；初始播放状态决定是否立即启动帧循环 */
    override fun create(): BgEffectNode = BgEffectNode(
        painter = painter,
        preset = preset,
        deviceType = deviceType,
        isDarkTheme = isDarkTheme,
        surface = surface,
        effectBackground = effectBackground,
        isFullSize = isFullSize,
        playing = playing,
        colorStage = colorStage,
        alpha = alpha,
    )

    /** 参数变化时同步到节点，并按需启停动画、请求重绘 */
    override fun update(node: BgEffectNode) {
        node.update(
            painter = painter,
            preset = preset,
            deviceType = deviceType,
            isDarkTheme = isDarkTheme,
            surface = surface,
            effectBackground = effectBackground,
            isFullSize = isFullSize,
            playing = playing,
            colorStage = colorStage,
            alpha = alpha,
        )
    }
}

/**
 * 背景绘制节点。
 *
 * 动画时间由独立的协程按 60fps 节流推进：`withFrameNanos` 保证与屏幕刷新对齐，
 * 但高刷屏上也会被限流到 60fps，避免后台大色块动画白白耗电。
 * 节点 detach 时取消 Job，防止修饰符移除后仍在后台请求帧。
 */
private class BgEffectNode(
    private var painter: BgEffectPainter,
    private var preset: BgEffectConfig.Config,
    private var deviceType: DeviceType,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var effectBackground: Boolean,
    private var isFullSize: Boolean,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {

    // 动画只推进时间戳；颜色阶段由外部 Animatable 单独驱动，两者互不阻塞
    private var animationJob: Job? = null
    private var animTime: Float = 0f
    // 重启动画时以当前时间为基准，避免暂停后动画进度“跳变”
    private var startOffset: Float = 0f

    /** 节点挂载时若处于播放态则启动帧循环 */
    override fun onAttach() {
        if (playing) startAnimation()
    }

    /** 节点移除时取消帧循环，否则会持续请求下一帧造成泄漏 */
    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    /** 同步最新参数；播放状态切换是唯一起停动画的入口 */
    fun update(
        painter: BgEffectPainter,
        preset: BgEffectConfig.Config,
        deviceType: DeviceType,
        isDarkTheme: Boolean,
        surface: Color,
        effectBackground: Boolean,
        isFullSize: Boolean,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.deviceType = deviceType
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.effectBackground = effectBackground
        this.isFullSize = isFullSize
        this.colorStage = colorStage
        this.alpha = alpha

        if (this.playing != playing) {
            this.playing = playing
            if (playing) {
                startAnimation()
            } else {
                animationJob?.cancel()
                animationJob = null
            }
        }
        invalidateDraw()
    }

    /**
     * 启动按帧推进的时间循环。
     *
     * 先取一次 origin 作为零点，之后每帧计算相对秒数；小于 16.6ms 的帧直接跳过，
     * 把更新频率限制在 60fps。`startOffset` 保证重启动画时时间连续。
     */
    private fun startAnimation() {
        animationJob?.cancel()
        startOffset = animTime
        animationJob = coroutineScope.launch {
            val minDeltaNanos = 1_000_000_000L / 60L
            val origin = withFrameNanos { it }
            var lastEmit = origin
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - lastEmit < minDeltaNanos) continue
                lastEmit = now
                animTime = startOffset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    /**
     * 绘制顺序：先铺主题底色，再叠 shader 背景，最后画内容。
     *
     * 底色必须最先画，shader 输出的 alpha 才有可混合的底；内容最后画，
     * 保证背景不会盖住文字。背景高度按全屏/半屏取不同比例，
     * 控制色团只集中在上半部分，避免整屏泛色影响阅读。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun ContentDrawScope.draw() {
        drawRect(surface)
        if (effectBackground) {
            val alphaValue = alpha()
            if (alphaValue > 0f) {
                // 全屏时背景覆盖 80% 高度，半屏只覆盖 50%，给正文留出足够的纯净底色
                val drawHeight = if (isFullSize) size.height * 0.8f else size.height * 0.5f

                painter.updateResolution(size.width, size.height)
                painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
                painter.updatePresetIfNeeded(deviceType, isDarkTheme)
                painter.updateColors(preset, colorStage())
                painter.updateAnimTime(animTime)
                painter.updatePointsAnim(animTime, preset)

                drawRect(painter.brush, alpha = alphaValue)
            }
        }
        drawContent()
    }
}
