package me.weishu.kernelsu.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import me.weishu.kernelsu.IKsuInterface
import rikka.parcelablelist.ParcelableListSlice

/**
 * @author weishu
 * @date 2023/4/18.
 */

/**
 * 以 root 身份运行的辅助服务，供 Manager 进程通过 Binder 跨进程查询所有用户下的应用列表。
 *
 * 继承 [RootService]，其生命周期由 libsu 管理：只有绑定成功后才处于 root 上下文，
 * 因此这里不持有任何长期状态，所有数据都按调用即时读取。
 */
class KsuService : RootService() {

    companion object {
        // 日志标签，反射失败等异常都以此过滤
        private const val TAG = "KsuService"
    }

    /**
     * 返回 Binder 桩实现。每次绑定都会新建 Stub，避免不同客户端共享可变状态。
     */
    override fun onBind(intent: Intent): IBinder {
        return Stub()
    }

    /**
     * 通过反射获取所有存活用户（工作资料、多开等）的 userId。
     *
     * getAliveUsers 在部分系统版本上不可见或不可用，失败时降级为仅返回主用户 0，
     * 保证调用方至少能拿到可用结果而不是抛异常。
     */
    private fun getAllUserIds(): IntArray {
        val um = getSystemService(USER_SERVICE) as UserManager
        // getAliveUsers() was added in API 31
        try {
            val method = um.javaClass.getMethod("getAliveUsers")
            val users = method.invoke(um) as List<*>
            return extractUserIds(users)
        } catch (e: Exception) {
            Log.e(TAG, "getAliveUsers reflection failed", e)
        }

        return intArrayOf(0)
    }

    /**
     * 从反射得到的 UserInfo 对象列表中提取 id 字段。
     *
     * 由于这里不直接依赖隐藏 API，字段读取同样可能因 ROM 差异失败，此时回退到 [0]。
     */
    private fun extractUserIds(users: List<*>?): IntArray {
        if (users.isNullOrEmpty()) return intArrayOf(0)

        return try {
            users.map { user ->
                user!!.javaClass.getField("id").getInt(user)
            }.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ID from UserInfo", e)
            intArrayOf(0)
        }
    }

    /**
     * 汇总所有用户的已安装应用，逐个用户调用隐藏 API 后拼接成一个列表。
     *
     * 结果可能包含同一包名在不同用户下的重复条目，调用方需自行按 uid/包名去重。
     */
    private fun getInstalledPackagesAll(flags: Int): ArrayList<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        for (userId in getAllUserIds()) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            packages.addAll(getInstalledPackagesAsUser(flags, userId))
        }
        return packages
    }

    /**
     * 调用 PackageManager 的隐藏方法 getInstalledPackagesAsUser 获取指定用户的应用。
     *
     * 该方法在公开 API 中不可用，只能用反射；任何失败都会记录日志并返回空列表，
     * 以免单个用户查询失败导致整体结果不可用。
     */
    @Suppress("UNCHECKED_CAST")
    private fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> {
        return try {
            val pm: PackageManager = packageManager
            val method = pm.javaClass.getDeclaredMethod(
                "getInstalledPackagesAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, flags, userId) as List<PackageInfo>
        } catch (e: Throwable) {
            Log.e(TAG, "err", e)
            ArrayList()
        }
    }

    /**
     * AIDL 接口的实现，运行在本服务的 root 进程中，因此能访问全用户范围的数据。
     */
    private inner class Stub : IKsuInterface.Stub() {
        /**
         * 返回所有用户的应用列表。
         *
         * ParcelableListSlice 会在 Binder 事务中按需分片传输，避免一次性超过事务大小限制。
         */
        override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
            val list = getInstalledPackagesAll(flags)
            Log.i(TAG, "getPackages: ${list.size}")
            return ParcelableListSlice(list)
        }

        /**
         * 返回当前存活用户 id 数组，供上层按用户维度展示应用。
         */
        override fun getUserIds(): IntArray {
            val ids = getAllUserIds()
            Log.i(TAG, "getUserIds: ${ids.contentToString()}")
            return ids
        }
    }
}
