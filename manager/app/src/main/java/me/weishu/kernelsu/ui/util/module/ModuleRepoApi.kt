package me.weishu.kernelsu.ui.util.module

/**
 * 把版本号清洗成可安全用于文件名/URL 的形式。
 *
 * 只保留字母、数字、点、连字符和下划线，其余字符替换为 `_`，
 * 防止路径穿越或非法字符导致下载失败。
 */
fun sanitizeVersionString(version: String): String {
    return version.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_")
}
