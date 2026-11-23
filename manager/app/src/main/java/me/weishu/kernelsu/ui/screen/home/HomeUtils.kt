package me.weishu.kernelsu.ui.screen.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat

/**
 * 管理器自身的版本信息。
 * versionCode 使用 Long，是因为 Android 的 longVersionCode 可能超出 Int 范围。
 */
@Immutable
data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

/**
 * 首页展示用的系统信息快照，全部为字符串以便直接渲染。
 * seccompStatus 沿用内核返回值：-1 不支持、0 关闭、1 严格、2 过滤。
 */
@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int
)

/**
 * 读取当前管理器包的版本名与 long 版本号。
 * 版本号用于和内核/LKM 版本比较，因此必须取 longVersionCode 而不是已弃用的 versionCode。
 */
fun getManagerVersion(context: Context): ManagerVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    // PackageInfoCompat 同时兼容新旧 Android 的版本号编码方式。
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return ManagerVersion(
        versionName = packageInfo.versionName!!,
        versionCode = versionCode
    )
}
