package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable

/** 从模块 updateJson 解析出的可用更新；[Empty] 表示无更新或检查失败。 */
@Immutable
data class ModuleUpdateInfo(
    val downloadUrl: String,
    val version: String,
    val changelog: String,
    val versionCode: Int = 0,
) {
    companion object {
        /** 空结果，调用方统一用它表示“没有可用更新”。 */
        val Empty = ModuleUpdateInfo("", "", "")
    }
}
