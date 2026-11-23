package me.weishu.kernelsu.ui.util

import android.annotation.SuppressLint
import android.os.Build
import java.io.File

// 三星系统里保存“商品名”的类，读取需要反射私有 API。
private const val SAMSUNG_FLOATING_FEATURE_CLASS = "com.samsung.android.feature.SemFloatingFeature"
// SemFloatingFeature 中保存商品名的键。
private const val KEY_SAMSUNG_PRODUCT_NAME = "SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"

// 反射失败时回退到解析 floating_feature.xml；不同分区路径都要尝试。
private val SAMSUNG_FLOATING_FEATURE_PATHS = listOf(
    "/vendor/etc/floating_feature.xml",
    "/system/etc/floating_feature.xml",
)

/**
 * Resolve the commercial device name.
 * 1. Dispatch by [Build.MANUFACTURER] — each vendor branch uses its own property
 *    or dedicated helper (e.g. Samsung).
 * 2. If the vendor branch returns nothing, Fallback to MANUFACTURER + BRAND + MODEL.
 *
 * 说明：优先读取厂商自定义属性以获得市场名称；这些属性并非标准 API，
 * 读取失败时必须回退到 Build 字段，不能抛异常影响主流程。
 */
fun resolveDeviceName(): String {
    // 按厂商分派，只有对应属性存在时才采用。
    val marketName = when (Build.MANUFACTURER.orEmpty().lowercase()) {
        "samsung" -> getSamsungProductName()
        "xiaomi" -> getSystemProperty("ro.product.marketname").takeIfValidDeviceName()
        "oppo", "oneplus", "realme", "oplus" ->
            getSystemProperty("ro.vendor.oplus.market.name").takeIfValidDeviceName()

        "vivo" -> getSystemProperty("ro.vivo.market.name").takeIfValidDeviceName()
        "honor", "huawei" -> getSystemProperty("ro.config.marketing_name").takeIfValidDeviceName()
        "zte", "nubia" -> getSystemProperty("ro.vendor.product.ztename").takeIfValidDeviceName()

        else -> null
    }
    if (marketName != null) return marketName

    return buildString {
        append(Build.MANUFACTURER)
        if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) append(" ").append(Build.BRAND)
        append(" ").append(Build.MODEL)
    }
}

// 三星商品名：先走 SemFloatingFeature，失败再解析配置文件。
private fun getSamsungProductName(): String? {
    val name = getSamsungProductNameFromFloatingFeature()
        ?: getSamsungProductNameFromFloatingFeatureFile()
    return name.withSamsungPrefix()
}

// 通过隐藏 API 读取；任何异常（类不存在/权限限制）都视为不可用。
@SuppressLint("PrivateApi")
private fun getSamsungProductNameFromFloatingFeature(): String? = try {
    val clazz = Class.forName(SAMSUNG_FLOATING_FEATURE_CLASS)
    val instance = clazz.getMethod("getInstance").invoke(null)
    val value = clazz.getMethod("getString", String::class.java)
        .invoke(instance, KEY_SAMSUNG_PRODUCT_NAME) as? String
    value.takeIfValidDeviceName()
} catch (_: Throwable) {
    null
}

// 解析 XML 文本取标签内容；DOT_MATCHES_ALL 允许值跨行。
private fun getSamsungProductNameFromFloatingFeatureFile(): String? {
    val pattern = Regex(
        "<$KEY_SAMSUNG_PRODUCT_NAME>\\s*(.*?)\\s*</$KEY_SAMSUNG_PRODUCT_NAME>",
        RegexOption.DOT_MATCHES_ALL,
    )
    return SAMSUNG_FLOATING_FEATURE_PATHS.firstNotNullOfOrNull { path ->
        try {
            pattern.find(File(path).readText())?.groupValues?.getOrNull(1).takeIfValidDeviceName()
        } catch (_: Throwable) {
            null
        }
    }
}

// 过滤空值、系统占位符 "unknown" 和字符串 "null"，保证展示名称有意义。
private fun String?.takeIfValidDeviceName(): String? =
    this?.trim()?.takeIf {
        it.isNotEmpty() &&
                !it.equals(Build.UNKNOWN, ignoreCase = true) &&
                !it.equals("null", ignoreCase = true)
    }

// 部分三星型号只返回型号数字，补上 Samsung 前缀便于识别。
private fun String?.withSamsungPrefix(): String? {
    val name = this.takeIfValidDeviceName() ?: return null
    return if (name.startsWith("Samsung", ignoreCase = true)) name else "Samsung $name"
}
