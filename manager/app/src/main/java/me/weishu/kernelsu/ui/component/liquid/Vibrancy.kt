// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/**
 * 提升背景的饱和度为 1.5 倍。
 *
 * 玻璃模糊本身会平均邻近像素，视觉上容易显得发灰；适度拉高饱和度可以
 * 让透过玻璃的图标/缩略图仍保有辨识度。亮度与对比度保持不变，
 * 避免改变整体明暗关系、影响前景文字对比度。
 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
