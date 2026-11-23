package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/** 验证流式下载校验器的增量哈希、字节计数与提前失败边界。 */
class DownloadVerifierTest {

    // 固定载荷，保证期望 SHA-256 可复现；长度 10000 便于测试分块。
    private val data = ByteArray(10_000) { (it % 251).toByte() }
    // 小写十六进制期望哈希，校验器需大小写不敏感。
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // 按固定块大小喂数据，模拟网络分片到达；默认 4096 可跨多个块覆盖边界。
    private fun feed(verifier: DownloadVerifier, bytes: ByteArray, chunk: Int = 4096) {
        var off = 0
        while (off < bytes.size) {
            val len = minOf(chunk, bytes.size - off)
            verifier.update(bytes, off, len)
            off += len
        }
    }

    // 内容和哈希一致时应校验通过，并准确累计字节数。
    @Test
    fun acceptsMatchingFile() {
        val v = DownloadVerifier(ExpectedFile(data.size.toLong(), sha))
        feed(v, data)
        v.finish()
        assertEquals(data.size.toLong(), v.bytesSeen)
        // Upper-case expected hash is accepted too.
        DownloadVerifier(ExpectedFile(data.size.toLong(), sha.uppercase())).also { feed(it, data); it.finish() }
    }

    // 同大小但内容被篡改时，finish 必须报告哈希不匹配。
    @Test
    fun rejectsHashMismatch() {
        // 只翻转一个字节，确保失败原因不是大小差异。
        val tampered = data.copyOf().also { it[42] = (it[42] + 1).toByte() }
        val v = DownloadVerifier(ExpectedFile(data.size.toLong(), sha))
        feed(v, tampered)
        val e = assertThrows(DownloadVerificationException::class.java) { v.finish() }
        assertEquals(DownloadVerificationException.Reason.SHA256_MISMATCH, e.reason)
    }

    // 流提前结束导致字节数不足时，finish 报告大小不匹配。
    @Test
    fun rejectsShortFile() {
        val v = DownloadVerifier(ExpectedFile(data.size.toLong(), sha))
        feed(v, data.copyOf(data.size - 1))
        val e = assertThrows(DownloadVerificationException::class.java) { v.finish() }
        assertEquals(DownloadVerificationException.Reason.SIZE_MISMATCH, e.reason)
    }

    // 一旦写入超过期望大小就应立即失败，而不是等到流结束，避免下载无界增长。
    @Test
    fun abortsEarlyOnOversizedFile() {
        val v = DownloadVerifier(ExpectedFile(data.size.toLong(), sha))
        val e = assertThrows(DownloadVerificationException::class.java) { feed(v, data + byteArrayOf(0)) }
        assertEquals(DownloadVerificationException.Reason.SIZE_MISMATCH, e.reason)
    }

    // 期望值本身非法（大小为 0 或哈希格式错误）必须在构造时抛出，而非运行期。
    @Test
    fun rejectsInvalidExpectation() {
        assertThrows(IllegalArgumentException::class.java) { DownloadVerifier(ExpectedFile(0, sha)) }
        assertThrows(IllegalArgumentException::class.java) { DownloadVerifier(ExpectedFile(1, "abc")) }
    }
}
