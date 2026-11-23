// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.miuix.effect

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * 模糊背景的调色 token。
 *
 * 每项是若干“颜色 + 混合模式”的组合，按顺序叠加到模糊结果上。
 * 混合模式决定玻璃质感：Overlay/HardLight 提亮高光，Luminosity 只取亮度，
 * PlusDarker 压暗边缘；改动时需同时检查深色背景下的对比度。
 */
object ColorBlendToken {

    /** 通透常规亮色：冷色叠加 + 强高光，适合浅色玻璃面板 */
    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )

    /** 轻薄覆盖层：以亮度混合为主，几乎不改变原色相，适合叠在图片上 */
    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )
}
