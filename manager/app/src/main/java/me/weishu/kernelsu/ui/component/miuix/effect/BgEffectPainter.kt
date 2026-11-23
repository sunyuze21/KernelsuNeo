// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.miuix.effect

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import kotlin.math.cos
import kotlin.math.sin

/**
 * 动态背景的 shader 包装与 uniform 缓存。
 *
 * 为避免每帧都向 GPU 上传全部 uniform，每个参数都记录上次值，只有真正变化时
 * 才 `setFloatUniform`；数组 uniform 通过复用的 FloatArray 提交，减少 GC 抖动。
 * 该类非线程安全，只应在绘制线程使用；shader 编译本身是懒加载的，
 * 保证不支持 RuntimeShader 的设备根本不会触发编译。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BgEffectPainter {

    // 懒加载：只有真正需要绘制背景时才编译 AGSL，避免拖慢首帧
    val runtimeShader by lazy {
        RuntimeShader(OS3_BG_FRAG).also {
            initStaticUniforms(it)
        }
    }

    /** 把当前 uniform 状态转成可直接用于 drawRect 的 Brush */
    val brush: Brush get() = runtimeShader.asBrush()

    // 复用的 uniform 缓冲区；长度与 shader 中的数组声明严格对应
    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colorsBuffer = FloatArray(16)
    private val pointsAnimBuffer = FloatArray(8)

    private var animTime = Float.NaN
    private var isDarkCached: Boolean? = null
    private var deviceTypeCached: DeviceType? = null

    private var presetApplied = false

    private var cachedLogoHeight = Float.NaN
    private var cachedTotalHeight = Float.NaN
    private var cachedTotalWidth = Float.NaN

    private var cachedColorStage = Float.NaN
    private var cachedColorsPreset: BgEffectConfig.Config? = null

    private var cachedPointsAnimTime = Float.NaN
    private var cachedPointsAnimPreset: BgEffectConfig.Config? = null

    companion object {

        // 静态 uniform：这些值在运行期不变，初始化时一次性写入即可
        private const val U_TRANSLATE_Y = 0f
        private const val U_ALPHA_MULTI = 1f
        private const val U_NOISE_SCALE = 1.5f
        private const val U_POINT_RADIUS_MULTI = 1f
    }

    /** 写入不会变化的 uniform；shader 名称必须与 AGSL 声明一致 */
    private fun initStaticUniforms(shader: RuntimeShader) {
        shader.setFloatUniform("uTranslateY", U_TRANSLATE_Y)
        shader.setFloatUniform("uNoiseScale", U_NOISE_SCALE)
        shader.setFloatUniform("uPointRadiusMulti", U_POINT_RADIUS_MULTI)
        shader.setFloatUniform("uAlphaMulti", U_ALPHA_MULTI)
    }

    /** 更新画布分辨率；相同尺寸直接跳过，避免无意义的 uniform 上传 */
    fun updateResolution(width: Float, height: Float) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader.setFloatUniform("uResolution", resolution)
    }

    /** 更新时间 uniform；时间戳相同则复用上一帧的 GPU 状态 */
    fun updateAnimTime(time: Float) {
        if (animTime == time) return
        animTime = time
        runtimeShader.setFloatUniform("uAnimTime", animTime)
    }

    /**
     * 计算四个控制点的漂移位置。
     *
     * 用正余弦让 x/y 相位互相偏移，形成缓慢的“绕圈”运动而不是同步平移；
     * 位移幅度由预设的 pointOffset 控制。结果写入复用缓冲区后整体上传。
     */
    fun updatePointsAnim(time: Float, preset: BgEffectConfig.Config) {
        if (cachedPointsAnimTime == time && cachedPointsAnimPreset === preset) return

        val offset = preset.pointOffset
        var i = 0
        while (i < 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            val animX = srcX + sin(time + srcY) * offset
            val animY = srcY + cos(time + animX) * offset
            pointsAnimBuffer[i * 2] = animX
            pointsAnimBuffer[i * 2 + 1] = animY
            i++
        }
        runtimeShader.setFloatUniform("uPointsAnim", pointsAnimBuffer)

        cachedPointsAnimTime = time
        cachedPointsAnimPreset = preset
    }

    /**
     * 按颜色阶段插值出当前的四组 RGBA。
     *
     * stage 的整数部分决定取哪两组预设色，小数部分作为插值系数；
     * 相邻两组颜色交替循环，避免色相在循环点出现硬切。
     */
    fun updateColors(preset: BgEffectConfig.Config, stage: Float) {
        if (cachedColorsPreset === preset && cachedColorStage == stage) return

        val base = stage.toInt()
        val fraction = stage - base
        val start = colorsForCycleIndex(preset, base)
        val end = colorsForCycleIndex(preset, base + 1)
        for (i in 0 until 16) {
            colorsBuffer[i] = start[i] + (end[i] - start[i]) * fraction
        }
        runtimeShader.setFloatUniform("uColors", colorsBuffer)

        cachedColorsPreset = preset
        cachedColorStage = stage
    }

    /** 把循环索引映射到三组预设色；索引 2 与 0 都回到 colors2，保证首尾平滑衔接 */
    private fun colorsForCycleIndex(preset: BgEffectConfig.Config, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }

    /**
     * 按可见区域更新 shader 的绘制边界。
     *
     * logoHeight 决定渐变内容在屏幕纵向的占比；宽屏时按纵横比扩展边界，
     * 使色块不被拉伸变形。三个尺寸任一变化都需要重新计算并上传。
     */
    fun updateBoundIfNeeded(
        logoHeight: Float,
        totalHeight: Float,
        totalWidth: Float,
    ) {
        if (cachedLogoHeight == logoHeight &&
            cachedTotalHeight == totalHeight &&
            cachedTotalWidth == totalWidth
        ) {
            return
        }

        updateBound(logoHeight, totalHeight, totalWidth)
        runtimeShader.setFloatUniform("uBound", bound)

        cachedLogoHeight = logoHeight
        cachedTotalHeight = totalHeight
        cachedTotalWidth = totalWidth
    }

    /** 设备形态或明暗主题变化时重新应用预设；相同组合直接跳过 */
    fun updatePresetIfNeeded(deviceType: DeviceType, isDark: Boolean) {
        if (presetApplied && isDarkCached == isDark && deviceTypeCached == deviceType) return

        applyPreset(deviceType, isDark)

        isDarkCached = isDark
        deviceTypeCached = deviceType
        presetApplied = true
    }

    /** 把预设中的点位与亮度/饱和度系数写入 shader */
    private fun applyPreset(deviceType: DeviceType, isDark: Boolean) {
        val preset = BgEffectConfig.get(deviceType, isDark)

        runtimeShader.setFloatUniform("uPoints", preset.points)
        runtimeShader.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
    }

    /**
     * 计算归一化绘制边界。
     *
     * 竖屏时内容只占顶部一条区域，边界直接取 logo 高度比例；
     * 横屏时按纵横比放大高度并居中，防止四个色团被横向拉扁。
     */
    private fun updateBound(
        logoHeight: Float,
        totalHeight: Float,
        totalWidth: Float,
    ) {
        val heightRatio = logoHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
    }
}
