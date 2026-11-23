package me.weishu.kernelsu.ui.webui.file

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.topjohnwu.superuser.io.SuFileInputStream
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * 暴露给 WebUI 的输入流接口（JS 桥接对象）。
 *
 * 流通过 root 权限打开，句柄保存在 [KsuIO] 中并用随机 id 引用，避免 JS
 * 直接持有 Java 对象；所有数据以 Base64 返回。
 */
class FileInputStreamInterface {
    /** 打开文件并返回流 id；失败或超过上限时返回空串。 */
    @JavascriptInterface
    fun open(path: String): String = runCatching {
        if (!KsuIO.canOpenStream()) {
            Log.w(TAG, "FileInputStream open rejected: too many open streams")
            return ""
        }
        // SuFileInputStream 通过 root shell 读取任意路径。
        val stream = ManagedInputStream(SuFileInputStream.open(path))
        // 随机 id 作为 JS 侧句柄，避免暴露可预测的路径/序号。
        val id = java.util.UUID.randomUUID().toString()
        KsuIO.openInputStreams[id] = stream
        id
    }.onFailure { Log.e(TAG, "FileInputStream open failed", it) }.getOrElse { "" }

    /** 读取默认大小的数据块并以 Base64 返回；EOF 时返回空串。 */
    @JavascriptInterface
    fun read(id: String): String = runCatching {
        val stream = KsuIO.openInputStreams[id] ?: return ""
        val bytesRead = stream.readInto()
        if (bytesRead > 0) {
            Base64.encodeToString(stream.buffer(), 0, bytesRead, Base64.NO_WRAP)
        } else {
            ""
        }
    }.onFailure { Log.e(TAG, "FileInputStream read failed", it) }.getOrElse { "" }

    /** 读取最多 [maxBytes] 字节；内部会裁剪到允许的分块上限。 */
    @JavascriptInterface
    fun read(id: String, maxBytes: Int): String = runCatching {
        val stream = KsuIO.openInputStreams[id] ?: return ""
        val bytesRead = stream.readInto(maxBytes)
        if (bytesRead > 0) {
            Base64.encodeToString(stream.buffer(), 0, bytesRead, Base64.NO_WRAP)
        } else {
            ""
        }
    }.onFailure { Log.e(TAG, "FileInputStream read(maxBytes) failed", it) }.getOrElse { "" }

    /** 返回预估可读字节数；仅作提示，实际读取仍可能阻塞。 */
    @JavascriptInterface
    fun available(id: String): Int = runCatching {
        val stream = KsuIO.openInputStreams[id] ?: return 0
        stream.available()
    }.getOrDefault(0)

    /** 关闭指定流；重复关闭或未知 id 返回 false。 */
    @JavascriptInterface
    fun close(id: String): Boolean {
        val stream = KsuIO.openInputStreams.remove(id) ?: return false
        return runCatching {
            stream.close()
            true
        }.getOrElse {
            Log.e(TAG, "FileInputStream close failed", it)
            false
        }
    }

    // WebUI 销毁时兜底关闭所有残留流；并发安全地逐个 remove。
    fun closeAll() {
        while (true) {
            val entry = KsuIO.openInputStreams.entries.firstOrNull() ?: break
            val id = entry.key
            val stream = entry.value
            if (!KsuIO.openInputStreams.remove(id, stream)) {
                continue
            }
            runCatching {
                stream.close()
            }.onFailure { Log.e(TAG, "closeAll failed for $id", it) }
        }
    }
}

/**
 * 带缓冲与分块限制的输入流包装。
 *
 * 读操作全部加锁，防止 JS 桥接的多次调用并发读写同一缓冲区导致数据错乱。
 */
internal class ManagedInputStream(
    inputStream: InputStream,
    private val defaultChunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxChunkSize: Int = MAX_CHUNK_SIZE,
) : AutoCloseable {
    private val stream = BufferedInputStream(inputStream, maxChunkSize)
    private var buffer = ByteArray(defaultChunkSize)

    // 读取一块数据到内部缓冲区，返回实际字节数；结果通过 buffer() 暴露给 JS。
    @Synchronized
    fun readInto(maxBytes: Int = defaultChunkSize): Int {
        // 强制限制在 [1, MAX_CHUNK_SIZE]，防止 JS 请求超大块导致 OOM。
        val requestedSize = maxBytes.coerceIn(1, maxChunkSize)
        if (buffer.size < requestedSize) {
            buffer = ByteArray(requestedSize)
        }
        return stream.read(buffer, 0, requestedSize)
    }

    // 返回最近一次读取使用的缓冲区；调用方只能读取前 readInto 返回的长度。
    @Synchronized
    fun buffer(): ByteArray = buffer

    // 底层流可读字节数，需加锁保证与读取串行。
    @Synchronized
    fun available(): Int = stream.available()

    // 关闭底层 root 输入流。
    @Synchronized
    override fun close() {
        stream.close()
    }

    // 分块大小常量。
    companion object {
        // 默认每次读 8 KiB，兼顾往返次数与 JS 侧解码开销。
        const val DEFAULT_CHUNK_SIZE = 8 * 1024
        // 单次最多 64 KiB，防止内存峰值过高。
        const val MAX_CHUNK_SIZE = 64 * 1024
    }
}
