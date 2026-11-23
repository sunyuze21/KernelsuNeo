package me.weishu.kernelsu.data.model

import android.content.pm.PackageInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.Natives

/** WebView Zygote 的固定 UID；它没有独立包名，SuperUser 列表里用特殊 AppInfo 表示。 */
const val WEBVIEW_ZYGOTE_UID = 1053
/** WebView Zygote 在内核 profile 中使用的 key（而非包名）。 */
const val WEBVIEW_ZYGOTE_PROFILE_KEY = "webview_zygote"

/**
 * SuperUser 列表中的一行：应用信息 + 其内核 profile。
 * [profileKey] 通常等于包名，但 WebView Zygote 等特殊 UID 使用自定义 key。
 */
@Parcelize
data class AppInfo(
    val label: String,
    val packageInfo: PackageInfo,
    /** 内核返回的当前 profile；null 表示读取失败，UI 应展示为默认/未知。 */
    val profile: Natives.Profile?,
    /** 查询/写入 profile 时使用的 key；默认使用包名。 */
    val profileKey: String = packageInfo.packageName,
    /** 是否为特殊条目（如 WebView Zygote），此时显示名与 UID 不来自普通包信息。 */
    val special: Boolean = false,
) : Parcelable {
    val packageName: String
        get() = packageInfo.packageName

    /** 列表中展示用的稳定标识：特殊条目用 profileKey，普通应用用包名。 */
    val displayIdentifier: String
        get() = if (special) profileKey else packageName
    val uid: Int
        get() = packageInfo.applicationInfo!!.uid

    /** 是否为 WebView Zygote 条目；它不能获得 root，只用于配置非 root 策略。 */
    val isWebViewZygote: Boolean
        get() = special && uid == WEBVIEW_ZYGOTE_UID

    /** 是否已授予 root；WebView Zygote 永远为 false。 */
    val allowSu: Boolean
        get() = !isWebViewZygote && profile != null && profile.allowSu
    /** 是否偏离了默认 profile（用于 UI 标记“已自定义”）。 */
    val hasCustomProfile: Boolean
        get() {
            return profile != null && if (isWebViewZygote) {
                !profile.nonRootUseDefault
            } else if (profile.allowSu) {
                !profile.rootUseDefault
            } else {
                !profile.nonRootUseDefault
            }
        }
}
