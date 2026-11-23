package me.weishu.kernelsu.ui.webui.file

import android.util.Log
import android.webkit.JavascriptInterface
import com.topjohnwu.superuser.io.SuFile
import org.json.JSONArray
import java.io.BufferedOutputStream

/**
 * 暴露给 WebUI 的 java.io.File 等价接口。
 *
 * 所有操作都通过 [SuFile] 走 root shell，因此 WebUI 能访问系统受限路径；
 * 这也是模块 WebUI 拥有高权限的原因，接口仅应注入到可信的本地页面。
 */
class FileInterface(private val path: String) {
    // 所有文件操作都基于这个 root 文件句柄。
    private val suFile: SuFile = SuFile(path)

    /** 文件/目录是否存在。 */
    @JavascriptInterface
    fun exists(): Boolean = runCatching { suFile.exists() }.getOrDefault(false)

    /** 是否为普通文件。 */
    @JavascriptInterface
    fun isFile(): Boolean = runCatching { suFile.isFile }.getOrDefault(false)

    /** 是否为目录。 */
    @JavascriptInterface
    fun isDirectory(): Boolean = runCatching { suFile.isDirectory }.getOrDefault(false)

    /** 当前 root 身份是否可读。 */
    @JavascriptInterface
    fun canRead(): Boolean = runCatching { suFile.canRead() }.getOrDefault(false)

    /** 当前 root 身份是否可写。 */
    @JavascriptInterface
    fun canWrite(): Boolean = runCatching { suFile.canWrite() }.getOrDefault(false)

    /** 当前 root 身份是否可执行。 */
    @JavascriptInterface
    fun canExecute(): Boolean = runCatching { suFile.canExecute() }.getOrDefault(false)

    /** 创建空文件；已存在时失败。 */
    @JavascriptInterface
    fun createNewFile(): Boolean = runCatching { suFile.createNewFile() }.getOrDefault(false)

    /** 删除文件或空目录。 */
    @JavascriptInterface
    fun delete(): Boolean = runCatching { suFile.delete() }.getOrDefault(false)

    /** 递归删除目录及其内容，慎用。 */
    @JavascriptInterface
    fun deleteRecursive(): Boolean = runCatching { suFile.deleteRecursive() }.getOrDefault(false)

    /** 创建单级目录。 */
    @JavascriptInterface
    fun mkdir(): Boolean = runCatching { suFile.mkdir() }.getOrDefault(false)

    /** 递归创建目录。 */
    @JavascriptInterface
    fun mkdirs(): Boolean = runCatching { suFile.mkdirs() }.getOrDefault(false)

    /** 重命名/移动文件；目标路径同样通过 root 操作。 */
    @JavascriptInterface
    fun renameTo(destPath: String): Boolean = runCatching {
        suFile.renameTo(SuFile(destPath))
    }.getOrDefault(false)

    /** 列出子项名称，返回 JSON 数组字符串。 */
    @JavascriptInterface
    fun list(): String = runCatching {
        val names = suFile.list() ?: emptyArray()
        JSONArray(names.toList()).toString()
    }.getOrElse { "[]" }

    /** 列出子项的绝对路径，返回 JSON 数组字符串。 */
    @JavascriptInterface
    fun listFiles(): String = runCatching {
        val paths = suFile.listFiles()?.map { it.absolutePath } ?: emptyList()
        JSONArray(paths).toString()
    }.getOrElse { "[]" }

    /** 文件大小（字节）；失败返回 -1。 */
    @JavascriptInterface
    fun length(): Long = runCatching { suFile.length() }.getOrDefault(-1L)

    /** 最后修改时间（毫秒时间戳）；失败返回 -1。 */
    @JavascriptInterface
    fun lastModified(): Long = runCatching { suFile.lastModified() }.getOrDefault(-1L)

    /** 设置最后修改时间；需要 root 权限。 */
    @JavascriptInterface
    fun setLastModified(time: Long): Boolean = runCatching { suFile.setLastModified(time) }.getOrDefault(false)

    /** 绝对路径；解析失败时回退到构造时的原始路径。 */
    @JavascriptInterface
    fun getAbsolutePath(): String = runCatching { suFile.absolutePath }.getOrDefault(path)

    /** 规范化路径（解析符号链接）；失败时回退原路径。 */
    @JavascriptInterface
    fun getCanonicalPath(): String = runCatching { suFile.canonicalPath }.getOrDefault(path)

    /** 父目录路径，可能为 null。 */
    @JavascriptInterface
    fun getParent(): String? = runCatching { suFile.parent }.getOrNull()

    /** 构造时传入的原始路径，不做任何解析。 */
    @JavascriptInterface
    fun getPath(): String = path

