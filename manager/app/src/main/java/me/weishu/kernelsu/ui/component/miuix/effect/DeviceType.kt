// Mirrored from compose-miuix-ui example.

package me.weishu.kernelsu.ui.component.miuix.effect

/**
 * 背景动效使用的设备形态。
 *
 * 用于在手机与平板上选择不同的渐变点位/配色（平板视野更宽，需要更平缓的色块分布）；
 * 命名对齐 miuix 示例，便于后续同步上游参数。
 */
enum class DeviceType {
    PHONE,
    PAD,
}
