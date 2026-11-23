package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.data.model.ModuleUpdateInfo

/** 已安装模块的读取入口；数据来自 ksud，调用方不应在主线程直接使用。 */
interface ModuleRepository {
    /** 读取全部已安装模块，按 ksud 返回顺序。 */
    suspend fun getModules(): Result<List<Module>>
    /** 请求模块 updateJson，比较 versionCode 后返回可用更新；无更新返回 [ModuleUpdateInfo.Empty]。 */
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
