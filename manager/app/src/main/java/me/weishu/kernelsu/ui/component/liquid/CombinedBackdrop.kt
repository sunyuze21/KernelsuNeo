// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 把两个 [Backdrop] 串联成一个绘制单元。
 *
 * 液体玻璃通常需要“模糊背景 + 折射/内阴影”两层效果，而单个 Backdrop
 * 只能承载一条效果链；这里按顺序依次执行两个 drawBackdrop，
 * 后者的输入即前者的输出，从而组合出多层视觉效果。
 *
 * `isCoordinatesDependent` 取逻辑或：任一子效果依赖坐标时，
 * 整体都必须在坐标确定后重绘，否则会用到过期的背景采样。
 */
@Stable
class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    /**
     * 顺序绘制两层背景。
     *
     * 两个子 Backdrop 共享同一组参数，因此它们必须对同一块区域、
     * 同一缩放比例采样，否则折射边缘会出现错位。
     */
    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

/**
 * 记忆化创建组合背景。
 *
 * 以两个子 Backdrop 实例为 key：它们通常在外部已被 remember，
 * 引用稳定时不会产生新对象，避免每帧重建导致背景效果反复重新初始化。
 */
@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    remember(first, second) { CombinedBackdrop(first, second) }