    /** 文件名（不含父目录）。 */
    @JavascriptInterface
    fun getName(): String = runCatching { suFile.name }.getOrDefault("")

    /** 是否为隐藏文件（以 . 开头）。 */
    @JavascriptInterface
    fun isHidden(): Boolean = runCatching { suFile.isHidden }.getOrDefault(false)

    /** 是否为块设备。 */
    @JavascriptInterface
    fun isBlock(): Boolean = runCatching { suFile.isBlock }.getOrDefault(false)

    /** 是否为字符设备。 */
    @JavascriptInterface
    fun isCharacter(): Boolean = runCatching { suFile.isCharacter }.getOrDefault(false)

    /** 是否为符号链接。 */
    @JavascriptInterface
    fun isSymlink(): Boolean = runCatching { suFile.isSymlink }.getOrDefault(false)

    /** 创建符号链接，目标路径由调用方提供。 */
    @JavascriptInterface
    fun createNewSymlink(target: String): Boolean = runCatching {
        suFile.createNewSymlink(target)
    }.getOrDefault(false)

    /** 创建硬链接。 */
    @JavascriptInterface
    fun createNewLink(existing: String): Boolean = runCatching {
        suFile.createNewLink(existing)
    }.getOrDefault(false)

    /** 清空文件内容但保留文件本身。 */
    @JavascriptInterface
    fun clear(): Boolean = runCatching { suFile.clear() }.getOrDefault(false)

    /** 设为只读（去掉写权限）。 */
    @JavascriptInterface
    fun setReadOnly(): Boolean = runCatching { suFile.setReadOnly() }.getOrDefault(false)

    /** 设置读权限；[ownerOnly] 为 true 时只影响所有者。 */
    @JavascriptInterface
    fun setReadable(readable: Boolean, ownerOnly: Boolean): Boolean =
        runCatching { suFile.setReadable(readable, ownerOnly) }.getOrDefault(false)

    /** 设置写权限；[ownerOnly] 为 true 时只影响所有者。 */
    @JavascriptInterface
    fun setWritable(writable: Boolean, ownerOnly: Boolean): Boolean =
        runCatching { suFile.setWritable(writable, ownerOnly) }.getOrDefault(false)

    /** 设置执行权限；[ownerOnly] 为 true 时只影响所有者。 */
    @JavascriptInterface
    fun setExecutable(executable: Boolean, ownerOnly: Boolean): Boolean =
        runCatching { suFile.setExecutable(executable, ownerOnly) }.getOrDefault(false)

    /** 所在分区剩余空间（字节）。 */
    @JavascriptInterface
    fun getFreeSpace(): Long = runCatching { suFile.freeSpace }.getOrDefault(-1L)

    /** 所在分区总空间（字节）。 */
    @JavascriptInterface
    fun getTotalSpace(): Long = runCatching { suFile.totalSpace }.getOrDefault(-1L)

    /** 当前身份可用空间（字节）。 */
    @JavascriptInterface
    fun getUsableSpace(): Long = runCatching { suFile.usableSpace }.getOrDefault(-1L)

    /** 打开 root 输入流并返回句柄 id；超过全局上限时拒绝。 */
    @JavascriptInterface
    fun newInputStream(): String {
        return runCatching {
            if (!KsuIO.canOpenStream()) {
                Log.w(TAG, "newInputStream rejected: too many open streams")
                return ""
            }
            val stream = ManagedInputStream(suFile.newInputStream())
            val id = java.util.UUID.randomUUID().toString()
            KsuIO.openInputStreams[id] = stream
            id
        }.onFailure { Log.e(TAG, "newInputStream failed", it) }.getOrElse { "" }
    }

    /** 打开 root 输出流并返回句柄 id；[append] 决定是否追加写。 */
    @JavascriptInterface
    fun newOutputStream(append: Boolean): String {
        return runCatching {
            if (!KsuIO.canOpenStream()) {
                Log.w(TAG, "newOutputStream rejected: too many open streams")
                return ""
            }
            val stream = suFile.newOutputStream(append)
            val id = java.util.UUID.randomUUID().toString()
            KsuIO.openOutputStreams[id] = BufferedOutputStream(stream, ManagedInputStream.MAX_CHUNK_SIZE)
            id
        }.onFailure { Log.e(TAG, "newOutputStream failed", it) }.getOrElse { "" }
    }

    /** 以覆盖模式打开输出流，等价于 `newOutputStream(false)`。 */
    @JavascriptInterface
    fun newOutputStream(): String = newOutputStream(false)

    /** JS 侧字符串化时直接返回路径，便于调试。 */
    override fun toString(): String = path
}
