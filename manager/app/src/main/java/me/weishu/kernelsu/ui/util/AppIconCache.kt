package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Process
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.AppIconCache.getMainUserId
import me.zhanghai.android.appiconloader.AppIconLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap

/**
 * Remap the uid to the non-profile user possible to avoid SecurityException
 * when loading icons for cross-profileGroup apps.
 * See: https://github.com/zhanghai/AppIconLoader/issues/3
 *
 * 说明：跨工作资料（profileGroup）读取图标会被系统拒绝，这里把 uid 重映射到
 * 主用户下的同一 appId，仅用于图标加载，不改变应用本身的身份信息。
 */
fun ApplicationInfo.withMainUserUid(context: Context): ApplicationInfo {
    val mainUserId = getMainUserId(context)
    val appId = this.uid % 100000
    val targetUid = mainUserId * 100000 + appId

    if (this.uid == targetUid) return this
    return ApplicationInfo(this).apply { uid = targetUid }
}

/**
 * 应用图标的内存缓存。
 *
 * 图标解码开销大，列表/下拉框会频繁复用；这里用 LRU 缓存限制内存占用，
 * 并用信号量限制并发解码数量，避免滚动时一次性创建过多 Bitmap。
 */
object AppIconCache {
    private const val TAG = "AppIconCache"
    // 以 KB 为单位计算缓存容量。
    private val maxMemory = Runtime.getRuntime().maxMemory() / 1024
    // 最多占用堆上限的 1/8，防止图标缓存把应用推到 OOM。
    private val cacheSize = (maxMemory / 8).toInt()
    // 最多同时解码 4 个图标，减轻 CPU/GPU 压力。
    private val loadSemaphore = Semaphore(4)
    // 每个尺寸复用一个 AppIconLoader，避免重复构造带来的额外开销。
    private val iconLoaders = ConcurrentHashMap<Int, AppIconLoader>()

