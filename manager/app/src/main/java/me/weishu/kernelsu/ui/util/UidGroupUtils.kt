package me.weishu.kernelsu.ui.util

import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import java.util.concurrent.ConcurrentHashMap

// 共享 uid 组里通常有多个包，优先展示系统代表性包名。
private val PREFERRED_PKG_BY_SUID = mapOf(
    "android.uid.system" to "android",
    "android.uid.phone" to "com.android.phone",
    "android.uid.bluetooth" to "com.android.bluetooth",
    "android.uid.nfc" to "com.android.nfc",
)

/**
 * 从同一 uid（共享 uid 组）的多个应用中挑出一个代表。
 *
 * 优先使用带 sharedUserLabel 的应用，其次按 `android.uid.*` 组的已知映射，
 * 最后用“包名更短、字典序更小”作为稳定兜底。
 */
fun pickPrimary(apps: List<SuperUserViewModel.AppInfo>): SuperUserViewModel.AppInfo {
    if (apps.isEmpty()) throw IllegalArgumentException("apps must not be empty")
    // sharedUserLabel 非 0 表示系统为整个共享 uid 提供了统一名称。
    val labeled = apps.filter { it.packageInfo.sharedUserLabel != 0 }
    if (labeled.isNotEmpty()) {
        return labeled.minWith(compareBy({ it.packageName.length }, { it.packageName }))
    }
    // 按 sharedUserId 分组，只关心系统级 android.uid.* 组。
    val bySuid = apps.groupBy { it.packageInfo.sharedUserId ?: "" }
        .filterKeys { it.startsWith("android.uid.") }
    if (bySuid.isEmpty()) return apps.first()
    val suid = bySuid.keys.minOf { it }
    val group = bySuid[suid] ?: apps
    val preferredPkg = PREFERRED_PKG_BY_SUID[suid]
    preferredPkg?.let { pkg ->
        group.firstOrNull { it.packageName == pkg }?.let { return it }
    }
    return group.minWith(compareBy({ it.packageName.length }, { it.packageName }))
}

// uid 到展示名的缓存；避免列表滚动时反复查询 PackageManager / 内核。
val ownerNameCache = ConcurrentHashMap<Int, String>()

/**
 * 获取 uid 对应的可读名称。
 *
 * 共享 uid 优先使用系统标签或 sharedUserId；普通应用回退到内核提供的
 * 用户名，最后用数字 uid 兜底，保证永远有可展示文本。
 */
fun ownerNameForUid(uid: Int, appSource: List<SuperUserViewModel.AppInfo>? = null): String {
    ownerNameCache[uid]?.let { return it.ifEmpty { uid.toString() } }
    val apps = appSource ?: SuperUserViewModel.apps.filter { it.uid == uid }
    val labeledApp = apps.firstOrNull { it.packageInfo.sharedUserLabel != 0 }
    val name = if (labeledApp != null) {
        val pm = ksuApp.packageManager
        val resId = labeledApp.packageInfo.sharedUserLabel
        val text = runCatching { pm.getText(labeledApp.packageName, resId, labeledApp.packageInfo.applicationInfo) }.getOrNull()
        text?.toString() ?: ""
    } else {
        Natives.getUserName(uid) ?: ""
    }
    // Android 应用 uid 的 appId 落在 10000..19999，用于区分系统/隔离用户。
    val appId = uid % 100000
    val isAppRange = appId in 10000..19999
    // 内核返回的默认名形如 u0_a123，说明没有可用的人类可读名称。
    val isUA = name.matches(Regex("u\\d+_a\\d+"))
    val sharedUserId = apps.firstOrNull { !it.packageInfo.sharedUserId.isNullOrEmpty() }?.packageInfo?.sharedUserId
    val finalName = if (isAppRange && isUA && !sharedUserId.isNullOrEmpty()) {
        sharedUserId
    } else {
        name
    }
    ownerNameCache[uid] = finalName
    return finalName.ifEmpty { uid.toString() }
}
