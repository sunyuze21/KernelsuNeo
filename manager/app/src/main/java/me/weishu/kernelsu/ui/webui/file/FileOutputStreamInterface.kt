package me.weishu.kernelsu.ui.webui.file

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.topjohnwu.superuser.io.SuFileOutputStream
import java.io.BufferedOutputStream
import java.util.UUID

/**
 * 暴露给 WebUI 的输出流接口（JS 桥接对象）。
 *
 * 写入通过 root 权限的 SuFileOutputStream 完成，数据以 Base64 传输；
 * 句柄存放在 [KsuIO] 中，受全局打开流数量限制。
 */
class FileOutputStreamInterface {
    /** 打开文件并返回流 id；[append] 为 true 时追加写。 */
    @JavascriptInterface
    fun open(path: String, append: Boolean): String = runCatching {
        if (!KsuIO.canOpenStream()) {
            Log.w(TAG, "FileOutputStream open rejected: too many open streams")
            return ""
        }
        // 通过 root 打开目标文件，支持系统受限路径。
        val file = com.topjohnwu.superuser.io.SuFile(path)
        val fos = SuFileOutputStream.open(file, append)
        // 64 KiB 缓冲减少跨进程/root 调用次数。
        val bos = BufferedOutputStream(fos, 64 * 1024)
        // 随机 id 作为 JS 侧句柄。
        val id = UUID.randomUUID().toString()
        KsuIO.openOutputStreams[id] = bos
        id
    }.onFailure { Log.e(TAG, "FileOutputStream open failed", it) }.getOrElse { "" }

    /** 以覆盖模式打开文件，等价于 `open(path, false)`。 */
    @JavascriptInterface
    fun open(path: String): String = open(path, false)

    /** 写入单个字节；同步块保证同一流上的写入不交错。 */
    @JavascriptInterface
    fun writeByte(id: String, b: Int): Boolean = runCatching {
        val bos = KsuIO.openOutputStreams[id] ?: return false
        synchronized(bos) { bos.write(b) }
        true
    }.onFailure { Log.e(TAG, "writeByte failed", it) }.getOrElse { false }

    /** 写入 Base64 编码的数据块。 */
    @JavascriptInterface
    fun write(id: String, base64: String): Boolean = runCatching {
        val bos = KsuIO.openOutputStreams[id] ?: return false
        // NO_WRAP 与 JS 侧 btoa 输出格式保持一致。
        val data = Base64.decode(base64, Base64.NO_WRAP)
        synchronized(bos) { bos.write(data) }
        true
    }.onFailure { Log.e(TAG, "write failed", it) }.getOrElse { false }

    /** 强制刷新缓冲，确保内容真正写入文件。 */
    @JavascriptInterface
    fun flush(id: String): Boolean = runCatching {
        val bos = KsuIO.openOutputStreams[id] ?: return false
        synchronized(bos) { bos.flush() }
        true
    }.onFailure { Log.e(TAG, "flush failed", it) }.getOrElse { false }

    /** 关闭并移除流；重复关闭返回 false。 */
    @JavascriptInterface
    fun close(id: String): Boolean {
        val bos = KsuIO.openOutputStreams.remove(id) ?: return false
        return runCatching {
            synchronized(bos) { bos.close() }
            true
        }.onFailure { Log.e(TAG, "close failed", it) }.getOrElse { false }
    }

    // WebUI 销毁时兜底关闭所有输出流，避免文件句柄泄漏。
    fun closeAll() {
        while (true) {
            val entry = KsuIO.openOutputStreams.entries.firstOrNull() ?: break
            val id = entry.key
            val bos = entry.value
            if (!KsuIO.openOutputStreams.remove(id, bos)) {
                continue
            }
            runCatching {
                synchronized(bos) { bos.close() }
            }.onFailure { Log.e(TAG, "closeAll failed for $id", it) }
        }
    }
}
