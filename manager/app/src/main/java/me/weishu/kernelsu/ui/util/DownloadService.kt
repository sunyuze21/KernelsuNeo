package me.weishu.kernelsu.ui.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.registry.DownloadVerificationException
import me.weishu.kernelsu.data.registry.ExpectedFile
import me.weishu.kernelsu.data.registry.RegistryDownloadStore
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.MainActivity
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/** 下载完成后通知栏主操作的语义：安装模块或直接打开文件。 */
enum class DownloadCompletionAction {
    INSTALL_MODULE,
    OPEN_FILE,
}

/**
 * 解析下载文件的 MIME 类型。
 *
 * 优先使用调用方给出的值；否则按扩展名猜测；都无法确定时回退到
 * `application/octet-stream`，避免 MediaStore 拒绝或打开失败。
 */
internal fun resolveDownloadMimeType(fileName: String, providedMimeType: String?): String =
    providedMimeType?.trim()?.takeIf { it.isNotEmpty() }
        ?: URLConnection.guessContentTypeFromName(fileName)
        ?: "application/octet-stream"

/**
 * 前台下载服务。
 *
 * 普通下载写入 MediaStore 的 Downloads 目录；带校验信息的仓库下载则写入
 * 应用私有目录并在落盘过程中校验大小与 SHA-256。Service 负责通知、取消、
 * 删除临时文件等生命周期，避免进程被回收后下载状态丢失。
 */
class DownloadService : Service() {

    // 服务常量：Action/Extra 键名一旦发布就不能随意修改，通知点击依赖它们。
    companion object {
        // 通知渠道与 Action/Extra 名称：跨进程/通知点击都依赖这些稳定字符串。
        const val CHANNEL_ID = "download_channel"
        const val ACTION_DOWNLOAD = "me.weishu.kernelsu.action.DOWNLOAD"
        const val ACTION_CANCEL = "me.weishu.kernelsu.action.CANCEL_DOWNLOAD"
        const val ACTION_DISMISS_DOWNLOAD = "me.weishu.kernelsu.action.DISMISS_DOWNLOAD"
        const val ACTION_INSTALL_MODULE = "me.weishu.kernelsu.action.INSTALL_MODULE"
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_DOWNLOAD_ID = "downloadId"
        const val EXTRA_MODULE_URI = "moduleUri"
        const val EXTRA_EXPECTED_SIZE = "expectedSize"
        const val EXTRA_EXPECTED_SHA256 = "expectedSha256"
        const val EXTRA_TARGET_PATH = "targetPath"
        const val EXTRA_MIME_TYPE = "mimeType"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_USER_AGENT = "userAgent"
        const val EXTRA_COMPLETION_ACTION = "completionAction"
        const val EXTRA_DELETE_FILE_ON_DISMISS = "deleteFileOnDismiss"

        // 完成通知使用单独的 id 段，避免覆盖进行中的进度通知。
        private const val COMPLETION_NOTIFICATION_ID_BASE = 100000
        // 私有目录里的仓库下载超过 24 小时即清理，防止残留占空间。
        private const val REGISTRY_DOWNLOAD_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }

    // 按下载 id 记录协程任务，取消时可直接 cancel。
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    // 记录 OkHttp Call，取消时能中断阻塞中的网络请求。
    private val activeCalls = ConcurrentHashMap<Int, Call>()
    // Service 销毁时统一取消所有子协程。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    // 下载服务不提供绑定，仅通过 startForegroundService/Intent 驱动。
    override fun onBind(intent: Intent?): IBinder? = null

