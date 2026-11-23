package me.weishu.kernelsu.ui.webui.pm

import android.content.pm.ApplicationInfo
import android.webkit.JavascriptInterface
import androidx.core.content.pm.PackageInfoCompat
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.ui.webui.webUIApps
import org.json.JSONArray
import org.json.JSONObject

/**
 * 暴露给 WebUI 的 PackageManager 接口，作为 `ksu.pm()` 注入。
 *
 * 数据来自应用缓存的已安装应用列表；特殊/隔离应用会被过滤，避免暴露
 * 内部信息。返回值统一为 JSON 字符串。
 */
object KsuPm {
    private const val KSU_PM_VERSION = 1

    /** 返回 PM 桥接协议版本，便于 JS 做兼容判断。 */
    @JavascriptInterface
    fun version(): Int = KSU_PM_VERSION

    /** 按 system/user 过滤并排序包名，返回 JSON 数组字符串。 */
    @JavascriptInterface
    fun listPackages(type: String): String {
        val packageNames = webUIApps()
            .filter { appInfo ->
                // 特殊/隔离应用不暴露给 WebUI。
                if (appInfo.special) return@filter false
                // FLAG_SYSTEM 区分系统应用与用户应用。
                val flags = appInfo.packageInfo.applicationInfo?.flags ?: 0
                when (type.lowercase()) {
                    "system" -> (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    "user" -> (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    else -> true
                }
            }
            .map { it.packageName }
            .distinct()
            .sorted()

        val jsonArray = JSONArray()
        for (pkgName in packageNames) {
            jsonArray.put(pkgName)
        }
        return jsonArray.toString()
    }

    /**
     * 批量查询包信息。
     *
     * 同一包名可能对应多个用户（工作资料），因此结果可能多于请求数量；
     * 找不到的包会返回带 error 字段的对象而不是直接丢弃。
     */
    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val packageNames = JSONArray(packageNamesJson)
        val appsByPackage = webUIApps()
            .filterNot { it.special }
            .groupBy { it.packageName }
        val jsonArray = JSONArray()
        for (i in 0 until packageNames.length()) {
            val pkgName = packageNames.getString(i)
            val matches = appsByPackage[pkgName]
            if (matches.isNullOrEmpty()) {
                val error = JSONObject()
                error.put("packageName", pkgName)
                error.put("error", "Package not found or inaccessible")
                jsonArray.put(error)
                continue
            }
            for (appInfo in matches.sortedBy { it.uid }) {
                jsonArray.put(buildInfo(appInfo))
            }
        }
        return jsonArray.toString()
    }

    // 把内部 AppInfo 转成 JS 友好的 JSON；缺失字段用 JSONObject.NULL 表示。
    private fun buildInfo(appInfo: AppInfo): JSONObject {
        val pkg = appInfo.packageInfo
        val app = pkg.applicationInfo
        return JSONObject().apply {
            put("packageName", pkg.packageName)
            put("versionName", pkg.versionName ?: "")
            put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
            put("appLabel", appInfo.label)
            put("isSystem", app?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: JSONObject.NULL)
            put("uid", app?.uid ?: JSONObject.NULL)
            // uid 高位即 userId，便于 JS 区分多用户/工作资料。
            put("userId", app?.let { it.uid / 100000 } ?: JSONObject.NULL)
        }
    }
}
