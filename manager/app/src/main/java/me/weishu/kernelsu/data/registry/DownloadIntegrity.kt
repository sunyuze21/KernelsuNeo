package me.weishu.kernelsu.data.registry

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Expected properties of a registry release ZIP. */
data class ExpectedFile(val size: Long, val sha256: String) {
    /** Same expectation regardless of hash spelling. */
    /** 大小写不敏感地比较哈希，便于接受不同来源的写法。 */
    fun sameAs(other: ExpectedFile?): Boolean =
        other != null && size == other.size && sha256.equals(other.sha256, ignoreCase = true)
}

/** 下载校验失败；[reason] 供调用方区分 URL 不合法、大小不符或哈希不符。 */
class DownloadVerificationException(val reason: Reason, message: String) : IOException(message) {
    /** 校验失败的具体原因，调用方可据此决定是否删除已下载文件。 */
    enum class Reason { URL_NOT_ALLOWED, SIZE_MISMATCH, SHA256_MISMATCH }
}

/**
 * Two requests may share one download only when they ask for the same URL *and* the same
 * verification: a verified (registry) request must never reuse an unverified download, and
 * vice versa.
 */
fun canShareDownload(url: String, expected: ExpectedFile?, otherUrl: String, otherExpected: ExpectedFile?): Boolean {
    if (url != otherUrl) return false
    return if (expected == null) otherExpected == null else expected.sameAs(otherExpected)
}

/**
 * Streaming size + SHA-256 check. Feed every chunk to [update] (aborts early once more bytes
 * than expected arrive) and call [finish] before the file is handed to the installer.
 */
class DownloadVerifier(private val expected: ExpectedFile) {
    private val digest = MessageDigest.getInstance("SHA-256")
    /** 规范化后的期望哈希（小写），用于最终比较。 */
    private val expectedHash = expected.sha256.lowercase()
    /** 已流过的字节数；超过期望大小时立即失败，避免继续写入磁盘。 */
    var bytesSeen: Long = 0L
        private set

    init {
        require(expected.size > 0) { "expected size must be positive" }
        require(expectedHash.length == 64 && expectedHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "expected sha256 must be 64 hex chars"
        }
    }

    /** 喂入一段数据；累计大小超过期望值时提前抛出大小不符。 */
    @Throws(DownloadVerificationException::class)
    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        bytesSeen += length
        if (bytesSeen > expected.size) {
            throw DownloadVerificationException(
                DownloadVerificationException.Reason.SIZE_MISMATCH,
                "Downloaded more than the expected ${expected.size} bytes"
            )
        }
        digest.update(buffer, offset, length)
    }

    /** 在数据流结束时调用：先校验总大小，再比较 SHA-256。 */
    @Throws(DownloadVerificationException::class)
    fun finish() {
        if (bytesSeen != expected.size) {
            throw DownloadVerificationException(
                DownloadVerificationException.Reason.SIZE_MISMATCH,
                "Size mismatch: expected ${expected.size}, got $bytesSeen"
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedHash.toByteArray())) {
            throw DownloadVerificationException(
                DownloadVerificationException.Reason.SHA256_MISMATCH,
                "SHA-256 mismatch: expected $expectedHash, got $actual"
            )
        }
    }
}

/** Re-hashes [file] from disk; throws [DownloadVerificationException] on any mismatch. */
@Throws(IOException::class)
fun verifyFile(file: File, expected: ExpectedFile) {
    val verifier = DownloadVerifier(expected)
    if (file.length() != expected.size) {
        throw DownloadVerificationException(
            DownloadVerificationException.Reason.SIZE_MISMATCH,
            "Size mismatch: expected ${expected.size}, got ${file.length()}"
        )
    }
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            verifier.update(buf, 0, read)
        }
    }
    verifier.finish()
}

/**
 * Copies a module ZIP to [dest] (the exact file handed to `ksud module install`) and, when
 * [expected] is set, re-hashes the copy on disk right before installing. On mismatch [dest]
 * is deleted and [DownloadVerificationException] is thrown.
 */
@Throws(IOException::class)
fun copyForInstall(input: InputStream, dest: File, expected: ExpectedFile?) {
    try {
        FileOutputStream(dest).use { out ->
            input.copyTo(out)
            out.flush()
            // 先落盘再校验，确保交给安装器的是已持久化的字节。
            out.fd.sync()
        }
        if (expected != null) verifyFile(dest, expected)
    } catch (e: Throwable) {
        dest.delete()
        throw e
    }
}

