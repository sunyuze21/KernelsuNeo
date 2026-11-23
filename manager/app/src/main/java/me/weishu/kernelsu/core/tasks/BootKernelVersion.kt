package me.weishu.kernelsu.core.tasks

import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.io.IOUtils
import me.weishu.kernelsu.core.utils.DataSourceChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * Mirrors ksud's parse_kmi_from_boot: touches only the boot header plus the
 * kernel block to find the KMI (e.g. "android15-6.6").
 */
object BootKernelVersion {
    /** Android boot image 魔数，位于文件偏移 0。 */
    private const val BOOT_MAGIC = "ANDROID!"
    /** 首轮读取的 boot header 固定长度，足够解析所有已支持的 header 版本。 */
    private const val HEADER_READ_SIZE = 64
    /** 首次只探测 1MB 内核数据，找不到 KMI 再按 4 倍扩张，避免下载整个 boot。 */
    private const val INITIAL_KERNEL_PROBE = 1L shl 20
    /** 远程（Range 请求）场景的初始探测大小。 */
    private const val INITIAL_REMOTE_PROBE = 256L * 1024
    /** 远程探测失败后每次扩大 4 倍，直到覆盖整个文件。 */
    private const val REMOTE_PROBE_GROWTH = 4L
    /** 分段扫描时保留的重叠字节，防止 KMI 字符串跨探测边界被截断。 */
    private const val KMI_SCAN_OVERLAP = 64

    // Boot header total sizes per header version (android_bootimg layouts)
    private val HEADER_TOTAL_SIZE = mapOf(
        0L to 1632L,
        1L to 1648L,
        2L to 1660L,
        3L to 1580L,
        4L to 1584L,
    )

    /** 从内核版本 banner 中提取 "6.6" + "android15" 形式的两段内容。 */
    private val kmiRegex = Regex("""(\d+\.\d+)(?:\S+)?(android\d+)""")

    /** 支持的压缩格式魔数；内核可能被压缩，需要先解压前缀再扫描。 */
    private val gzipMagic = byteArrayOf(0x1F, 0x8B.toByte())
    private val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val lz4FrameMagic = byteArrayOf(0x04, 0x22, 0x4D, 0x18)