    private val lruCache = object : LruCache<String, Bitmap>(cacheSize) {
        /**
         * 缓存大小按字节数计算；LruCache 默认按条目数，会严重低估 Bitmap 内存。
         */
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount / 1024
        }
    }

    // 记录已确认属于“其他工作资料”的 userId，后续直接走主用户重映射，省去一次异常。
    @Volatile
    private var otherProfileGroupUserIds = setOf<Int>()

    // Android uid 的高位是 userId，低位是 appId。
    private fun userId(uid: Int): Int = uid / 100000

    // 判断该 uid 是否属于此前触发过跨资料访问异常的用户。
    private fun isOtherProfileGroupUser(uid: Int): Boolean {
        return otherProfileGroupUserIds.contains(userId(uid))
    }

    // 记录跨资料用户；使用写时复制 + synchronized 保证并发读取安全。
    private fun markOtherProfileGroupUser(uid: Int) {
        val id = userId(uid)
        if (!otherProfileGroupUserIds.contains(id)) {
            synchronized(this) {
                otherProfileGroupUserIds = otherProfileGroupUserIds + id
            }
        }
    }

    // 主用户 id 只解析一次；双重检查锁避免多线程重复查询 UserManager。
    @Volatile
    private var cachedMainUserId: Int? = null

    /**
     * 解析当前设备的主用户 id。
     *
     * 主用户是没有角标（hasBadge == false）的那个用户；hasBadge 属于隐藏 API，
     * 需要 HiddenApiBypass 豁免，失败时回退到当前进程 uid 对应的用户。
     */
    internal fun getMainUserId(context: Context): Int {
        cachedMainUserId?.let { return it }

        synchronized(this) {
            cachedMainUserId?.let { return it }

            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            // 遍历所有用户资料，找出没有角标的主用户。
            val profiles = um.userProfiles
            var foundMainUserId = userId(Process.myUid())
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/os/UserManager;->hasBadge(I)Z")
                val hasBadgeMethod = UserManager::class.java.getMethod("hasBadge", Int::class.javaPrimitiveType)
                for (profile in profiles) {
                    val id = profile.hashCode()
                    val hasBadge = hasBadgeMethod.invoke(um, id) as Boolean
                    if (!hasBadge) {
                        foundMainUserId = id
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve main user id via hasBadge", e)
            }
            cachedMainUserId = foundMainUserId
            return foundMainUserId
        }
    }

    // 缓存 key 带上 sourceDir，避免应用更新（同一包名/uid）后复用旧图标。
    private fun buildKey(applicationInfo: ApplicationInfo, size: Int): String {
        return "${applicationInfo.packageName}:${applicationInfo.uid}:${applicationInfo.sourceDir}:${size}"
    }

    // 只读缓存查询：不触发加载，供 UI 同步渲染时快速判断。
    fun getCached(applicationInfo: ApplicationInfo, size: Int): Bitmap? {
        val key = buildKey(applicationInfo, size)
        return lruCache.get(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Load icon bitmap using AppIconLoader with cross-profile fallback.
     * Called on IO thread, returns a software bitmap.
     *
     * 说明：必须在 IO 线程调用。跨资料用户首次会抛 SecurityException，
     * 捕获后记录该用户并改用主用户 uid 重试。
     */
    private fun loadIconBitmap(context: Context, applicationInfo: ApplicationInfo, size: Int): Bitmap {
        val loader = iconLoaders.getOrPut(size) {
            AppIconLoader(size, false, context.applicationContext)
        }

        return if (isOtherProfileGroupUser(applicationInfo.uid)) {
            loader.loadIcon(applicationInfo.withMainUserUid(context))
        } else {
            try {
                loader.loadIcon(applicationInfo)
            } catch (_: SecurityException) {
                Log.d(TAG, "SecurityException loading icon for userId ${userId(applicationInfo.uid)}, retrying with main user uid")
                markOtherProfileGroupUser(applicationInfo.uid)
                loader.loadIcon(applicationInfo.withMainUserUid(context))
            }
        }
    }

    /**
     * Synchronous icon loading for non-UI callers (e.g. WebView shouldInterceptRequest).
     * Returns a software bitmap suitable for compress/encode. Does NOT copy to HARDWARE config.
     *
     * 说明：返回软件 Bitmap，便于 WebView 拦截请求时压缩成 PNG；不能返回
     * HARDWARE 配置，否则 compress 会失败。
     */
    fun loadIconSync(context: Context, applicationInfo: ApplicationInfo, size: Int): Bitmap {
        val key = buildKey(applicationInfo, size)

        // 命中缓存且未被回收才可复用。
        lruCache.get(key)?.let {
            if (!it.isRecycled) return it
            lruCache.remove(key)
        }

        val bitmap = loadIconBitmap(context, applicationInfo, size)
        bitmap.prepareToDraw()
        lruCache.put(key, bitmap)
        return bitmap
    }

    /**
     * 协程版图标加载：带并发限流，并把结果尽量转成 HARDWARE Bitmap 以降低绘制内存。
     *
     * 调用方应处于主线程或任意协程上下文，内部会切到 IO 线程解码。
     */
    suspend fun loadIcon(context: Context, applicationInfo: ApplicationInfo, size: Int): Bitmap {
        val key = buildKey(applicationInfo, size)

        // 先做无锁快速路径，避免无谓地等待信号量。
        lruCache.get(key)?.let {
            if (!it.isRecycled) return it
            lruCache.remove(key)
        }

        return loadSemaphore.withPermit {
            // 拿到许可后再次检查，可能已被其他协程填充。
            lruCache.get(key)?.let {
                if (!it.isRecycled) return@withPermit it
                lruCache.remove(key)
            }

            withContext(Dispatchers.IO) {
                val bitmap = loadIconBitmap(context, applicationInfo, size)

                // HARDWARE Bitmap 只能用于绘制；转换失败则保留软件 Bitmap。
                val gpuBitmap = try {
                    bitmap.copy(Bitmap.Config.HARDWARE, false)?.also {
                        bitmap.recycle()
                    } ?: bitmap.also { it.prepareToDraw() }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to copy bitmap to HARDWARE config", e)
                    bitmap.also { it.prepareToDraw() }
                }

                lruCache.put(key, gpuBitmap)

                gpuBitmap
            }
        }
    }
}
