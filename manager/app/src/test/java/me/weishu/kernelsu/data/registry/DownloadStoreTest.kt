package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** 覆盖下载存储的落盘、哈希/大小校验失败回滚、安装前二次校验与过期清理。 */
class DownloadStoreTest {

    /** JUnit 临时目录规则：每个测试使用独立根目录，避免清理类测试相互污染。 */
    @get:Rule
    val tmp = TemporaryFolder()

    // 固定内容的测试载荷；用可复现的构造式而不是随机数，保证哈希断言稳定。
    private val data = ByteArray(50_000) { (it * 31 % 253).toByte() }
    // 期望哈希统一以小写十六进制表示；存储层需兼容大写输入。
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    // 期望文件记录：大小 + 哈希，是下载目录命名与校验的唯一依据。
    private val expected = ExpectedFile(data.size.toLong(), sha)

    // 每次调用都新建目录，确保 cleanup 测试看到的是干净的 store 根。
    private fun root() = tmp.newFolder("registry_downloads")

    /** Everything under [dir], recursively, as relative paths. */
    private fun listing(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    // 成功路径：目录由 dirNameFor(expected) 决定，文件名被净化，进度回调累计等于文件大小。
    @Test
    fun verifiedDownloadIsStoredUnderItsExpectation() {
        val root = root()
        // 进度回调累计值，最终应等于总字节数。
        var progress = 0L
        val file = RegistryDownloadStore.download(ByteArrayInputStream(data), root, expected, "My Module (v1).zip", { progress = it })
        assertEquals(data.size.toLong(), progress)
        assertArrayEquals(data, file.readBytes())
        assertEquals("My_Module__v1_.zip", file.name)
        assertEquals(RegistryDownloadStore.dirNameFor(expected), file.parentFile!!.name)
        assertEquals(expected, RegistryDownloadStore.expectedFor(root, file))
        assertEquals(listOf("${RegistryDownloadStore.dirNameFor(expected)}/My_Module__v1_.zip"), listing(root))
    }

    // 哈希不匹配必须删除整个下载目录，避免被篡改的文件残留在缓存中。
    @Test
    fun hashMismatchDeletesEverything() {
        val root = root()
        // 只改一个字节，保持大小相同，确保失败原因是 SHA256 而非 SIZE。
        val tampered = data.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val e = assertThrows(DownloadVerificationException::class.java) {
            RegistryDownloadStore.download(ByteArrayInputStream(tampered), root, expected, "m.zip")
        }
        assertEquals(DownloadVerificationException.Reason.SHA256_MISMATCH, e.reason)
        assertEquals(emptyList<String>(), listing(root))
        assertEquals(0, root.list()!!.size)
    }

    // 超出或不足声明大小都要立即失败并清理，不能把不完整文件留下。
    @Test
    fun oversizedAndTruncatedDownloadsAreDeleted() {
        val root = root()
        val big = assertThrows(DownloadVerificationException::class.java) {
            RegistryDownloadStore.download(ByteArrayInputStream(data + byteArrayOf(1)), root, expected, "m.zip")
        }
        assertEquals(DownloadVerificationException.Reason.SIZE_MISMATCH, big.reason)
        val small = assertThrows(DownloadVerificationException::class.java) {
            RegistryDownloadStore.download(ByteArrayInputStream(data.copyOf(10)), root, expected, "m.zip")
        }
        assertEquals(DownloadVerificationException.Reason.SIZE_MISMATCH, small.reason)
        assertEquals(emptyList<String>(), listing(root))
    }

    // 网络 IO 异常与协程取消（checkActive 抛异常）都必须删除 .part 半成品。
    @Test
    fun networkErrorAndCancellationDeletePartialFile() {
        val root = root()
        // 模拟读到少量数据后连接重置，覆盖“已写入部分内容再失败”的清理路径。
        val failing = object : InputStream() {
            var n = 0
            // 单字节读直接失败，确保下载器走 bulk read 路径。
            override fun read(): Int = throw IOException("reset")
            // 先成功返回少量字节，再模拟连接重置，覆盖“已写部分数据后失败”的清理逻辑。
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ > 2) throw IOException("connection reset")
                b[off] = data[n]
                return 1
            }
        }
        assertThrows(IOException::class.java) { RegistryDownloadStore.download(failing, root, expected, "m.zip") }
        // 模拟协作式取消：回调抛异常后同样不能留下临时文件。
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            RegistryDownloadStore.download(ByteArrayInputStream(data), root, expected, "m.zip", checkActive = {
                if (++calls > 2) throw IllegalStateException("cancelled")
            })
        }
        assertEquals(emptyList<String>(), listing(root))
    }

    // 安装前对已存文件重新哈希，内容未变时应原样复制成功。
    @Test
    fun reHashBeforeFlashAcceptsExactBytes() {
        val stored = RegistryDownloadStore.download(ByteArrayInputStream(data), root(), expected, "m.zip")
        val dest = tmp.newFile("install.zip")
        stored.inputStream().use { copyForInstall(it, dest, expected) }
        assertArrayEquals(data, dest.readBytes())
    }

    // 下载校验后文件被同大小篡改，安装前二次校验必须失败并删除目标副本。
    @Test
    fun reHashBeforeFlashRejectsModifiedFileAndDeletesCopy() {
        val root = root()
        val stored = RegistryDownloadStore.download(ByteArrayInputStream(data), root, expected, "m.zip")
        // Same size, different content: modified after the download was verified.
        stored.writeBytes(data.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val dest = File(tmp.root, "install.zip")
        val e = assertThrows(DownloadVerificationException::class.java) {
            stored.inputStream().use { copyForInstall(it, dest, RegistryDownloadStore.expectedFor(root, stored)) }
        }
        assertEquals(DownloadVerificationException.Reason.SHA256_MISMATCH, e.reason)
        assertFalse(dest.exists())

        // 追加一个字节使大小不匹配，覆盖 SIZE 分支。
        stored.writeBytes(data + byteArrayOf(0))
        val sizeError = assertThrows(DownloadVerificationException::class.java) { verifyFile(stored, expected) }
        assertEquals(DownloadVerificationException.Reason.SIZE_MISMATCH, sizeError.reason)
        assertTrue(RegistryDownloadStore.delete(root, stored))
        assertEquals(emptyList<String>(), listing(root))
    }

    // 没有期望哈希（如本地模块）时不做校验，直接复制流内容。
    @Test
    fun unverifiedInstallJustCopies() {
        val dest = File(tmp.root, "plain.zip")
        copyForInstall(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dest, null)
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
    }

    // 只有位于 store 目录内、路径可规范化的文件才带期望值；路径穿越不能伪造期望。
    @Test
    fun onlyFilesInsideTheStoreCarryAnExpectation() {
        val root = root()
        val stored = RegistryDownloadStore.download(ByteArrayInputStream(data), root, expected, "m.zip")
        // 同名但位于 store 外的文件不应被识别。
        val outside = File(tmp.newFolder(RegistryDownloadStore.dirNameFor(expected)), "m.zip").apply { writeBytes(data) }
        assertNull(RegistryDownloadStore.expectedFor(root, outside))
        assertNull(RegistryDownloadStore.expectedFor(root, File(root, "m.zip")))
        assertNull(RegistryDownloadStore.expectedFor(root, File(File(root, "not-a-hash"), "m.zip")))
        // Path tricks resolve to the canonical location first.
        val dotted = File(root, "${stored.parentFile!!.name}/../${stored.parentFile!!.name}/m.zip")
        assertEquals(expected, RegistryDownloadStore.expectedFor(root, dotted))
        assertNull(RegistryDownloadStore.expectedFor(root, File(root, "${stored.parentFile!!.name}/../../x/m.zip")))
        // 删除同样受路径约束，store 外的文件不能被删。
        assertFalse(RegistryDownloadStore.delete(root, outside))
        assertTrue(outside.exists())
    }

    // 目录名是“大小-哈希”的编码，必须可逆；非法编码返回 null，避免信任外部输入。
    @Test
    fun dirNamesRoundTrip() {
        assertEquals(expected, RegistryDownloadStore.expectedFromDirName(RegistryDownloadStore.dirNameFor(expected)))
        // 哈希大小写不同视为同一期望，目录名需归一化。
        assertEquals(
            RegistryDownloadStore.dirNameFor(expected),
            RegistryDownloadStore.dirNameFor(ExpectedFile(expected.size, sha.uppercase()))
        )
        for (bad in listOf("", "x", "$sha-0", "$sha-", "${sha.uppercase()}-1", "$sha-1-2", "${sha.dropLast(1)}-1", "$sha-99999999999999999999")) {
            assertNull(bad, RegistryDownloadStore.expectedFromDirName(bad))
        }
        // 文件名净化必须去掉路径分隔与 ..，只保留最终文件名。
        assertEquals("module.zip", RegistryDownloadStore.sanitizeFileName("../.."))
        assertEquals("evil.zip", RegistryDownloadStore.sanitizeFileName("a/b/../evil.zip"))
    }

    // 超过 maxAge 的条目被删除；游离文件（不属于任何下载目录）也会被清理。
    @Test
    fun cleanupRemovesOldEntriesAndStrayFiles() {
        val root = root()
        val stored = RegistryDownloadStore.download(ByteArrayInputStream(data), root, expected, "m.zip")
        File(root, "stray.txt").writeText("x")
        RegistryDownloadStore.cleanup(root, maxAgeMillis = 60_000, now = stored.lastModified() + 1_000)
        assertEquals(listOf("${stored.parentFile!!.name}/m.zip", "stray.txt"), listing(root))
        RegistryDownloadStore.cleanup(root, maxAgeMillis = 60_000, now = stored.lastModified() + 120_000)
        assertEquals(0, root.list()!!.size)
    }

    // 清理不能误删并发下载刚创建的空目录或 .part 文件，否则会破坏进行中的任务。
    @Test
    fun cleanupKeepsFreshDirectoriesOfConcurrentDownloads() {
        val root = root()
        // Just created by a concurrent download, before (or while) it writes its .part file.
        val empty = File(root, RegistryDownloadStore.dirNameFor(expected)).apply { mkdirs() }
        val other = ExpectedFile(expected.size, "b".repeat(64))
        val partial = File(root, RegistryDownloadStore.dirNameFor(other)).apply { mkdirs() }
        val part = File.createTempFile("dl-", ".part", partial)
        // 以三者最新 mtime 作为基准，模拟“刚刚开始”的并发下载。
        val now = maxOf(empty.lastModified(), partial.lastModified(), part.lastModified())

        RegistryDownloadStore.cleanup(root, maxAgeMillis = 60_000, now = now + 1_000)
        assertTrue(empty.isDirectory)
        assertTrue(part.isFile)

        RegistryDownloadStore.cleanup(root, maxAgeMillis = 60_000, now = now + 120_000)
        assertEquals(0, root.list()!!.size)
    }

    // 下载去重共享只在 URL 与期望完全一致时成立；带校验与不带校验的下载不能混用。
    @Test
    fun verifiedAndUnverifiedDownloadsAreNeverShared() {
        val url = "https://github.com/o/r/releases/download/v1/m.zip"
        assertTrue(canShareDownload(url, null, url, null))
        // 哈希大小写不影响等价判断。
        assertTrue(canShareDownload(url, expected, url, ExpectedFile(expected.size, sha.uppercase())))
        assertFalse(canShareDownload(url, expected, url, null))
        assertFalse(canShareDownload(url, null, url, expected))
        assertFalse(canShareDownload(url, expected, url, ExpectedFile(expected.size + 1, sha)))
        assertFalse(canShareDownload(url, expected, url, ExpectedFile(expected.size, "b".repeat(64))))
        // URL 的 query 不同就是不同资源，不能复用。
        assertFalse(canShareDownload(url, expected, "$url?x", expected))
    }
}
