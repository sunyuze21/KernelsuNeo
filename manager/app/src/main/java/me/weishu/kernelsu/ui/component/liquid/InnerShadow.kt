// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.liquid

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 内阴影的视觉参数。
 *
 * 内部阴影通过 `BlendMode.Clear` 在“已填充的形状”上挖出一圈偏移的轮廓再模糊，
 * 因此颜色越深、半径越大，压暗范围越明显。默认偏移等于半径，
 * 使阴影集中在上/下边缘形成厚度感，而不是四周均匀发黑。
 *
 * @param radius 模糊半径；为 0 时退化为硬边遮罩
 * @param offset 阴影相对形状的偏移，决定“内凹”的方向
 * @param color 阴影颜色，默认 15% 黑色
 * @param alpha 整体透明度，便于做淡入淡出动画
 * @param blendMode 图层混合模式，默认跟随 DrawScope
 */
@Immutable
data class InnerShadow(
    val radius: Dp = 24.dp,
    val offset: DpOffset = DpOffset(0.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f),
    val alpha: Float = 1f,
    val blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    companion object {
        /** 共享的默认实例：参数全为默认值，可安全复用避免反复分配 */
        @Stable
        val Default: InnerShadow = InnerShadow()
    }
}

/**
 * 为任意 [Shape] 绘制内阴影。
 *
 * 阴影以 lambda 形式传入而非直接传值：绘制阶段才读取最新参数，
 * 动画中的半径/透明度过期即可生效，无需每帧重建 Modifier 链。
 * 传 null 可在动画结束后彻底跳过绘制，避免空转。
 */
fun Modifier.innerShadow(
    shape: Shape,
    shadow: () -> InnerShadow?,
): Modifier = this then InnerShadowElement(shape, shadow)

/**
 * [Modifier.innerShadow] 的节点元素声明。
 *
 * 通过实现 [ModifierNodeElement] 参与节点复用；equals/hashCode 以 shape 与
 * shadow lambda 为准，参数不变时不会触发重建，只有真正变化才会 [invalidateDraw]。
 */
private class InnerShadowElement(
    val shape: Shape,
    val shadow: () -> InnerShadow?,
) : ModifierNodeElement<InnerShadowNode>() {

    /** 创建绘制节点，节点持有自己的离屏图层与 Path 缓存 */
    override fun create(): InnerShadowNode = InnerShadowNode(shape, shadow)

    /** 参数更新时同步到节点并请求重绘；不重建节点可保留已分配的 GraphicsLayer */
    override fun update(node: InnerShadowNode) {
        node.shape = shape
        node.shadow = shadow
        node.invalidateDraw()
    }

    /** 暴露给布局检查器的属性，方便在 Android Studio 中排查阴影参数 */
    override fun InspectorInfo.inspectableProperties() {
        name = "innerShadow"
        properties["shape"] = shape
        properties["shadow"] = shadow
    }

    /** 相等性只比较影响绘制的两项，避免无关字段变化触发节点重建 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerShadowElement) return false
        if (shape != other.shape) return false
        if (shadow != other.shadow) return false
        return true
    }

    /** 与 equals 保持一致的哈希实现 */
    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

/**
 * 内阴影的绘制节点。
 *
 * 采用“离屏图层 + Clear 混合”实现：先在图层里填充阴影色并挖掉偏移后的形状，
 * 再对整层模糊，最后裁剪回原形状绘制。离屏层是必需的——直接在画布上做
 * Clear 会擦掉内容本身；图层只在 attach 时创建、detach 时释放，
 * 避免每帧分配显存。
 */
private class InnerShadowNode(
    var shape: Shape,
    var shadow: () -> InnerShadow?,
) : Modifier.Node(),
    DrawModifierNode {

    // 关闭自动重绘：只有参数变化时才需要重绘，否则滚动等场景会白白触发绘制
    override val shouldAutoInvalidate: Boolean = false

    // 离屏图层按需复用；paint/path 也缓存，减少绘制阶段的分配
    private var shadowLayer: GraphicsLayer? = null
    private val paint = Paint()
    private val clipPath = Path()
    // 仅在模糊半径真正变化时才重建 RenderEffect（构造成本较高）
    private var prevRadius = Float.NaN

    /**
     * 先画内容，再把内阴影叠加在上层。
     *
     * 阴影必须后画，否则会被内容遮住；同时绘制前重新生成裁剪 Path，
     * 以支持 Rounded/Rectangle/Generic 三类 Outline。
     */
    override fun ContentDrawScope.draw() {
        drawContent()

        val shadow = shadow() ?: return
        val layer = shadowLayer ?: return

        // 阴影参数在绘制时才读取，动画中的值无需重建 Modifier
        val radius = shadow.radius.toPx()
        val offsetX = shadow.offset.x.toPx()
        val offsetY = shadow.offset.y.toPx()

        // 统一转成 Path，后续 clipPath 只需要维护一种裁剪来源
        val outline = shape.createOutline(size, layoutDirection, this)
        clipPath.reset()
        when (outline) {
            is Outline.Rectangle -> clipPath.addRect(outline.rect)
            is Outline.Rounded -> clipPath.addRoundRect(outline.roundRect)
            is Outline.Generic -> clipPath.addPath(outline.path)
        }

        paint.color = shadow.color
        layer.alpha = shadow.alpha
        layer.blendMode = shadow.blendMode
        // Decal 让模糊采样止步于图层边界，避免边缘透明处被拉出灰边
        if (prevRadius != radius) {
            layer.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            prevRadius = radius
        }

        layer.record {
            drawContext.canvas.let { canvas ->
                canvas.save()
                canvas.clipPath(clipPath)
                // 先铺满阴影色，再用 Clear 在偏移位置“挖掉”一个形状，得到环形差值
                canvas.drawOutline(outline, paint)
                canvas.translate(offsetX, offsetY)
                canvas.drawOutline(outline, ShadowMaskPaint)
                canvas.translate(-offsetX, -offsetY)
                canvas.restore()
            }
        }

        drawContext.canvas.let { canvas ->
            canvas.save()
            canvas.clipPath(clipPath)
            drawLayer(layer)
            canvas.restore()
        }
    }

    /** 创建离屏图层；Offscreen 合成策略是 Clear 混合正确生效的前提 */
    override fun onAttach() {
        shadowLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    /** 节点移除时必须显式释放图层，否则频繁进出组合会泄漏显存 */
    override fun onDetach() {
        shadowLayer?.let { layer ->
            requireGraphicsContext().releaseGraphicsLayer(layer)
            shadowLayer = null
        }
    }
}

// 全局共享的“挖洞”画笔：Clear 会把目标 alpha 直接清零，形成真正的透明缺口
private val ShadowMaskPaint: Paint = Paint().apply {
    blendMode = BlendMode.Clear
}
