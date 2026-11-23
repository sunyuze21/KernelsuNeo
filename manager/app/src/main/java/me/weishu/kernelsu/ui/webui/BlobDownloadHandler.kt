package me.weishu.kernelsu.ui.webui

import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Handles blob downloads by spooling chunks to a temporary file before WebView
 * requests the generated internal URL.
 *
 * 说明：WebUI 的 JS 通过桥接分块写入 Base64，再让 WebView 访问内部 https
 * 地址触发下载；分块与临时文件都只在应用缓存中，完成后立即删除。
 */
object BlobDownloadHandler {
    // 仅用于内部拦截的虚拟域名，不解析到真实网络。
    private const val BLOB_DOWNLOAD_HOST = "blob-download.kernelsu.internal"
    // 超过 5 分钟未完成的分块下载视为废弃，防止缓存泄漏。
    private const val STALE_THRESHOLD_MS = 5 * 60 * 1000L

    private val pendingDownloads = ConcurrentHashMap<String, PendingDownload>()
    @Volatile
    private var lastCleanupTime = System.currentTimeMillis()

    /** 一个进行中的分块下载：临时文件、输出流与完成标记。 */
    private data class PendingDownload(
        val fileName: String,
        val mimeType: String?,
        val tempFile: File,
        val outputStream: OutputStream,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var completed: Boolean = false,
    )

    /** Register a new blob download session and return its internal URL. */
    // 说明：返回的 URL 只在本次会话内有效，下载完成后由拦截器读取临时文件。
    fun registerBlobDownload(fileName: String, mimeType: String?, cacheDir: File): String {
        // 随机 id 同时作为 URL 路径，避免不同 WebUI 之间互相覆盖。
        val downloadId = UUID.randomUUID().toString()
        // 必须落在应用缓存目录，避免占用用户存储。
        val tempFile = File.createTempFile("ksu-blob-", ".download", cacheDir)
        pendingDownloads[downloadId] = PendingDownload(
            fileName = fileName,
            mimeType = mimeType,
            tempFile = tempFile,
            outputStream = tempFile.outputStream().buffered(64 * 1024),
        )

        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > STALE_THRESHOLD_MS) {
            lastCleanupTime = now
            cleanupStaleDownloads()
        }

        return "https://$BLOB_DOWNLOAD_HOST/$downloadId"
    }

    /** Write one base64 encoded chunk to the session. */
    // 说明：分块可能来自 JS 的多线程回调，写入需按会话加锁。
    fun writeChunk(downloadId: String, base64Chunk: String): Boolean {
        val download = pendingDownloads[downloadId] ?: return false
        return try {
            synchronized(download) {
                if (download.completed) return false
                download.outputStream.write(Base64.decode(base64Chunk, Base64.DEFAULT))
            }
            true
        } catch (e: Exception) {
            Log.e("BlobDownloadHandler", "Failed to write chunk for $downloadId", e)
            closeDownload(downloadId)
            false
        }
    }

    /** Mark the download complete and make the spooled file available to WebView. */
    // 完成后不再接受写入，并关闭输出流保证数据落盘。
    fun completeDownload(downloadId: String): Boolean {
        val download = pendingDownloads[downloadId] ?: return false
        return try {
            synchronized(download) {
                if (download.completed) return false
                download.outputStream.flush()
                download.outputStream.close()
                download.completed = true
            }
            true
        } catch (e: Exception) {
            Log.e("BlobDownloadHandler", "Failed to complete download $downloadId", e)
            closeDownload(downloadId)
            false
        }
    }

    // 取消即关闭并删除临时文件。
    fun cancelDownload(downloadId: String) = closeDownload(downloadId)

    /** Intercept requests to the internal blob URL and serve the completed file. */
    // 说明：只拦截内部虚拟域名；其他请求返回 null 交给后续处理器。
    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != BLOB_DOWNLOAD_HOST) return null

        val downloadId = url.path?.substring(1)?.takeIf { it.isNotEmpty() }
            ?: return createErrorResponse("Invalid download ID")
        val download = pendingDownloads[downloadId]
            ?: return createErrorResponse("Download not found")

        if (!download.completed) return createErrorResponse("Download is not ready")
        val input = runCatching { FileInputStream(download.tempFile) }.getOrElse {
            closeDownload(downloadId)
            return createErrorResponse("Download not found")
        }

        return WebResourceResponse(
            // 未提供 MIME 时回退为二进制流，避免浏览器按文本渲染。
            download.mimeType ?: "application/octet-stream",
            null,
            // 流关闭后自动清理临时文件。
            CleanupInputStream(input) { closeDownload(downloadId) },
        ).apply {
            responseHeaders = mapOf(
                "Content-Disposition" to "attachment; filename=\"${download.fileName}\"",
                "Access-Control-Allow-Origin" to "*",
            )
        }
    }

    // 从注册表移除并尽力关闭流、删除临时文件；所有异常都吞掉，清理不能影响主流程。
    private fun closeDownload(downloadId: String) {
        pendingDownloads.remove(downloadId)?.let { download ->
            runCatching { download.outputStream.close() }
            runCatching { download.tempFile.delete() }
        }
    }

    // 构造 404 文本响应；WebView 拦截失败时给 JS 一个明确错误。
    private fun createErrorResponse(message: String) = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
    )

    // 清理超过 5 分钟仍未完成的会话，避免 JS 异常退出后临时文件长期残留。
    private fun cleanupStaleDownloads() {
        val now = System.currentTimeMillis()
        val staleIds = pendingDownloads.entries
            .filter { now - it.value.createdAt > TimeUnit.MINUTES.toMillis(5) }
            .map { it.key }
        staleIds.forEach { downloadId ->
            Log.w("BlobDownloadHandler", "Cleaning up stale download: $downloadId")
            closeDownload(downloadId)
        }
    }

    // WebUI 销毁时清空所有会话。
    fun cleanup() = pendingDownloads.keys.toList().forEach(::closeDownload)

    /** 在流关闭时触发一次清理回调，确保临时文件不会泄漏。 */
    private class CleanupInputStream(
        input: InputStream,
        private val onClose: () -> Unit,
    ) : FilterInputStream(input) {
        // 关闭底层流后触发清理，确保临时文件随响应结束删除。
        override fun close() {
            runCatching { super.close() }
            onClose()
        }
    }
}
