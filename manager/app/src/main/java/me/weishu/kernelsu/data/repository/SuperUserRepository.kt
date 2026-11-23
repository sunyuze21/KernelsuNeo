package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.AppInfo

/**
 * SuperUser 列表数据源：通过绑定 root 服务读取包列表，并逐个查询内核 profile。
 * 需要 root；返回的第二个列表是设备上的用户 id。
 */
interface SuperUserRepository {
    /** 返回 (应用列表, 用户 id 列表)；无 root 时以 failure 返回。 */
    suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>>
    /** 重新从内核读取已有条目的 profile，不重新枚举包，适合下拉刷新。 */
    suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>>
}
