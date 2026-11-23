package me.weishu.kernelsu.ui.webui

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Window
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.internal.UiThreadHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.DownloadManager
import me.weishu.kernelsu.ui.util.DownloadService
import me.weishu.kernelsu.ui.util.createRootShell
import me.weishu.kernelsu.ui.util.listModules
import me.weishu.kernelsu.ui.util.resolveDownloadMimeType
import me.weishu.kernelsu.ui.util.withNewRootShell
import me.weishu.kernelsu.ui.webui.file.KsuIO
import me.weishu.kernelsu.ui.webui.pm.KsuPm
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * 注入为 `ksu` 的 WebUI 主桥接对象。
 *
 * 提供的 shell/spawn 能力等价于 root 命令执行，因此只应注入到模块本地
 * webroot 页面；命令与参数都直接来自 JS，没有额外的沙箱隔离。
 */
class WebViewInterface(private val state: WebUIState) {
    private val webView get() = state.webView
    private val modDir get() = state.modDir

    /** 同步执行 shell 命令并返回 stdout；在全局挂载命名空间中运行。 */
    @JavascriptInterface
    fun exec(cmd: String): String {
        return withNewRootShell(true) { ShellUtils.fastCmd(this, cmd) }
    }

    /** 异步执行命令，完成后调用 JS 回调函数。 */
    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    /**
     * 把 options 中的 cwd/env 转成 shell 前缀。
     *
     * 直接字符串拼接，不做转义：这些参数本身来自受信任的模块 WebUI。
     */
    private fun processOptions(sb: StringBuilder, options: String?) {
        val opts = if (options == null) JSONObject() else JSONObject(options)

        val cwd = opts.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) {
            sb.append("cd ${cwd};")
        }

