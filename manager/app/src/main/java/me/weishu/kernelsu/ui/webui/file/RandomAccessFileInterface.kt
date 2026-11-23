package me.weishu.kernelsu.ui.webui.file

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.topjohnwu.superuser.io.SuRandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 暴露给 WebUI 的 RandomAccessFile 接口（JS 桥接对象）。
 *
 * 用于 WebUI 需要随机定位读写的场景；所有文件都以 root 权限打开，
 * 句柄数有独立上限，避免耗尽文件描述符。
 */
class RandomAccessFileInterface {
    // 随机读写句柄的配额常量。
    companion object {
        // 与流接口分开限制，防止大量随机读写句柄拖垮进程。
        private const val MAX_OPEN_FILES = 30
    }
    
    private val openFiles = ConcurrentHashMap<String, SuRandomAccessFile>()

    /** 以 [mode]（如 "r"/"rw"）打开文件并返回句柄 id；超过上限返回空串。 */
    @JavascriptInterface
    fun open(path: String, mode: String): String = runCatching {
        if (openFiles.size >= MAX_OPEN_FILES) {
            Log.w(TAG, "RandomAccessFile open rejected: too many open files")
            return ""
        }
        val raf = SuRandomAccessFile.open(path, mode)
        val id = java.util.UUID.randomUUID().toString()
        openFiles[id] = raf
        id
    }.onFailure { Log.e(TAG, "RandomAccessFile open failed", it) }.getOrElse { "" }

    /** 读取一个字节；返回 -1 表示 EOF 或句柄无效。 */
    @JavascriptInterface
    fun read(id: String): Int = runCatching {
        val raf = openFiles[id] ?: return -1
        synchronized(raf) { raf.read() }
    }.onFailure { Log.e(TAG, "RandomAccessFile read() failed", it) }.getOrElse { -1 }

