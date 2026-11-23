package me.weishu.kernelsu.ui.webui

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

// 文件名缺失或非法时使用的兜底名称。
private const val DEFAULT_WEBUI_DOWNLOAD_NAME = "download.bin"

/** 在指定目录下解析出安全的下载目标文件，防止路径穿越到目录之外。 */
internal fun resolveWebUIDownloadFile(downloadsDir: File, requestedFileName: String?): File {
    val fileName = sanitizeWebUIDownloadFileName(requestedFileName)
    return File(downloadsDir, fileName)
}

/**
 * 清洗 WebUI 提供的文件名。
 *
 * 只取最后一段路径并拒绝 `.`/`..`，避免 `../../` 之类写到沙箱外；
 * 空名回退到 [DEFAULT_WEBUI_DOWNLOAD_NAME]。
 */
internal fun sanitizeWebUIDownloadFileName(requestedFileName: String?): String {
    val trimmed = requestedFileName?.trim().orEmpty()
    if (trimmed.isEmpty()) return DEFAULT_WEBUI_DOWNLOAD_NAME

    val candidate = trimmed
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()

    if (candidate.isEmpty() || candidate == "." || candidate == "..") {
        return DEFAULT_WEBUI_DOWNLOAD_NAME
    }

    return candidate
}

/**
 * 把输入流写入目标文件，并在写入过程中回报累计字节数。
 *
 * 会自动创建父目录；输出流由本函数负责关闭。
 */
internal fun writeWebUIDownload(
    target: File,
    source: InputStream,
    outputStreamFactory: (File) -> OutputStream = ::FileOutputStream,
    onBytesWritten: (Long) -> Unit = {},
): Long {
    target.parentFile?.mkdirs()
    return outputStreamFactory(target).use { output ->
        writeWebUIDownload(source, output, onBytesWritten)
    }
}

/** 把输入流复制到调用方提供的输出流；缓冲区 8 KiB，避免大文件占用内存。 */
internal fun writeWebUIDownload(
    source: InputStream,
    output: OutputStream,
    onBytesWritten: (Long) -> Unit = {},
): Long {
    output.buffered(64 * 1024).use { bufferedOutput ->
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            bufferedOutput.write(buffer, 0, read)
            total += read
            onBytesWritten(total)
        }
        bufferedOutput.flush()
        return total
    }
}
