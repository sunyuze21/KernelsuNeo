package me.weishu.kernelsu.ui.component.miuix.animation

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.component.miuix.modifier.inspectDragGestures
import org.intellij.lang.annotations.Language

/**
 * 跟随手指的交互高光。
 *
 * 绘制分两层：一层整体提亮（Plus 混合的白色薄层），另一层是运行时着色器画的
 * 径向光斑，位置随拖动实时 snapTo 到指尖。两层都用 Plus 混合而不是直接改
 * 背景色，因此可以叠加在任何内容之上而不破坏原有颜色。仅在按压进度 > 0 时绘制，
 * 松手后动画归零就自动停止绘制，不会长期占用 GPU。
 *
 * 该效果依赖 RuntimeShader（API 33+），调用方需自行确保版本兼容。
 *
 * @param animationScope 驱动高光动画的协程作用域
 * @param position 把原始触点映射到实际光斑位置的策略，默认原样使用
 */
@SuppressLint("NewApi")
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    // 进度与位置都使用较软的弹簧：高光跟随手指时略微滞后，观感更自然
    private val pressProgressAnimationSpec =
        spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    // 记录按下点，松手后位置动画回到这里而不是原点，避免高光“飞走”
    private var startPosition = Offset.Zero
    /** 当前光斑相对按下点的偏移，供调用方做轻微位移联动 */
    val offset: Offset get() = positionAnimation.value - startPosition

    // 径向光斑 shader：smoothstep 让中心最亮、边缘平滑衰减，避免出现硬边圆
    @Language("AGSL")
    private val shader =
        RuntimeShader(
            """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;
    
    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""
        )

    /**
     * 绘制高光。
     *
     * 顺序上先画光斑、再 drawContent：Plus 混合作为内容的底层提亮，
     * 文字仍保持清晰；若画在内容之上会降低文字对比度。
     */
    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            // 未按压时完全跳过绘制，避免无谓的 shader uniform 更新
            if (progress > 0f) {
                drawRect(
                    Color.White.copy(0.06f * progress),
                    blendMode = BlendMode.Plus
                )
                shader.apply {
                    val position = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    // 光斑透明度随按压进度淡入，避免按下瞬间突然出现一个亮斑
                    setColorUniform("color", Color.White.copy(0.12f * progress).toArgb())
                    // 半径取最短边的 1.2 倍，保证光斑足够大、边缘衰减完整落在控件内
                    setFloatUniform("radius", size.minDimension * 1.2f)
                    setFloatUniform(
                        "position",
                        position.x.fastCoerceIn(0f, size.width),
                        position.y.fastCoerceIn(0f, size.height)
                    )
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus
                )
            }

            drawContent()
        }

    /** 拖动期间实时同步光斑位置；按下/抬起/取消分别淡入淡出并回位 */
    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                }
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}