package me.weishu.kernelsu.ui.util.module

/**
 * GitHub Release 中解析出的最新版本信息。
 *
 * 默认值（0/空串）表示查询失败或尚未获取，UI 应据此隐藏更新提示。
 */
data class LatestVersionInfo(
    val versionCode: Int = 0,
    // 与服务端 versionCode 比较，只有更大时才提示更新。
    val downloadUrl: String = "",
    // Release 正文，用于展示更新日志。
    val changelog: String = ""
)