    // 初始化通知管理器并创建下载通知渠道。
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    // 按 Action 分发：开始下载、取消、关闭完成通知。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                // downloadId 同时用于状态流索引和前台通知 id，缺失则无法继续。
                val downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1)
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
                val cookie = intent.getStringExtra(EXTRA_COOKIE)
                val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
                val completionAction = intent.getStringExtra(EXTRA_COMPLETION_ACTION)
                    ?.let { runCatching { DownloadCompletionAction.valueOf(it) }.getOrNull() }
                    ?: DownloadCompletionAction.INSTALL_MODULE
                if (downloadId == -1) return START_NOT_STICKY

                val notification = buildProgressNotification(downloadId, fileName, 0)
                // Android 14 起必须声明前台服务类型，否则启动会抛异常。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        downloadId, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(downloadId, notification)
                }

                val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)
                val expectedSize = intent.getLongExtra(EXTRA_EXPECTED_SIZE, -1L)
                val expected = expectedSha256?.let { ExpectedFile(expectedSize, it) }
                startDownload(downloadId, url, fileName, mimeType, cookie, userAgent, completionAction, expected)
            }

            ACTION_CANCEL -> {
                val downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1) {
                    activeJobs[downloadId]?.cancel()
                    activeCalls[downloadId]?.cancel()
                }
            }

            ACTION_DISMISS_DOWNLOAD -> {
                val downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, -1)
                val uri = intent.getStringExtra(EXTRA_MODULE_URI)?.let(Uri::parse)
                // 模块安装类下载关闭通知时删除临时文件；普通下载保留给用户。
                val deleteFileOnDismiss = intent.getBooleanExtra(EXTRA_DELETE_FILE_ON_DISMISS, true)
                if (downloadId != -1) {
                    notificationManager.cancel(COMPLETION_NOTIFICATION_ID_BASE + downloadId)
                }
                serviceScope.launch {
                    if (deleteFileOnDismiss && uri != null) deleteDownload(uri)
                    stopForegroundIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Registry downloads ([expected] != null) may only come from GitHub Release download URLs
     * and their CDN redirect targets; every hop is checked.
     *
     * 说明：关闭 OkHttp 自动重定向，改由拦截器逐跳校验主机名，防止被
     * 重定向到任意地址；任何不合规跳转都会抛出校验异常。
     */
    private fun registryClient(): OkHttpClient = ksuApp.okhttpClient.newBuilder()
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val requestUrl = chain.request().url
            if (!RegistryUrls.isAllowedDownloadHop(requestUrl.toString())) {
                throw DownloadVerificationException(
                    DownloadVerificationException.Reason.URL_NOT_ALLOWED,
                    "Download host not allowed: ${requestUrl.host}"
                )
            }
            chain.proceed(chain.request())
        }
        .build()

    /**
     * 启动实际下载协程。
     *
     * 普通下载边读边写入 MediaStore（IS_PENDING 保证对其他应用不可见，完成后再发布）；
     * 仓库下载委托 [downloadVerified]。取消或失败时删除半成品。
     */
    private fun startDownload(
        id: Int,
        url: String,
        fileName: String,
        mimeType: String?,
        cookie: String?,
        userAgent: String?,
        completionAction: DownloadCompletionAction,
        expected: ExpectedFile?,
    ) {
        // 惰性启动，便于先登记 activeJobs 再真正执行。
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            // 记录尚未成功发布的 URI，finally 中负责清理。
            var pendingUri: Uri? = null
            var displayName = fileName
            try {
                val uri = if (expected != null) {
                    // Already stored under its final name: delete it if cancelled before completion.
                    downloadVerified(id, url, fileName, expected).also { pendingUri = it }
                } else {
                    val call = ksuApp.okhttpClient.newCall(Request.Builder().url(url).apply {
                        cookie?.takeIf { it.isNotEmpty() }?.let { addHeader("Cookie", it) }
                        userAgent?.takeIf { it.isNotEmpty() }?.let { addHeader("User-Agent", it) }
                    }.build())
                    activeCalls[id] = call
                    ensureActive()
                    val published = call.execute()
                        .use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body
                        val total = body.contentLength()

                        ensureActive()
                        // IS_PENDING=1 期间其他应用看不到该条目，避免读到半截文件。
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, resolveDownloadMimeType(fileName, mimeType))
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        // 固定写入主外部存储的 Downloads 目录。
                        val downloadUri = contentResolver.insert(
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            values
                        ) ?: throw IOException("Failed to create download")
                        pendingUri = downloadUri
                        displayName = downloadUri.getFileName(this@DownloadService) ?: fileName

                        val output = contentResolver.openOutputStream(downloadUri)
                            ?: throw IOException("Failed to open download")
                        output.use { fos ->
                            // 8 KiB 缓冲兼顾内存与 IO 次数。
                            val buf = ByteArray(8 * 1024)
                            var soFar = 0L
                            val progress = ProgressReporter(id, displayName, total)
                            val source = body.byteStream()

                            while (true) {
                                ensureActive()
                                val read = source.read(buf)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                soFar += read
                                progress.report(soFar)
                            }
                            fos.flush()
                        }
                        downloadUri
                    }

                    ensureActive()
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    if (contentResolver.update(published, values, null, null) != 1) {
                        throw IOException("Failed to publish download")
                    }
                    displayName = published.getFileName(this@DownloadService) ?: displayName
                    published
                }
                ensureActive()
                pendingUri = null
                DownloadManager.markCompleted(id, uri)

                notificationManager.cancel(id)
                notificationManager.notify(
                    COMPLETION_NOTIFICATION_ID_BASE + id,
                    buildCompletionNotification(id, displayName, uri, mimeType, completionAction)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                // 区分安全校验失败与普通 IO 失败，便于通知栏给出不同文案。
                val verificationFailure = (e as? DownloadVerificationException)?.reason
                if (verificationFailure != null) {
                    Log.w("DownloadService", "Rejected download $url: ${e.message}")
                }
                DownloadManager.markFailed(id, e.message ?: "Unknown error", verificationFailure)

                notificationManager.cancel(id)
                notificationManager.notify(
                    COMPLETION_NOTIFICATION_ID_BASE + id,
                    buildFailureNotification(displayName, verificationFailure)
                )
            } finally {
                pendingUri?.let { deleteDownload(it) }
            }
        }
        activeJobs[id] = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException &&
                DownloadManager.downloads.value[id]?.status != DownloadManager.Status.COMPLETED
            ) {
                DownloadManager.markFailed(id, "Cancelled")
                notificationManager.cancel(id)
            }
            activeCalls.remove(id)?.cancel()
            activeJobs.remove(id)
            stopForegroundIfIdle()
        }
        job.start()
    }

    // 节流进度通知：每变化 2% 或到达 100% 才更新通知栏。
    private inner class ProgressReporter(private val id: Int, private val displayName: String, private val total: Long) {
        private var lastNotifiedProgress = -1

        // 上报已写入字节数；total 未知（<=0）时不计算百分比。
        fun report(soFar: Long) {
            if (total <= 0) return
            val percent = ((soFar * 100L) / total).toInt().coerceIn(0, 100)
            DownloadManager.updateProgress(id, percent)
            if (percent - lastNotifiedProgress >= 2 || percent == 100) {
                notificationManager.notify(id, buildProgressNotification(id, displayName, percent))
                lastNotifiedProgress = percent
            }
        }
    }

    // 仓库下载落在应用私有目录，其他应用无法直接篡改。
    private fun registryStoreRoot(): File = File(filesDir, RegistryDownloadStore.DIR_NAME)

    /**
     * Registry release ZIP: only GitHub Release URLs (and their CDN redirect targets), streamed
     * into app-private storage while size + SHA-256 are checked. A mismatching file is deleted
     * and never published; the flash step re-verifies the stored bytes before installing.
     *
     * 说明：校验失败的文件绝不保留，安装前还会再次核对，形成双重校验。
     */
    private fun CoroutineScope.downloadVerified(id: Int, url: String, fileName: String, expected: ExpectedFile): Uri {
        if (!RegistryUrls.isAllowedReleaseDownloadUrl(url)) {
            throw DownloadVerificationException(
                DownloadVerificationException.Reason.URL_NOT_ALLOWED,
                "Download URL not allowed"
            )
        }
        val root = registryStoreRoot()
        runCatching { RegistryDownloadStore.cleanup(root, REGISTRY_DOWNLOAD_MAX_AGE_MS) }
        val call = registryClient().newCall(Request.Builder().url(url).build())
        activeCalls[id] = call
        ensureActive()
        val file = call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val progress = ProgressReporter(id, fileName, expected.size)
            RegistryDownloadStore.download(
                input = resp.body.byteStream(),
                root = root,
                expected = expected,
                fileName = fileName,
                onProgress = progress::report,
                checkActive = { ensureActive() },
            )
        }
        return Uri.fromFile(file)
    }

    // 删除下载产物：私有目录文件走 RegistryDownloadStore，MediaStore 条目走 ContentResolver。
    private fun deleteDownload(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { RegistryDownloadStore.delete(registryStoreRoot(), File(it)) }
                return@runCatching
            }
            contentResolver.delete(uri, null, null)
        }.onFailure {
            Log.w("DownloadService", "Failed to delete download: $uri", it)
        }
    }

    // 构建进行中通知；progress == 0 时显示不确定进度条。
    private fun buildProgressNotification(
        id: Int,
        fileName: String,
        progress: Int
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.download_progress_title, fileName))
        .setContentText("$progress%")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setProgress(100, progress, progress == 0)
        .setOngoing(true)
        .setSilent(true)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.download_cancel),
            createCancelPendingIntent(id)
        )
        .build()

    /**
     * 构建完成通知。
     *
     * 模块安装类通过 PendingIntent 把 URI 与一次性 token 交给 MainActivity；
     * 普通下载则用 ACTION_VIEW + 读权限打开。PendingIntent 必须 immutable。
     */
    private fun buildCompletionNotification(
        id: Int,
        fileName: String,
        uri: Uri,
        mimeType: String?,
        completionAction: DownloadCompletionAction,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_complete_title))
            .setContentText(getString(R.string.download_complete_content, fileName))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)

        val primaryPendingIntent = when (completionAction) {
            DownloadCompletionAction.INSTALL_MODULE -> PendingIntent.getActivity(
                this, id, Intent(this, MainActivity::class.java).apply {
                    action = ACTION_INSTALL_MODULE
                    putExtra(EXTRA_MODULE_URI, uri.toString())
                    putExtra(EXTRA_DOWNLOAD_ID, id)
                    // 一次性 token 防止其他应用伪造 Intent 触发模块安装。
                    putExtra(EXTRA_TOKEN, SettingsRepositoryImpl().intentToken)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { builder.addAction(android.R.drawable.ic_menu_save, getString(R.string.download_install), it) }
            DownloadCompletionAction.OPEN_FILE -> PendingIntent.getActivity(
                this, id, Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, resolveDownloadMimeType(fileName, mimeType))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }, getString(R.string.open)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).also { builder.addAction(android.R.drawable.ic_menu_view, getString(R.string.open), it) }
        }
        builder.setContentIntent(primaryPendingIntent)

        // Add "Cancel" action button
        val dismissIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_DISMISS_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, id)
            putExtra(EXTRA_MODULE_URI, uri.toString())
            putExtra(EXTRA_DELETE_FILE_ON_DISMISS, completionAction == DownloadCompletionAction.INSTALL_MODULE)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            COMPLETION_NOTIFICATION_ID_BASE + id,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.download_cancel),
            dismissPendingIntent
        )

        return builder.build()
    }

    // 失败通知：按校验失败原因给出对应文案。
    private fun buildFailureNotification(
        fileName: String,
        verificationFailure: DownloadVerificationException.Reason? = null,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.download_failed_title))
        .setContentText(
            when (verificationFailure) {
                null -> getString(R.string.download_failed_content, fileName)
                DownloadVerificationException.Reason.URL_NOT_ALLOWED -> getString(R.string.module_repo_download_url_blocked)
                DownloadVerificationException.Reason.SIZE_MISMATCH,
                DownloadVerificationException.Reason.SHA256_MISMATCH ->
                    getString(R.string.module_repo_download_verify_failed, fileName)
            }
        )
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setAutoCancel(true)
        .build()

    // 创建“取消下载”按钮的 PendingIntent；显式指向本服务，避免被外部接走。
    private fun createCancelPendingIntent(downloadId: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getService(
            this,
            downloadId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // IMPORTANCE_LOW 保证下载通知不发声、不弹窗，只在下拉栏展示进度。
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    // 没有活跃任务时退出前台并结束服务，避免常驻耗电。
    private fun stopForegroundIfIdle() {
        if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // 销毁时取消协程与网络请求，防止回调访问已释放的通知管理器。
    override fun onDestroy() {
        serviceScope.cancel()
        activeCalls.values.forEach { it.cancel() }
        super.onDestroy()
    }
}
