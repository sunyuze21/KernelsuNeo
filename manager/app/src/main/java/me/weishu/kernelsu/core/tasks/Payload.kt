package me.weishu.kernelsu.core.tasks

import chromeos_update_engine.UpdateMetadata.DeltaArchiveManifest
import chromeos_update_engine.UpdateMetadata.InstallOperation
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import me.weishu.kernelsu.core.utils.DataSourceChannel
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Android OTA `payload.bin` 的解析器：读取 protobuf manifest，按 InstallOperation
 * 从 [channel] 随机读取数据并写入目标分区文件，同时支持解析内核 KMI。
 */
class Payload(private val channel: DataSourceChannel) {
    /** payload 的元数据清单，包含分区、操作列表和块大小。 */
    private val manifest: DeltaArchiveManifest
    /** 数据区起点（manifest 与签名之后的绝对偏移），所有 operation.dataOffset 都相对于它。 */
    private var dataBase = 0L

    init {
        manifest = readPayloadHeader()
    }

    /**
     * 提取 [partitionName] 分区到 [outputFile]，并在 manifest 提供哈希时校验完整性。
     * 哈希不匹配会抛 [IOException]，此时输出文件内容不可信。
     */
    fun extract(
        outputFile: File,
        partitionName: String,
        console: (String) -> Unit,
        logger: (String) -> Unit,
    ) {
        val partition = findPartition(partitionName)
        console("- Found partition ${partition.partitionName}")

        val actualHash = extractPartition(outputFile, partition, console)

        if (!partition.newPartitionInfo.hasHash()) {
            logger("Hash verification skipped")
            return
        }

        // 把摘要转成小写十六进制，便于和 manifest 中的字节序列对比。
        fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        val expectedHash = partition.newPartitionInfo.hash.toByteArray()
        if (!expectedHash.contentEquals(actualHash)) {
            throw IOException(
                "Hash mismatch, expected ${toHex(expectedHash)}, but got ${toHex(actualHash)}"
            )
        }
        logger("Hash verification passed")
    }

    /** payload 中包含的所有分区名，顺序与 manifest 一致。 */
    fun partitionNames(): List<String> = manifest.partitionsList.map { it.partitionName }

    /**
     * Extracts the KMI from the payload's boot partition with minimal reads.
     * A miss means "unknown": the install page asks for a KMI manually and
     * remote downloads pass an explicit --kmi to ksud.
     */
    fun kernelKmi(onProgress: ((String) -> Unit)? = null): String? {
        // 只有真正携带 kernel 的分区才值得探测；init_boot 只有 ramdisk。
        for (name in BOOT_PARTITION_NAMES) {
            val partition = manifest.partitionsList.find { it.partitionName == name } ?: continue
            kernelKmi(partition, onProgress)?.let { return it }
        }
        return null
    }

