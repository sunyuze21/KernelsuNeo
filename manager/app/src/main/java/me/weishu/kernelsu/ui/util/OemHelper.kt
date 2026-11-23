package me.weishu.kernelsu.ui.util

import android.annotation.SuppressLint

/**
 * 读取 Android 系统属性。
 *
 * `android.os.SystemProperties` 是隐藏 API，部分 ROM 会限制反射；任何失败
 * 都返回空串而不是抛异常。
 */
@SuppressLint("PrivateApi")
internal fun getSystemProperty(key: String): String {
    return try {
        val props = Class.forName("android.os.SystemProperties")
        props.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }
}

/** 是否为 MIUI（通过 MIUI 版本属性判断）。 */
fun isMiui(): Boolean {
    return getSystemProperty("ro.miui.ui.version.name").isNotEmpty()
}

/** 是否为小米 HyperOS；HyperOS 与 MIUI 是不同分支，需要分别判断。 */
fun isHyperOS(): Boolean {
    return getSystemProperty("ro.mi.os.version.name").isNotEmpty()
}

/** 是否为 OPPO/一加/realme 的 ColorOS 系系统。 */
fun isColorOS(): Boolean {
    return getSystemProperty("ro.build.version.oplus.api").isNotEmpty() || getSystemProperty("ro.vendor.oplus.market.name").isNotEmpty()
}
