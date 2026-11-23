package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.weishu.kernelsu.data.registry.DownloadVerificationException
import me.weishu.kernelsu.data.registry.ExpectedFile
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.data.registry.canShareDownload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局下载状态中心：维护进行中/已完成的下载，并转发给 [DownloadService]。
 *
 * 真正的网络与落盘由前台 Service 执行；这里只保存可观察状态和完成回调，
 * 避免 Activity/Compose 重建时丢失下载进度。
 */
object DownloadManager {

    /** 下载生命周期状态。 */
    enum class Status { PENDING, DOWNLOADING, COMPLETED, FAILED }

    /** 单个下载任务的快照，供 UI 与回调读取。 */
    data class DownloadState(
        val id: Int,
        val fileName: String,
        val url: String,
        val targetPath: String? = null,
        val mimeType: String? = null,
        val completionAction: DownloadCompletionAction = DownloadCompletionAction.INSTALL_MODULE,
        val progress: Int = 0,
        val status: Status = Status.PENDING,
        val resultUri: Uri? = null,
        val error: String? = null,
        /** Set when a registry download was rejected (URL allowlist, size or SHA-256 mismatch). */
        val verificationFailure: DownloadVerificationException.Reason? = null,
        /** Size + SHA-256 a registry download is verified against; null for plain downloads. */
        val expected: ExpectedFile? = null,
    )

    // 单调递增的下载 id，也用作通知 id 的一部分。
    private val idCounter = AtomicInteger(0)
    private val _downloads = MutableStateFlow<Map<Int, DownloadState>>(emptyMap())
    // 对外只暴露只读流，防止外部直接改写状态。
    val downloads: StateFlow<Map<Int, DownloadState>> = _downloads.asStateFlow()

    // 同一个 id 可能被多次订阅，用 merge 合并回调。
    private val completionCallbacks = ConcurrentHashMap<Int, (Uri) -> Unit>()
    // 完成回调统一切到主线程，UI 可以安全更新。
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 登记一个由调用方自行写盘的“本地保存”任务（如 WebUI 生成的 blob）。
     *
     * 这类任务不经过 DownloadService，仅复用状态流与完成通知。
     */
    internal fun registerLocalSave(
        fileName: String,
        targetPath: String? = null,
        mimeType: String? = null,
        completionAction: DownloadCompletionAction = DownloadCompletionAction.OPEN_FILE,
    ): Int {
        val id = idCounter.incrementAndGet()
        val state = DownloadState(
            id = id,
            fileName = fileName,
            url = targetPath ?: fileName,
            targetPath = targetPath,
            mimeType = mimeType,
            completionAction = completionAction,
        )
        _downloads.update { it + (id to state) }
        return id
    }

    /**
     * 入队一个下载任务并启动前台 Service。
     *
     * 若已有参数完全一致且仍在进行的任务则复用它，避免重复下载；
     * 带 [expected] 的仓库下载会先做 URL 白名单校验，防止任意地址绕过校验。
     */
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        targetPath: String? = null,
        mimeType: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
        completionAction: DownloadCompletionAction = DownloadCompletionAction.INSTALL_MODULE,
        onCompleted: ((Uri) -> Unit)? = null,
        expected: ExpectedFile? = null,
    ): Int {
        // Only share an in-flight download that is verified exactly like this request.
        val existing = _downloads.value.values.find {
            it.fileName == fileName &&
                it.targetPath == targetPath &&
                it.completionAction == completionAction &&
                (it.status == Status.PENDING || it.status == Status.DOWNLOADING) &&
                canShareDownload(url, expected, it.url, it.expected)
        }
        if (existing != null) {
            if (onCompleted != null) {
                completionCallbacks.merge(existing.id, onCompleted) { a, b -> { uri -> a(uri); b(uri) } }
                // It may have finished in between; markCompleted / markFailed already drained the
                // callbacks then, so run (completed) or drop (failed) what was merged afterwards.
                val now = _downloads.value[existing.id]
                if (now?.status == Status.COMPLETED && now.resultUri != null) {
                    completionCallbacks.remove(existing.id)?.let { cb -> mainHandler.post { cb(now.resultUri) } }
                } else if (now == null || now.status == Status.FAILED) {
                    completionCallbacks.remove(existing.id)
                }
            }
            return existing.id
        }

        val id = idCounter.incrementAndGet()
        val state = DownloadState(
            id = id,
            fileName = fileName,
            url = url,
            targetPath = targetPath,
            mimeType = mimeType,
            completionAction = completionAction,
            expected = expected,
        )
        _downloads.update { it + (id to state) }

        // 仓库下载必须来自允许的 Release 地址，否则直接标记失败。
        if (expected != null && !RegistryUrls.isAllowedReleaseDownloadUrl(url)) {
            markFailed(id, "Download URL not allowed", DownloadVerificationException.Reason.URL_NOT_ALLOWED)
            return id
        }

        if (onCompleted != null) {
            completionCallbacks[id] = onCompleted
        }

        // 通过显式 Intent 启动服务，避免被外部应用劫持。
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_DOWNLOAD_ID, id)
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FILE_NAME, fileName)
            if (expected != null) {
                putExtra(DownloadService.EXTRA_EXPECTED_SIZE, expected.size)
                putExtra(DownloadService.EXTRA_EXPECTED_SHA256, expected.sha256)
            }
            putExtra(DownloadService.EXTRA_TARGET_PATH, targetPath)
            putExtra(DownloadService.EXTRA_MIME_TYPE, mimeType)
            putExtra(DownloadService.EXTRA_COOKIE, cookie)
            putExtra(DownloadService.EXTRA_USER_AGENT, userAgent)
            putExtra(DownloadService.EXTRA_COMPLETION_ACTION, completionAction.name)
        }
        ContextCompat.startForegroundService(context, intent)

        return id
    }

    // 更新进度并自动转为 DOWNLOADING；未知 id 直接忽略。
    internal fun updateProgress(id: Int, progress: Int) {
        _downloads.update { map ->
            val state = map[id] ?: return@update map
            map + (id to state.copy(progress = progress, status = Status.DOWNLOADING))
        }
    }

    // 标记完成、进度置 100，并在主线程派发一次性完成回调。
    internal fun markCompleted(id: Int, uri: Uri) {
        _downloads.update { map ->
            val state = map[id] ?: return@update map
            map + (id to state.copy(status = Status.COMPLETED, progress = 100, resultUri = uri))
        }

        val callback = completionCallbacks.remove(id)
        if (callback != null) {
            mainHandler.post { callback(uri) }
        }
    }

    /**
     * 标记失败并丢弃完成回调；[verificationFailure] 用于区分 URL/大小/哈希校验失败。
     */
    internal fun markFailed(
        id: Int,
        error: String,
        verificationFailure: DownloadVerificationException.Reason? = null,
    ) {
        _downloads.update { map ->
            val state = map[id] ?: return@update map
            map + (id to state.copy(status = Status.FAILED, error = error, verificationFailure = verificationFailure))
        }
        completionCallbacks.remove(id)
    }
}
