package me.weishu.kernelsu.ui.navigation3

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.modulerepo.RepoModuleArg
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.util.FlashItSerializer
import me.weishu.kernelsu.ui.util.RepoModuleArgSerializer
import me.weishu.kernelsu.ui.util.ScriptRunArgSerializer
import me.weishu.kernelsu.ui.util.TemplateInfoSerializer
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe navigation keys for miuix-nav.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey, Parcelable {
    /** 底部导航容器页，内部再承载各个主标签页。 */
    @Parcelize
    @Serializable
    data object Main : Route

    /** 首页标签页。 */
    @Parcelize
    @Serializable
    data object Home : Route

    /** 超级用户（授权管理）标签页。 */
    @Parcelize
    @Serializable
    data object SuperUser : Route

    /** 模块管理标签页。 */
    @Parcelize
    @Serializable
    data object Module : Route

    /** 设置标签页。 */
    @Parcelize
    @Serializable
    data object Settings : Route

    /** 关于页面，展示版本与项目信息。 */
    @Parcelize
    @Serializable
    data object About : Route

    /** 内核日志（sulog）查看页。 */
    @Parcelize
    @Serializable
    data object Sulog : Route

    /** 配色自定义页，编辑强调色与调色板。 */
    @Parcelize
    @Serializable
    data object ColorPalette : Route

    /** 应用配置模板列表页。 */
    @Parcelize
    @Serializable
    data object AppProfileTemplate : Route

    /**
     * 模板编辑器。
     *
     * 携带完整模板对象而非 id，使编辑过程不依赖外部存储的即时状态；
     * [readOnly] 为 true 时用于查看内置模板。
     */
    @Parcelize
    @Serializable
    data class TemplateEditor(
        @Serializable(with = TemplateInfoSerializer::class) val template: TemplateViewModel.TemplateInfo,
        val readOnly: Boolean
    ) : Route

    /** 单个应用（按 uid 区分）的配置页。 */
    @Parcelize
    @Serializable
    data class AppProfile(val uid: Int) : Route

    /** 刷写/安装向导页。 */
    @Parcelize
    @Serializable
    data object Install : Route

    /** 模块仓库中某个模块的详情页，模块信息随路由一起携带。 */
    @Parcelize
    @Serializable
    data class ModuleRepoDetail(@Serializable(with = RepoModuleArgSerializer::class) val module: RepoModuleArg) : Route

    /** 模块仓库列表页。 */
    @Parcelize
    @Serializable
    data object ModuleRepo : Route

    /** 刷写任务页，携带待执行任务的快照。 */
    @Parcelize
    @Serializable
    data class Flash(@Serializable(with = FlashItSerializer::class) val flashIt: FlashIt) : Route

    /**
     * 执行模块 action.sh。
     *
     * [fromShortcut] 标记是否由桌面快捷方式进入，用于决定返回行为。
     */
    @Parcelize
    @Serializable
    data class ExecuteModuleAction(val moduleId: String, val fromShortcut: Boolean = false) : Route

    /** 脚本运行页，携带脚本参数快照。 */
    @Parcelize
    @Serializable
    data class ScriptRun(@Serializable(with = ScriptRunArgSerializer::class) val arg: ScriptRunArg) : Route
}