    /** 先在 boot 分区中部探测，失败后回退到顺序扫描前缀。 */
    private fun kernelKmi(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)? = null,
    ): String? {
        // The banner can sit deep (EFI PE GKI, ~45-55% into the kernel), so
        // probe the middle op first, then fall back to the sequential walk.
        probeKernelMiddle(partition, onProgress)?.let { return it }
        return walkKernelPrefix(partition, onProgress)
    }

    /**
     * Ops map to uncompressed ranges via dst extents, so only the op covering
     * the kernel middle needs to be fetched.
     */
    private fun probeKernelMiddle(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)?,
    ): String? {
        val ranges = opRanges(partition)
        // 找到写入目标偏移 0 的 op，它的数据就是分区开头（含 boot header）。
        val firstIndex = ranges.indexOfFirst { it.first == 0L }
        if (firstIndex < 0) return null
        val first = partition.operationsList[firstIndex]
        if (first.dataLength <= 0 || first.getType() !in KMI_OPERATION_TYPES) return null

        // Locate the kernel block from the first op's header.
        // 只读取第一个 op 的头部来定位 kernel block。
        val header = channel.readFully(
            dataBase + first.dataOffset,
            minOf(first.dataLength, HEADER_PROBE_LIMIT),
        ) ?: return null
        val headerChunk = decodeChunk(header, first.getType()) ?: return null
        val (kernelOffset, kernelSize) = BootKernelVersion.bootKernelBlock(headerChunk) ?: return null
        if (kernelSize <= 0) return null

        // boot 镜像中部通常能命中 EFI PE GKI 的版本 banner。
        val target = kernelOffset.toLong() + kernelSize / 2L
        val middleIndex = ranges.indexOfFirst { it.first <= target && target < it.second }
        if (middleIndex < 0) return null
        val count = partition.operationsCount
        // The banner can sit just left of the middle op boundary; also cover the
        // previous op.
        for (index in middleIndex downTo maxOf(0, middleIndex - 1)) {
            val operation = partition.operationsList[index]
            if (operation.dataLength <= 0 || operation.getType() !in KMI_OPERATION_TYPES) continue
            onProgress?.invoke("- Analyzing boot partition ${index + 1}/$count")
            val data = channel.readFully(
                dataBase + operation.dataOffset,
                minOf(operation.dataLength, KMI_PROBE_LIMIT),
            ) ?: return null
            val chunk = decodeChunk(data, operation.getType()) ?: continue
            BootKernelVersion.scanKmi(chunk)?.let { return it }
        }
        return null
    }

    /** 顺序读取 boot 分区前部并累积解压结果，超出探测上限即放弃。 */
    private fun walkKernelPrefix(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)?,
    ): String? {
        val prefix = ByteArrayOutputStream()
        var scannedPrefix = 0
        val count = partition.operationsCount
        for ((index, operation) in partition.operationsList.withIndex()) {
            // 每 5 个 op 或最后一个时上报一次进度，避免刷屏。
            if (onProgress != null && (index % 5 == 0 || index == count - 1)) {
                onProgress("- Analyzing boot partition ${index + 1}/$count")
            }
            if (operation.dataLength <= 0 || operation.dstExtentsCount == 0) continue
            val type = operation.getType()
            if (type !in KMI_OPERATION_TYPES) continue

            val readLength = minOf(operation.dataLength, KMI_PROBE_LIMIT)
            val data = channel.readFully(dataBase + operation.dataOffset, readLength) ?: return null
            val chunk = decodeChunk(data, type) ?: return null
            prefix.write(chunk)
            BootKernelVersion.parseKmiFromBootData(prefix.toByteArray(), scannedPrefix)
                ?.let { return it }
            scannedPrefix = prefix.size()
            if (prefix.size() >= KMI_PROBE_LIMIT) return null
        }
        return null
    }

    /** 把 op 数据还原为未压缩字节；不支持的 op 类型返回 null。 */
    private fun decodeChunk(data: ByteArray, type: InstallOperation.Type): ByteArray? {
        return when (type) {
            InstallOperation.Type.REPLACE -> data
            InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ ->
                decompressPrefix(data, type)

            else -> null
        }
    }

    /** 按 dst extents 计算每个 op 在目标分区中的未压缩区间 [start, end)。 */
    private fun opRanges(partition: PartitionUpdate): List<Pair<Long, Long>> {
        var offset = 0L
        return partition.operationsList.map { operation ->
            val size = operation.dstExtentsList.sumOf { it.numBlocks } * manifest.blockSize
            val range = offset to (offset + size)
            offset += size
            range
        }
    }

    /** 按 op 的压缩类型选择 BZip2 / XZ 解压器；数据损坏时返回 null。 */
    private fun decompressPrefix(data: ByteArray, type: InstallOperation.Type): ByteArray? {
        return try {
            val stream = when (type) {
                InstallOperation.Type.REPLACE_BZ -> BZip2CompressorInputStream(ByteArrayInputStream(data))
                InstallOperation.Type.REPLACE_XZ -> XZCompressorInputStream(ByteArrayInputStream(data))
                else -> return null
            }
            stream.use { decompressPrefix(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** 解压数据流，最多保留 [KMI_PROBE_LIMIT] 字节；截断时保留已解出的前缀。 */
    private fun decompressPrefix(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (output.size() < KMI_PROBE_LIMIT) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, minOf(read, (KMI_PROBE_LIMIT - output.size()).toInt()))
            }
        } catch (_: IOException) {
            // Truncated stream: keep the prefix decompressed so far.
        }
        return if (output.size() > 0) output.toByteArray() else null
    }

    /**
     * 解析 payload 头部：魔数 "CrAU"、版本、manifest 长度与签名长度。
     * 成功后会推进 [channel] 位置到数据区并记录 [dataBase]。
     */
    @Throws(IOException::class)
    private fun readPayloadHeader(): DeltaArchiveManifest {
        val magicBuffer = ByteBuffer.allocate(4)
        channel.read(magicBuffer)
        magicBuffer.flip()
        val magic = String(magicBuffer.array())
        if (magic != "CrAU") {
            throw IOException("Invalid payload: invalid magic")
        }

        val versionBuffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        channel.read(versionBuffer)
        versionBuffer.flip()
        val version = versionBuffer.long
        if (version != 2L) {
            throw IOException("Invalid payload: unsupported version: $version")
        }

        val manifestLenBuffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        channel.read(manifestLenBuffer)
        manifestLenBuffer.flip()
        val manifestLen = manifestLenBuffer.long.toInt()
        if (manifestLen == 0) {
            throw IOException("Invalid payload: manifest length is zero")
        }

        val manifestSigLenBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        channel.read(manifestSigLenBuffer)
        manifestSigLenBuffer.flip()
        val manifestSigLen = manifestSigLenBuffer.int
        if (manifestSigLen == 0) {
            throw IOException("Invalid payload: manifest signature length is zero")
        }

        val manifestBuffer = ByteBuffer.allocate(manifestLen)
        channel.read(manifestBuffer)
        manifestBuffer.flip()
        val manifest = DeltaArchiveManifest.parseFrom(manifestBuffer.array())

        channel.position(channel.position() + manifestSigLen)

        // manifest 与签名之后才是 op 数据区。
        dataBase = channel.position()

        return manifest
    }

    /** 按分区名查找 manifest 条目；不存在时抛 [IOException]。 */
    @Throws(IOException::class)
    private fun findPartition(partitionName: String): PartitionUpdate {
        return manifest.partitionsList.find { it.partitionName == partitionName }
            ?: throw IOException("partition $partitionName not found in payload")
    }

    /**
     * 创建并预分配目标文件，逐个执行 op 写入，最后对整份输出计算 SHA-256。
     * 返回值用于和 manifest 中的分区哈希比较。
     */
    @Throws(IOException::class)
    private fun extractPartition(
        outputFile: File,
        partition: PartitionUpdate,
        console: (String) -> Unit,
    ): ByteArray {
        FileChannel.open(
            outputFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { outChannel ->
            // 在最后一块写入 1 字节，把文件预分配到分区大小。
            val size = partition.newPartitionInfo.size
            outChannel.write(ByteBuffer.allocate(1), size - 1)

            val count = partition.operationsCount
            partition.operationsList.forEachIndexed { index, operation ->
                if (index % 5 == 0 || index == count - 1) {
                    console("- Downloading ${index + 1}/$count")
                }
                processOperation(outChannel, operation)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = outChannel.map(FileChannel.MapMode.READ_WRITE, 0, size)
            digest.update(buffer)
            return digest.digest()
        }
    }

    /** 把单个 InstallOperation 的数据写入目标偏移；ZERO 类型只依赖预分配，无需写入。 */
    @Throws(IOException::class)
    private fun processOperation(outChannel: FileChannel, operation: InstallOperation) {
        val dataType = operation.getType()
        if (dataType == InstallOperation.Type.ZERO) {
            return
        }

        val dataBuffer = ByteBuffer.allocate(operation.dataLength.toInt())
        channel.read(dataBuffer, dataBase + operation.dataOffset)
        dataBuffer.flip()

        // op 的目标位置由第一个 dst extent 的起始块决定。
        val dstExtent = operation.getDstExtents(0)
        val outOffset = dstExtent.startBlock * manifest.blockSize

        when (dataType) {
            InstallOperation.Type.REPLACE -> {
                outChannel.write(dataBuffer, outOffset)
            }

            InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ -> {
                val inputStream = dataBuffer.array().inputStream()
                if (dataType == InstallOperation.Type.REPLACE_BZ) {
                    BZip2CompressorInputStream(inputStream)
                } else {
                    XZCompressorInputStream(inputStream)
                }.use { decompressor ->
                    val bytes = ByteArray(8192)
                    var bytesRead: Int
                    var bytesWritten = 0
                    while (decompressor.read(bytes).also { bytesRead = it } != -1) {
                        val buffer = ByteBuffer.wrap(bytes, 0, bytesRead)
                        bytesWritten += outChannel.write(buffer, outOffset + bytesWritten)
                    }
                }
            }

            else -> throw IOException("Unsupported operation type: $dataType")
        }
    }

    companion object {
        // KMI comes from the kernel block; init_boot only carries the ramdisk.
        private val BOOT_PARTITION_NAMES = listOf("boot", "vendor_kernel_boot")
        /** 能直接还原出内核字节的 op 类型。 */
        private val KMI_OPERATION_TYPES = setOf(
            InstallOperation.Type.REPLACE,
            InstallOperation.Type.REPLACE_BZ,
            InstallOperation.Type.REPLACE_XZ,
        )
        /** 读取探测数据的总上限，防止在异常 payload 上无限下载。 */
        private const val KMI_PROBE_LIMIT = 24L * 1024 * 1024
        /** 读取第一个 op 头部用于定位 kernel block 的上限。 */
        private const val HEADER_PROBE_LIMIT = 64L * 1024
    }
}