    /** 读取最多 [len] 字节并 Base64 返回；len 由 JS 指定，需注意内存占用。 */
    @JavascriptInterface
    fun readBytes(id: String, len: Int): String = runCatching {
        val raf = openFiles[id] ?: return ""
        val buffer = ByteArray(len)
        val bytesRead = synchronized(raf) { raf.read(buffer) }
        if (bytesRead > 0) {
            Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)
        } else {
            ""
        }
    }.onFailure { Log.e(TAG, "RandomAccessFile readBytes failed", it) }.getOrElse { "" }

    /** 读取一个 boolean。 */
    @JavascriptInterface
    fun readBoolean(id: String): Boolean = runCatching {
        val raf = openFiles[id] ?: return false
        synchronized(raf) { raf.readBoolean() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readBoolean failed", it) }.getOrElse { false }

    /** 读取一个有符号字节。 */
    @JavascriptInterface
    fun readByte(id: String): Byte = runCatching {
        val raf = openFiles[id] ?: return 0
        synchronized(raf) { raf.readByte() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readByte failed", it) }.getOrElse { 0 }

    /** 读取一个大端 int。 */
    @JavascriptInterface
    fun readInt(id: String): Int = runCatching {
        val raf = openFiles[id] ?: return 0
        synchronized(raf) { raf.readInt() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readInt failed", it) }.getOrElse { 0 }

    /** 读取一个大端 long。 */
    @JavascriptInterface
    fun readLong(id: String): Long = runCatching {
        val raf = openFiles[id] ?: return 0L
        synchronized(raf) { raf.readLong() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readLong failed", it) }.getOrElse { 0L }

    /** 读取一个大端 short。 */
    @JavascriptInterface
    fun readShort(id: String): Short = runCatching {
        val raf = openFiles[id] ?: return 0.toShort()
        synchronized(raf) { raf.readShort() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readShort failed", it) }.getOrElse { 0.toShort() }

    /** 读取一个 float。 */
    @JavascriptInterface
    fun readFloat(id: String): Float = runCatching {
        val raf = openFiles[id] ?: return 0f
        synchronized(raf) { raf.readFloat() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readFloat failed", it) }.getOrElse { 0f }

    /** 读取一个 double。 */
    @JavascriptInterface
    fun readDouble(id: String): Double = runCatching {
        val raf = openFiles[id] ?: return 0.0
        synchronized(raf) { raf.readDouble() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readDouble failed", it) }.getOrElse { 0.0 }

    /** 读取 Java modified UTF-8 字符串。 */
    @JavascriptInterface
    fun readUTF(id: String): String = runCatching {
        val raf = openFiles[id] ?: return ""
        synchronized(raf) { raf.readUTF() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readUTF failed", it) }.getOrElse { "" }

    /** 读取一行文本；EOF 时返回 null。 */
    @JavascriptInterface
    fun readLine(id: String): String? = runCatching {
        val raf = openFiles[id] ?: return null
        synchronized(raf) { raf.readLine() }
    }.onFailure { Log.e(TAG, "RandomAccessFile readLine failed", it) }.getOrNull()

    /** 写入一个字节。 */
    @JavascriptInterface
    fun write(id: String, b: Int): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.write(b) }
    }.onFailure { Log.e(TAG, "RandomAccessFile write failed", it) }.getOrDefault(Unit)

    /** 写入 Base64 编码的字节块。 */
    @JavascriptInterface
    fun writeBase64(id: String, data: String): Unit = runCatching {
        val raf = openFiles[id] ?: return
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        synchronized(raf) { raf.write(bytes) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeBase64 failed", it) }.getOrDefault(Unit)

    /** 写入一个 boolean。 */
    @JavascriptInterface
    fun writeBoolean(id: String, v: Boolean): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeBoolean(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeBoolean failed", it) }.getOrDefault(Unit)

    /** 写入一个字节（低 8 位）。 */
    @JavascriptInterface
    fun writeByte(id: String, v: Int): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeByte(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeByte failed", it) }.getOrDefault(Unit)

    /** 写入一个大端 int。 */
    @JavascriptInterface
    fun writeInt(id: String, v: Int): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeInt(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeInt failed", it) }.getOrDefault(Unit)

    /** 写入一个大端 long。 */
    @JavascriptInterface
    fun writeLong(id: String, v: Long): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeLong(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeLong failed", it) }.getOrDefault(Unit)

    /** 写入一个大端 short。 */
    @JavascriptInterface
    fun writeShort(id: String, v: Int): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeShort(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeShort failed", it) }.getOrDefault(Unit)

    /** 写入一个 float。 */
    @JavascriptInterface
    fun writeFloat(id: String, v: Float): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeFloat(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeFloat failed", it) }.getOrDefault(Unit)

    /** 写入一个 double。 */
    @JavascriptInterface
    fun writeDouble(id: String, v: Double): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeDouble(v) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeDouble failed", it) }.getOrDefault(Unit)

    /** 写入 Java modified UTF-8 字符串。 */
    @JavascriptInterface
    fun writeUTF(id: String, str: String): Unit = runCatching {
        val raf = openFiles[id] ?: return
        synchronized(raf) { raf.writeUTF(str) }
    }.onFailure { Log.e(TAG, "RandomAccessFile writeUTF failed", it) }.getOrDefault(Unit)

    /** 移动文件指针到绝对位置 [pos]。 */
    @JavascriptInterface
    fun seek(id: String, pos: Long): Boolean = runCatching {
        val raf = openFiles[id] ?: return false
        synchronized(raf) { raf.seek(pos) }
        true
    }.onFailure { Log.e(TAG, "RandomAccessFile seek failed", it) }.getOrElse { false }

    /** 获取当前文件指针位置；无效句柄返回 -1。 */
    @JavascriptInterface
    fun getFilePointer(id: String): Long = runCatching {
        val raf = openFiles[id] ?: return -1L
        synchronized(raf) { raf.getFilePointer() }
    }.onFailure { Log.e(TAG, "RandomAccessFile getFilePointer failed", it) }.getOrElse { -1L }

    /** 文件长度（字节）；无效句柄返回 -1。 */
    @JavascriptInterface
    fun length(id: String): Long = runCatching {
        val raf = openFiles[id] ?: return -1L
        synchronized(raf) { raf.length() }
    }.onFailure { Log.e(TAG, "RandomAccessFile length failed", it) }.getOrElse { -1L }

    /** 截断或扩展文件到指定长度。 */
    @JavascriptInterface
    fun setLength(id: String, newLength: Long): Boolean = runCatching {
        val raf = openFiles[id] ?: return false
        synchronized(raf) { raf.setLength(newLength) }
        true
    }.onFailure { Log.e(TAG, "RandomAccessFile setLength failed", it) }.getOrElse { false }

    /** 关闭并移除句柄；重复关闭返回 false。 */
    @JavascriptInterface
    fun close(id: String): Boolean {
        val raf = openFiles.remove(id) ?: return false
        return runCatching {
            synchronized(raf) { raf.close() }
            true
        }.onFailure { Log.e(TAG, "RandomAccessFile close failed", it) }.getOrElse { false }
    }

    // WebUI 销毁时兜底关闭全部随机读写句柄。
    fun closeAll() {
        openFiles.forEach { (id, raf) ->
            runCatching {
                synchronized(raf) { raf.close() }
            }.onFailure { Log.e(TAG, "closeAll failed for $id", it) }
        }
        openFiles.clear()
    }
}