        opts.optJSONObject("env")?.let { env ->
            env.keys().forEach { key ->
                sb.append("export ${key}=${env.getString(key)};")
            }
        }
    }

    /**
     * 执行命令并通过 javascript: URL 回调结果。
     *
     * 回调在主线程 post，stdout/stderr 用 JSONObject.quote 转义后嵌入 JS，
     * 避免输出中的引号破坏脚本。
     */
    @JavascriptInterface
    fun exec(
        cmd: String,
        options: String?,
        callbackFunc: String
    ) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)
        finalCommand.append(cmd)

        val result = withNewRootShell(true) {
            newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
        }
        val stdout = result.out.joinToString(separator = "\n")
        val stderr = result.err.joinToString(separator = "\n")

        val jsCode =
            "javascript: (function() { try { ${callbackFunc}(${result.code}, ${
                JSONObject.quote(
                    stdout
                )
            }, ${JSONObject.quote(stderr)}); } catch(e) { console.error(e); } })();"
        webView?.post {
            webView?.loadUrl(jsCode)
        }
    }

    /**
     * 以流式方式执行命令，逐行 emit stdout/stderr，结束时 emit exit/error。
     *
     * 适用于长时间运行的脚本；使用独立 root shell，结束后关闭。
     */
    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()

        processOptions(finalCommand, options)

        if (args.isNotEmpty()) {
            finalCommand.append(command).append(" ")
            val argsArray = JSONArray(args)
            for (i in 0 until argsArray.length()) {
                finalCommand.append(argsArray.getString(i)).append(" ")
            }
        } else {
            finalCommand.append(command)
        }

        // 每次 spawn 使用独立 shell，便于结束后立即关闭。
        val shell = createRootShell(true)

        // 把一行输出作为事件发送给 JS 回调对象。
        val emitData = fun(name: String, data: String) {
            val jsCode =
                "javascript: (function() { try { ${callbackFunc}.${name}.emit('data', ${
                    JSONObject.quote(
                        data
                    )
                }); } catch(e) { console.error('emitData', e); } })();"
            webView?.post {
                webView?.loadUrl(jsCode)
            }
        }

        val stdout = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            // 每行 stdout 立即 emit，实现流式输出。
            override fun onAddElement(s: String) {
                emitData("stdout", s)
            }
        }

        val stderr = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            // 每行 stderr 通过独立事件通道发送。
            override fun onAddElement(s: String) {
                emitData("stderr", s)
            }
        }

        val future = shell.newJob().add(finalCommand.toString()).to(stdout, stderr).enqueue()
        val completableFuture = CompletableFuture.supplyAsync {
            future.get()
        }

        completableFuture.thenAccept { result ->
            val emitExitCode =
                $$"javascript: (function() { try { $${callbackFunc}.emit('exit', $${result.code}); } catch(e) { console.error(`emitExit error: ${e}`); } })();"
            webView?.post {
                webView?.loadUrl(emitExitCode)
            }

            if (result.code != 0) {
                val emitErrCode =
                    "javascript: (function() { try { var err = new Error(); err.exitCode = ${result.code}; err.message = ${
                        JSONObject.quote(
                            result.err.joinToString(
                                "\n"
                            )
                        )
                    };${callbackFunc}.emit('error', err); } catch(e) { console.error('emitErr', e); } })();"
                webView?.post {
                    webView?.loadUrl(emitErrCode)
                }
            }
        }.whenComplete { _, _ ->
            runCatching { shell.close() }
        }
    }

    /** 在宿主 Activity 上显示短 Toast。 */
    @JavascriptInterface
    fun toast(msg: String) {
        webView?.post {
            webView?.let { Toast.makeText(it.context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 切换全屏：隐藏/显示系统栏，并同步 insets 模式。 */
    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val context = webView?.context
        if (context is Activity) {
            Handler(Looper.getMainLooper()).post {
                if (enable) {
                    hideSystemUI(context.window)
                } else {
                    showSystemUI(context.window)
                }
            }
        }
        enableEdgeToEdge(enable)
    }

    /** 由网页声明是否自行处理安全区；启用后 Compose 不再额外留白。 */
    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean = true) {
        state.isInsetsEnabled = enable
    }

    /** 返回当前模块的元信息 JSON（来自 `ksud module list`）。 */
    @JavascriptInterface
    fun moduleInfo(): String {
        val moduleInfos = JSONArray(listModules())
        val currentModuleInfo = JSONObject()
        currentModuleInfo.put("moduleDir", modDir)
        val moduleId = File(modDir).name
        for (i in 0 until moduleInfos.length()) {
            val currentInfo = moduleInfos.getJSONObject(i)

            if (currentInfo.getString("id") != moduleId) {
                continue
            }

            val keys = currentInfo.keys()
            for (key in keys) {
                currentModuleInfo.put(key, currentInfo.get(key))
            }
            break
        }
        return currentModuleInfo.toString()
    }

    /**
     * 兼容旧 WebUI 的包名列表接口，新代码应使用 `ksu.pm()`。
     */
    @Deprecated("Use ksu.pm().listPackages() instead")
    @JavascriptInterface
    fun listPackages(type: String): String {
        val packageNames = webUIApps()
            .filter { appInfo ->
                if (appInfo.special) return@filter false
                val flags = appInfo.packageInfo.applicationInfo?.flags ?: 0
                when (type.lowercase()) {
                    "system" -> (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    "user" -> (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    else -> true
                }
            }
            .map { it.packageName }
            .sorted()

        val jsonArray = JSONArray()
        for (pkgName in packageNames) {
            jsonArray.put(pkgName)
        }
        return jsonArray.toString()
    }

    /**
     * 兼容旧 WebUI 的包信息查询接口，新代码应使用 `ksu.pm()`。
     */
    @Deprecated("Use ksu.pm().getPackagesInfo() instead")
    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val packageNames = JSONArray(packageNamesJson)
        val jsonArray = JSONArray()
        val appMap = webUIApps().filterNot { it.special }.associateBy { it.packageName }
        for (i in 0 until packageNames.length()) {
            val pkgName = packageNames.getString(i)
            val appInfo = appMap[pkgName]
            if (appInfo != null) {
                val pkg = appInfo.packageInfo
                val app = pkg.applicationInfo
                val obj = JSONObject()
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName ?: "")
                obj.put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
                obj.put("appLabel", appInfo.label)
                obj.put("isSystem", app?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: JSONObject.NULL)
                obj.put("uid", app?.uid ?: JSONObject.NULL)
                jsonArray.put(obj)
            } else {
                val obj = JSONObject()
                obj.put("packageName", pkgName)
                obj.put("error", "Package not found or inaccessible")
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    /** 请求关闭 WebUI 页面。 */
    @JavascriptInterface
    fun exit() {
        state.requestExit()
    }

    /** 获取文件 IO 桥接对象（对应 `ksu.io()`）。 */
    @JavascriptInterface
    fun io() = KsuIO

    /** 获取 PackageManager 桥接对象（对应 `ksu.pm()`）。 */
    @JavascriptInterface
    fun pm() = KsuPm

    /** 页面销毁时释放所有文件句柄。 */
    fun destroy() {
        KsuIO.destroy()
    }
}

/**
 * 注入为 `ksu_download` 的下载桥接对象。
 *
 * 支持两类下载：一次性 Base64 保存（save）与分块 blob 下载
 * （startChunkedDownload/writeDownloadChunk/completeChunkedDownload）。
 * 写入统一通过 MediaStore，避免申请存储权限。
 */
class WebUIDownloadInterface(private val state: WebUIState) {
    // 下载落盘在 IO 线程执行，页面销毁时统一取消。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webView get() = state.webView

    // 下载通知相关常量。
    companion object {
        // Use a different notification ID range to avoid collision with DownloadService
        // 从 200000 起编号，避开 DownloadService 的 100000 段。
        private const val WEBUI_NOTIFICATION_ID_BASE = 200000
    }

    /** 用系统浏览器打开外部链接；仅允许 http/https，防止任意 scheme 调起。 */
    @JavascriptInterface
    fun openExternal(url: String) {
        val currentWebView = webView ?: return
        val uri = url.toUri()
        if (uri.scheme != "http" && uri.scheme != "https") return

        runCatching {
            currentWebView.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { throwable ->
            Log.e("WebUIDownload", "Failed to open $url", throwable)
        }
    }

    /**
     * 保存一段 Base64 数据到系统下载目录。
     *
     * 数据一次性解码到内存，适合小文件；大文件应使用分块接口。
     */
    @JavascriptInterface
    fun save(base64: String, fileName: String?) {
        val currentWebView = webView ?: return
        val context = currentWebView.context
        // 文件名先清洗，防止路径穿越；MIME 由扩展名推断。
        val displayName = sanitizeWebUIDownloadFileName(fileName)
        val mimeType = resolveDownloadMimeType(displayName, null)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val downloadId = DownloadManager.registerLocalSave(
            fileName = displayName,
            mimeType = mimeType,
        )

        ensureNotificationChannel(notificationManager, context)
        notificationManager.notify(WEBUI_NOTIFICATION_ID_BASE + downloadId, buildProgressNotification(context, displayName, 0))

        scope.launch {
            var pendingUri: Uri? = null
            val savedUri = try {
                // DEFAULT 兼容带换行的 Base64；数据整体在内存中。
                val decoded = Base64.decode(base64, Base64.DEFAULT)
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // IS_PENDING=1 先占位，写完后再发布，避免出现半截文件。
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values)
                    ?: error("Failed to create download")
                pendingUri = uri
                var lastProgress = -1
                ByteArrayInputStream(decoded).use { input ->
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Failed to open download")
                    output.use { stream ->
                        writeWebUIDownload(input, stream) { written ->
                            val progress = if (decoded.isEmpty()) {
                                100
                            } else {
                                ((written * 100L) / decoded.size.toLong()).toInt().coerceIn(0, 100)
                            }
                            DownloadManager.updateProgress(downloadId, progress)
                            if (progress - lastProgress >= 2 || progress == 100) {
                                notificationManager.notify(
                                    WEBUI_NOTIFICATION_ID_BASE + downloadId,
                                    buildProgressNotification(context, displayName, progress)
                                )
                                lastProgress = progress
                            }
                        }
                    }
                }
                val published = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (context.contentResolver.update(uri, published, null, null) != 1) {
                    error("Failed to publish download")
                }
                uri
            } catch (throwable: Throwable) {
                pendingUri?.let { context.contentResolver.delete(it, null, null) }
                Log.e("WebUIDownload", "Failed to save $displayName", throwable)
                DownloadManager.markFailed(downloadId, throwable.message ?: "Unknown error")
                notificationManager.notify(WEBUI_NOTIFICATION_ID_BASE + downloadId, buildFailureNotification(context, displayName))
                postToast(currentWebView.context.getString(R.string.download_failed_content, displayName))
                null
            }

            if (savedUri != null) {
                DownloadManager.markCompleted(downloadId, savedUri)
                notificationManager.notify(
                    WEBUI_NOTIFICATION_ID_BASE + downloadId,
                    buildCompletionNotification(context, downloadId, displayName, savedUri, mimeType)
                )
                postToast(currentWebView.context.getString(R.string.download_complete_content, displayName))
            }
        }
    }

    /**
     * 开始分块下载，返回内部下载 id（用于后续 write/complete）。
     *
     * 临时文件放在 cacheDir，fileName 同样会做安全清洗。
     */
    @JavascriptInterface
    fun startChunkedDownload(fileName: String?, mimeType: String?): String {
        val currentWebView = webView ?: return ""
        val sanitizedFileName = sanitizeWebUIDownloadFileName(fileName)
        return runCatching {
            BlobDownloadHandler.registerBlobDownload(
                sanitizedFileName,
                mimeType,
                currentWebView.context.cacheDir,
            )
        }.getOrElse {
            Log.e("WebUIDownload", "Failed to start chunked download", it)
            ""
        }
    }

    /** 写入一个 Base64 分块；失败会清理对应会话。 */
    @JavascriptInterface
    fun writeDownloadChunk(downloadId: String, base64Chunk: String): Boolean {
        return BlobDownloadHandler.writeChunk(downloadId, base64Chunk)
    }

    /** 完成分块下载，使临时文件可被 WebView 下载拦截器读取。 */
    @JavascriptInterface
    fun completeChunkedDownload(downloadId: String): Boolean {
        return BlobDownloadHandler.completeDownload(downloadId)
    }

    /** 取消分块下载并删除临时文件。 */
    @JavascriptInterface
    fun cancelChunkedDownload(downloadId: String) {
        BlobDownloadHandler.cancelDownload(downloadId)
    }

    // 页面销毁时取消协程并清理所有分块下载临时文件。
    fun destroy() {
        scope.cancel()
        BlobDownloadHandler.cleanup()
    }

    // 在 WebView 所在线程显示 Toast。
    private fun postToast(message: String) {
        webView?.let { currentWebView ->
            currentWebView.post {
                Toast.makeText(currentWebView.context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 确保下载通知渠道已创建；复用 DownloadService 的渠道，保持设置入口一致。
    private fun ensureNotificationChannel(notificationManager: NotificationManager, context: Context) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                DownloadService.CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    // 构建 WebUI 下载的进行中通知。
    private fun buildProgressNotification(
        context: Context,
        fileName: String,
        progress: Int,
    ) = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
        .setContentTitle(context.getString(R.string.download_progress_title, fileName))
        .setContentText("$progress%")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setProgress(100, progress, progress == 0)
        .setOngoing(true)
        .setSilent(true)
        .build()

    /**
     * 构建完成通知；点击后用 ACTION_VIEW 打开刚保存的 MediaStore URI。
     *
     * 需要授予临时读权限，否则接收方应用无法读取。
     */
    private fun buildCompletionNotification(
        context: Context,
        downloadId: Int,
        fileName: String,
        contentUri: Uri,
        mimeType: String,
    ): android.app.Notification {
        val openIntent = Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            context.getString(R.string.open),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            downloadId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_complete_title))
            .setContentText(context.getString(R.string.download_complete_content, fileName))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.open), pendingIntent)
            .build()
    }

    // 构建下载失败通知。
    private fun buildFailureNotification(
        context: Context,
        fileName: String,
    ) = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
        .setContentTitle(context.getString(R.string.download_failed_title))
        .setContentText(context.getString(R.string.download_failed_content, fileName))
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setAutoCancel(true)
        .build()
}

/** 隐藏系统栏进入全屏；滑动边缘可临时唤出。 */
fun hideSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

/** 退出全屏，恢复系统栏显示。 */
fun showSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
