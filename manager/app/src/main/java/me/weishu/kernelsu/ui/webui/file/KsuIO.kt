package me.weishu.kernelsu.ui.webui.file

import android.webkit.JavascriptInterface
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

// 日志 tag 与文件接口共用；对外可见是因为各 Interface 类都会引用。
const val TAG = "KsuIO"

/**
 * WebUI 文件 IO 的总入口，作为 `ksu.io()` 注入到 JS。
 *
 * 只暴露工厂方法与版本号；真正的流句柄集中在 [openInputStreams] 和
 * [openOutputStreams]，以便统一限制数量并在页面销毁时兜底清理。
 */
object KsuIO {
    private const val KSU_IO_VERSION = 1
    // 同时打开的输入/输出流总数上限，防止 JS 恶意或异常占用文件描述符。
    private const val MAX_OPEN_STREAMS = 50

    internal val openInputStreams = ConcurrentHashMap<String, ManagedInputStream>()
    internal val openOutputStreams = ConcurrentHashMap<String, OutputStream>()

    // 无状态接口可复用单例；RandomAccessFile 自身维护句柄表。
    private val fileOutputStream = FileOutputStreamInterface()
    private val fileInputStream = FileInputStreamInterface()
    private val randomAccessFile = RandomAccessFileInterface()

    /** 返回 IO 桥接协议版本，JS 侧可据此做兼容判断。 */
    @JavascriptInterface
    fun version(): Int = KSU_IO_VERSION

    /** 创建指向 [path] 的 File 包装对象。 */
    @JavascriptInterface
    fun File(path: String): FileInterface = FileInterface(path)

    /** 获取输入流接口实例。 */
    @JavascriptInterface
    fun FileInputStream(): FileInputStreamInterface = fileInputStream

    /** 获取输出流接口实例。 */
    @JavascriptInterface
    fun FileOutputStream(): FileOutputStreamInterface = fileOutputStream

    /** 获取随机读写文件接口实例。 */
    @JavascriptInterface
    fun RandomAccessFile(): RandomAccessFileInterface = randomAccessFile

    /**
     * Check if we can open a new stream without exceeding resource limits.
     * Returns true if under limit, false otherwise.
     *
     * 说明：输入与输出流共享同一个上限，避免两类句柄各自膨胀。
     */
    internal fun canOpenStream(): Boolean {
        return openInputStreams.size + openOutputStreams.size < MAX_OPEN_STREAMS
    }

    /** 页面销毁时关闭所有句柄，防止 root shell 侧资源泄漏。 */
    fun destroy() {
        fileInputStream.closeAll()
        fileOutputStream.closeAll()
        randomAccessFile.closeAll()
    }
}
