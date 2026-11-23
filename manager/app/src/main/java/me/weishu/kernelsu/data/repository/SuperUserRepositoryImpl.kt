package me.weishu.kernelsu.data.repository

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.IKsuInterface
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_UID
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.KsuService
import me.weishu.kernelsu.ui.util.KsuCli
import kotlin.coroutines.resume

/**
 * 通过 libsu 绑定 [KsuService] 读取应用列表。
 * 注意：绑定与解绑必须在主线程；包枚举和 profile 查询在 IO 线程，且需要 root。
 */
class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        /** 日志标签。 */
        private const val TAG = "SuperUserRepository"
    }

    /**
     * 枚举所有带代码的应用并读取各自的 profile，同时把 WebView Zygote 作为特殊条目追加。
     * 服务可能在查询过程中被系统回收，因此每个 AIDL 调用都有一次重连重试。
     */
    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!KsuCli.SHELL.isRoot) {
                return@withContext Result.failure(
                    IllegalStateException("Root access is required")
                )
            }

            val result = connectKsuService {
                Log.w(TAG, "KsuService disconnected")
            }

            // 当前 binder 与其连接对象；重连时会一起替换。
            var currentBinder = result.first
            var currentConnection = result.second

            try {
                /** 解绑旧连接并重新绑定，返回新的 AIDL 接口。 */
                suspend fun reconnect(): IKsuInterface {
                    withContext(Dispatchers.Main) {
                        RootService.unbind(currentConnection)
                    }
                    val retry = connectKsuService { Log.w(TAG, "KsuService disconnected") }
                    currentBinder = retry.first
                    currentConnection = retry.second
                    return IKsuInterface.Stub.asInterface(currentBinder)
                }

                val pm = ksuApp.packageManager
                val start = SystemClock.elapsedRealtime()

                var iface = IKsuInterface.Stub.asInterface(currentBinder)
                // 用户 id 列表用于多用户过滤；失败时重连一次。
                val idsArray = try {
                    iface.userIds
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.userIds
                }

                val slice = try {
                    iface.getPackages(0)
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.getPackages(0)
                }

                val packages = slice.list
                val newApps = packages.filter {
                    val ai = it.applicationInfo ?: return@filter false
                    // WebView Zygote 没有独立包，稍后单独追加；无代码的包（资源包）也不展示。
                    ai.uid != WEBVIEW_ZYGOTE_UID &&
                            (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                }.map {
                    val appInfo = it.applicationInfo!!
                    // profile 由内核按包名 + UID 返回；null 表示读取失败。
                    val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = it,
                        profile = profile,
                    )
                }.toMutableList()

                // WebView Zygote is a single system UID, not a per-user package. Reuse the system icon.
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
                Pair(newApps, idsArray.toList())
            } finally {
                // 无论成功失败都要解绑，避免泄漏 root 服务连接。
                withContext(Dispatchers.Main) {
                    RootService.unbind(currentConnection)
                }
            }
        }
    }

    /** 只刷新已有条目的 profile（不重新枚举包），用于下拉刷新等轻量场景。 */
    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }

    /**
     * 绑定 [KsuService] 并等待连接建立；连接断开时回调 [onDisconnect]。
     * 必须在主线程调用；取消协程会解绑（非主线程时 post 到主线程）。
     */
    private suspend inline fun connectKsuService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                /** 连接断开回调；实际重连由调用方的重试逻辑负责。 */
                override fun onServiceDisconnected(name: ComponentName?) {
                    onDisconnect()
                }

                /** 连接建立后把 binder 交给协程；已取消则忽略。 */
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive) {
                        cont.resume(binder as IBinder to this)
                    }
                }
            }

            // 协程取消时释放连接；RootService.unbind 只能在主线程执行。
            cont.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    RootService.unbind(connection)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        RootService.unbind(connection)
                    }
                }
            }

            val intent = Intent(ksuApp, KsuService::class.java)

            val task = RootService.bindOrTask(
                intent,
                Shell.EXECUTOR,
                connection,
            )
            // bindOrTask 返回任务时，需要 root shell 异步执行绑定请求。
            val shell = KsuCli.SHELL
            task?.let { shell.execTask(it) }
        }
    }
}
