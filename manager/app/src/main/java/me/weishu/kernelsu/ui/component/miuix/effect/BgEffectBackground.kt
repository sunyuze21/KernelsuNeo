// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.miuix.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.shouldShowSplitPane
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

/**
 * 运行时着色器驱动的动态渐变背景。
 *
 * 依赖 RuntimeShader（Android 13+）：不支持时直接退化为普通 Box，
 * 不产生任何动画或绘制开销，调用方无需自行判断能力。
 * 背景由 [BgEffectPainter] 绘制，颜色阶段值 `colorStage` 每隔半个插值周期
 * 递增并通过弹簧动画过渡，使色块缓慢漂移；仅在 `dynamicBackground` 为真时启动，
 * 静态背景可完全省掉每帧重绘。
 *
 * @param dynamicBackground 是否播放颜色漂移动画
 * @param modifier 外层修饰符
 * @param bgModifier 仅作用于背景层的修饰符（例如模糊、裁剪）
 * @param isFullSize 是否按全屏尺寸绘制，影响 shader 中的边界计算
 * @param effectBackground 是否启用背景特效本身，false 时保留占位以便动画过渡
 * @param alpha 背景整体透明度，以 lambda 形式读取避免每帧重组
 * @param content 覆盖在背景之上的页面内容
 */
@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(
        modifier = modifier,
    ) {
        val surface = MiuixTheme.colorScheme.surface
        // 分屏/大屏时用 PAD 预设，色彩变化更平缓，避免大块区域的漂移过于抢眼
        val deviceType = if (shouldShowSplitPane()) DeviceType.PAD else DeviceType.PHONE
        val isDarkTheme = isInDarkTheme()
        // Painter 内部持有 shader 程序，必须跨重组复用，否则每次组合都会重新编译
        val painter = remember { BgEffectPainter() }

        // 预设只随设备形态/主题切换重建，四套参数均为常量
        val preset = remember(deviceType, isDarkTheme) {
            BgEffectConfig.get(deviceType, isDarkTheme)
        }

        // 用 Animatable 承载“颜色阶段”，整数值切换、段内由弹簧插值，避免手动维护帧计数
        val colorStage = remember { Animatable(0f) }

        // 静态背景或三组颜色完全相同时无需循环动画，直接跳过以省电
        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            val animatesColors = preset.colors1 !== preset.colors2 || preset.colors2 !== preset.colors3
            if (!animatesColors) return@LaunchedEffect

            var targetStage = floor(colorStage.value) + 1f
            // 每隔半个周期推进一个阶段；弹簧阻尼接近 1，色块过渡既不生硬也不拖沓
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong().milliseconds)
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}