/**
 * App-private store for registry ZIPs: `{root}/{sha256}-{size}/{fileName}`. Files never leave
 * app-private storage, and the directory name keeps the expected size + SHA-256 attached to
 * the file all the way to the flash step, which re-verifies the bytes it installs.
 */
object RegistryDownloadStore {
    /** 下载目录名，位于应用私有 files 目录下。 */
    const val DIR_NAME = "registry_downloads"

    /** 目录名格式：64 位小写十六进制哈希 + '-' + 正数大小。 */
    private val DIR_PATTERN = Regex("^([0-9a-f]{64})-([1-9][0-9]{0,17})$")
    /** 文件名中不允许的字符，统一替换为下划线，防止路径穿越。 */
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

    /** 生成携带校验信息的目录名；同一份文件因此可以反查期望大小与哈希。 */
    fun dirNameFor(expected: ExpectedFile): String = "${expected.sha256.lowercase()}-${expected.size}"

    /** 从目录名还原期望大小与 SHA-256；格式不符返回 null。 */
    fun expectedFromDirName(name: String): ExpectedFile? {
        val m = DIR_PATTERN.matchEntire(name) ?: return null
        val size = m.groupValues[2].toLongOrNull() ?: return null
        return ExpectedFile(size, m.groupValues[1])
    }

    /** 清理文件名：去掉路径部分、替换不安全字符、去掉开头点并限制长度。 */
    fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(UNSAFE_NAME_CHARS, "_")
            .trimStart('.')
            .take(120)
        return base.ifEmpty { "module.zip" }
    }

    /** Expected size/hash of [file] if it lives in the store under [root], otherwise null. */
    fun expectedFor(root: File, file: File): ExpectedFile? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val parent = canonical.parentFile ?: return null
        // root 也取 canonical，避免符号链接导致父目录比较失败。
        val storeRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (parent.parentFile != storeRoot) return null
        return expectedFromDirName(parent.name)
    }

    /**
     * Streams [input] into a unique temporary file, verifying size + SHA-256 on the fly, fsyncs
     * it and only then moves it to its final name. Any failure (including a verification
     * mismatch) deletes the partial file; nothing unverified is ever left under its final name.
     */
    @Throws(IOException::class)
    fun download(
        input: InputStream,
        root: File,
        expected: ExpectedFile,
        fileName: String,
        onProgress: (bytes: Long) -> Unit = {},
        checkActive: () -> Unit = {},
    ): File {
        val verifier = DownloadVerifier(expected)
        val dir = File(root, dirNameFor(expected))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val target = File(dir, sanitizeFileName(fileName))
        // 临时文件与目标同目录，rename 才是原子操作。
        val part = File.createTempFile("dl-", ".part", dir)
        try {
            FileOutputStream(part).use { out ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    // 允许调用方在取消时中断下载（会走 finally 清理临时文件）。
                    checkActive()
                    val read = input.read(buf)
                    if (read == -1) break
                    verifier.update(buf, 0, read)
                    out.write(buf, 0, read)
                    onProgress(verifier.bytesSeen)
                }
                out.flush()
                out.fd.sync()
            }
            verifier.finish()
            if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
            if (!part.renameTo(target)) throw IOException("Cannot move download to $target")
            return target
        } catch (e: Throwable) {
            part.delete()
            if (dir.list()?.isEmpty() == true) dir.delete()
            throw e
        }
    }

    /** Deletes a stored file (and its directory when empty); false if [file] is not in the store. */
    fun delete(root: File, file: File): Boolean {
        if (expectedFor(root, file) == null) return false
        val canonical = file.canonicalFile
        val deleted = canonical.delete()
        val dir = canonical.parentFile
        if (dir?.list()?.isEmpty() == true) dir.delete()
        return deleted
    }

    /**
     * Removes entries older than [maxAgeMillis] (and old leftovers that are not store entries).
     * Recent directories are kept even when empty: a concurrent download may have just created
     * its directory and not yet its temporary file.
     */
    fun cleanup(root: File, maxAgeMillis: Long, now: Long = System.currentTimeMillis()) {
        /** 判断文件/目录的最后修改时间是否超过保留期。 */
        fun isOld(f: File) = now - f.lastModified() > maxAgeMillis
        root.listFiles()?.forEach { dir ->
            // Checked before deleting anything inside, which bumps the directory's mtime.
            val dirOld = isOld(dir)
            if (!dir.isDirectory || expectedFromDirName(dir.name) == null) {
                if (dirOld) dir.deleteRecursively()
                return@forEach
            }
            dir.listFiles()?.forEach { f -> if (isOld(f)) f.delete() }
            if (dirOld && dir.list()?.isEmpty() == true) dir.delete()
        }
    }
}
