package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable

/**
 * 已安装模块的展示模型，字段来源于 ksud `module list` 输出的 JSON（模块目录/module.prop）。
 * 该模型只读，模块状态变化后需重新拉取列表。
 */
@Immutable
data class Module(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val versionCode: Int,
    val description: String,
    val enabled: Boolean,
    /** 模块目录下存在 `update` 标记，表示有等待下一次启动应用的更新。 */
    val update: Boolean,
    /** 模块目录下存在 `remove` 标记，表示下次启动时会被卸载。 */
    val remove: Boolean,
    /** module.prop 中配置的更新描述 URL；为空表示不支持在线检查更新。 */
    val updateJson: String,
    /** 模块是否带 WebUI（webroot 目录）。 */
    val hasWebUi: Boolean,
    /** 是否有 action.sh，可在模块页面直接执行。 */
    val hasActionScript: Boolean,
    /** 是否为元模块（module.prop 中 metamodule=1 且被 ksud 激活）。 */
    val metamodule: Boolean,
    /** action 图标在磁盘上的绝对路径；null 表示未配置。 */
    val actionIconPath: String?,
    /** WebUI 图标在磁盘上的绝对路径；null 表示未配置。 */
    val webUiIconPath: String?,
    // preinit state for the next boot: "", "ok", "no_space", "error" or "bootloop"
    val preinit: String = "",
)