    /** 从本地 boot 镜像文件解析 KMI；只做随机读取，不会把整个文件读入内存。 */
    fun parseKmiFromBoot(file: File): String? {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            // 先读固定头部，得到内核块在内核镜像中的偏移与大小。
            val header = readRange(channel, 0, HEADER_READ_SIZE.toLong()) ?: return null
            val (kernelOffset, kernelSize) = bootKernelBlock(header) ?: return null

            var probeSize = minOf(INITIAL_KERNEL_PROBE, kernelSize.toLong())
            while (true) {
                val data = readRange(channel, kernelOffset.toLong(), probeSize) ?: return null
                parseKmi(data)?.let { return it }
                if (probeSize >= kernelSize) {
                    decompressPartial(data)?.let { parseKmi(it) }?.let { return it }
                    return null
                }
                probeSize = minOf(probeSize * 4, kernelSize.toLong())
            }
        }
    }

    /** Reads a boot image stream, including a decompressed ZIP entry. */
    fun parseKmiFromBoot(input: InputStream): String? {
        val stream = DataInputStream(input)
        val header = ByteArray(HEADER_READ_SIZE)
        if (IOUtils.read(stream, header) < header.size) return null
        val (kernelOffset, kernelSize) = bootKernelBlock(header) ?: return null
        // 跳过头部到内核块之间的 padding；偏移可能大于已读的头部长度。
        IOUtils.skipFully(stream, kernelOffset.toLong() - header.size)

        var data = ByteArray(minOf(INITIAL_KERNEL_PROBE, kernelSize.toLong()).toInt())
        var previousSize = 0
        while (true) {
            stream.readFully(data, previousSize, data.size - previousSize)
            parseKmi(data)?.let { return it }
            decompressPartial(data)?.let { parseKmi(it) }?.let { return it }
            if (data.size >= kernelSize) return null
            previousSize = data.size
            data = data.copyOf(minOf(data.size.toLong() * 4, kernelSize.toLong()).toInt())
        }
    }

    /**
     * KMI from a range-readable channel (HTTP Range or local file), touching
     * only the boot header plus a small banner probe.
     */
    fun parseKmiFromBoot(channel: DataSourceChannel): String? {
        val maxSize = channel.size()
        var probeSize = minOf(INITIAL_REMOTE_PROBE, maxSize)
        while (true) {
            val data = channel.readFully(0, probeSize) ?: return null
            parseKmiFromBootData(data)?.let { return it }
            if (probeSize >= maxSize) return null
            probeSize = minOf(probeSize * REMOTE_PROBE_GROWTH, maxSize)
        }
    }

    /**
     * Parses a partial boot image prefix; [data] must start at offset 0.
     * [fromPrefixOffset] skips already-scanned bytes so the op-walking probe
     * stays cheap when the banner is deep.
     */
    fun parseKmiFromBootData(data: ByteArray, fromPrefixOffset: Int = 0): String? {
        val (kernelOffset, kernelSize) = bootKernelBlock(data) ?: return null
        if (data.size <= kernelOffset + 4) return null

        // 只扫描当前已拿到数据中的内核块部分。
        val kernelEnd = minOf(data.size, kernelOffset + kernelSize)
        if (kernelEnd <= kernelOffset) return null

        // Scan only the new bytes, with overlap for a banner straddling the
        // probe boundary.
        val scanFrom = maxOf(kernelOffset, fromPrefixOffset - KMI_SCAN_OVERLAP)
        if (scanFrom < kernelEnd) {
            parseKmi(data, scanFrom, kernelEnd)?.let { return it }
        }

        // Compressed kernel: decompress the prefix so far; the banner usually
        // sits early in the output.
        if (isCompressedKernel(data, kernelOffset)) {
            val kernelData = data.copyOfRange(kernelOffset, kernelEnd)
            decompressPartial(kernelData)?.let { parseKmi(it) }?.let { return it }
        }
        return null
    }

    /**
     * Scans arbitrary bytes, e.g. a single op's uncompressed chunk.
     */
    fun scanKmi(buffer: ByteArray): String? = parseKmi(buffer)

    /**
     * @return (kernel block offset, size), or null without a valid boot header.
     */
    fun bootKernelBlock(data: ByteArray): Pair<Int, Int>? {
        if (data.size < HEADER_READ_SIZE) return null
        if (!startsWith(data, BOOT_MAGIC.toByteArray())) return null
        // 各字段均为小端 u32：内核大小、header 版本和页大小。
        val kernelSize = data.u32le(8)
        val headerVersion = data.u32le(40)
        if (kernelSize <= 0) return null
        val headerTotal = HEADER_TOTAL_SIZE[headerVersion] ?: return null
        // header v3 起页大小固定为 4096，老版本在头部字段中声明。
        val pageSize = if (headerVersion >= 3) 4096L else data.u32le(36)
        if (pageSize <= 0) return null
        return align(headerTotal, pageSize).toInt() to kernelSize.toInt()
    }

    /** 检查内核块起始处是否为支持的压缩格式魔数。 */
    private fun isCompressedKernel(data: ByteArray, offset: Int): Boolean {
        return startsWithAt(data, offset, gzipMagic) ||
            startsWithAt(data, offset, xzMagic) ||
            startsWithAt(data, offset, lz4FrameMagic)
    }

    /** 从文件的指定偏移最多读取 [length] 字节；读不到数据时返回 null。 */
    private fun readRange(channel: FileChannel, position: Long, length: Long): ByteArray? {
        if (length > Int.MAX_VALUE) return null
        val buffer = ByteBuffer.allocate(length.toInt())
        var total = 0
        while (total < buffer.capacity()) {
            val read = channel.read(buffer, position + total)
            if (read < 0) break
            total += read
        }
        if (total == 0) return null
        buffer.flip()
        val data = ByteArray(total)
        buffer.get(data)
        return data
    }

    /**
     * 在字节数组中查找内核 banner 并拼出 KMI。
     * 先做低成本的首字节判断，只有命中 "x.y" 形态才截取可打印字符串做正则匹配。
     */
    private fun parseKmi(buffer: ByteArray, from: Int = 0, to: Int = buffer.size): String? {
        val end = minOf(to, buffer.size)
        for (i in maxOf(0, from) until end - 3) {
            val b0 = buffer[i].toInt() and 0xFF
            val b1 = buffer[i + 1].toInt() and 0xFF
            val b2 = buffer[i + 2].toInt() and 0xFF
            val b3 = buffer[i + 3].toInt() and 0xFF
            // 快速筛选形如 "5.10" / "6.6" 的位置，避免全文正则开销。
            if (b1 != '.'.code || b2 < '0'.code || b2 > '9'.code) continue
            if (b0 != '5'.code || b3 < '0'.code || b3 > '9'.code) {
                if (b0 < '6'.code || b0 > '9'.code) continue
            }
            // banner 以 NUL 结尾；最多看 100 字节，防止越界。
            val end = minOf(buffer.size, i + 100)
            val nul = (i until end).firstOrNull { buffer[it] == 0.toByte() } ?: end
            val text = String(buffer, i, nul - i, StandardCharsets.UTF_8)
            kmiRegex.find(text)?.let { match ->
                return "${match.groupValues[2]}-${match.groupValues[1]}"
            }
        }
        return null
    }

    /** 根据魔数选择解压器，只解压前缀；数据不完整或格式错误时返回 null。 */
    private fun decompressPartial(data: ByteArray): ByteArray? {
        return try {
            val stream = when {
                startsWith(data, gzipMagic) -> GZIPInputStream(ByteArrayInputStream(data))
                startsWith(data, xzMagic) -> XZCompressorInputStream(ByteArrayInputStream(data))
                startsWith(data, lz4FrameMagic) -> FramedLZ4CompressorInputStream(ByteArrayInputStream(data))
                else -> return null
            }
            stream.use { decompressPrefix(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** 尽量多地解压已截断的数据流；IO 异常说明数据不完整，保留已解出的前缀。 */
    private fun decompressPrefix(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        } catch (_: IOException) {
            // Truncated stream: keep the prefix decompressed so far.
        }
        return if (output.size() > 0) output.toByteArray() else null
    }

    /** 向上对齐到 [alignment] 的整数倍。 */
    private fun align(value: Long, alignment: Long): Long {
        return (value + alignment - 1) / alignment * alignment
    }

    /** 判断数组是否以指定魔数开头。 */
    private fun startsWith(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        for (i in magic.indices) {
            if (data[i] != magic[i]) return false
        }
        return true
    }

    /** 判断数组从 [offset] 开始是否为指定魔数。 */
    private fun startsWithAt(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (data.size < offset + magic.size) return false
        for (i in magic.indices) {
            if (data[offset + i] != magic[i]) return false
        }
        return true
    }

    /** 按小端序读取 4 字节无符号整数（返回值用 Long 以避免符号扩展）。 */
    private fun ByteArray.u32le(pos: Int): Long {
        return (this[pos].toLong() and 0xFF) or
            ((this[pos + 1].toLong() and 0xFF) shl 8) or
            ((this[pos + 2].toLong() and 0xFF) shl 16) or
            ((this[pos + 3].toLong() and 0xFF) shl 24)
    }
}
