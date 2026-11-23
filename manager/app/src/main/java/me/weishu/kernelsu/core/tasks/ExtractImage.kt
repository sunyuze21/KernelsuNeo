package me.weishu.kernelsu.core.tasks

import me.weishu.kernelsu.core.utils.DataSourceChannel
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** 探测结果：[partitions] 为包内可提取的分区名，[kmi] 为解析出的内核 KMI（未知时为 null）。 */
data class ProbeResult(
    val partitions: List<String>,
    val kmi: String?,
)

/**
 * 从 OTA / 工厂镜像 / 原始 payload 中提取 boot 镜像。
 * 所有输入都通过 [DataSourceChannel] 随机读取，避免整包下载；[outFile] 为最终输出路径。
 */
class ExtractImage(
    private val outFile: File,
    private val onConsole: (String) -> Unit,
) {
    /** 识别 ZIP 类型（OTA 或工厂镜像）并提取 [target] 分区到 [outFile]。 */
    @Throws(IOException::class)
    fun consume(channel: DataSourceChannel, target: String) {
        ZipFile.builder()
            .setSeekableByteChannel(channel)
            .setIgnoreLocalFileHeader(true)
            .get().use { zipFile ->
                val payload = zipFile.getEntry("payload.bin")
                if (payload != null) {
                    onConsole("- Processing as OTA package")

                    // OTA 元数据仅用于展示构建信息，不影响提取逻辑。
                    zipFile.getEntry("META-INF/com/android/metadata")?.let { entry ->
                        zipFile.getInputStream(entry).use {
                            val meta = it.bufferedReader().readText()

                            onConsole("- OTA metadata:")
                            meta.lines().forEach { line ->
                                if (line.startsWith("post-")) {
                                    onConsole("  ${line.substringAfter('-')}")
                                }
                            }
                        }
                    }
                    zipFile.getRawInputStream(payload)
                    extractFromOTAPackage(payload, channel, target)
                } else {
                    extractFromFactoryImage(zipFile, channel, target)
                }
            }
    }

    /** 直接把输入当作未压缩的 payload.bin 处理。 */
    @Throws(IOException::class)
    fun consumePayload(channel: DataSourceChannel, target: String) {
        onConsole("- Processing as raw payload.bin")
        Payload(channel).extract(
            outFile,
            target,
            onConsole,
            onConsole,
        )
    }

    /** 从 OTA ZIP 的 payload.bin 中提取分区；payload 必须是 STORED，否则无法按偏移切片。 */
    @Throws(IOException::class)
    private fun extractFromOTAPackage(
        payload: ZipArchiveEntry,
        channel: DataSourceChannel,
        target: String,
    ) {
        if (payload.method != ZipMethod.STORED.code) {
            throw IOException("payload.bin is compressed, expected STORED method")
        }

        // 只把 payload 所在的字节区间包装成子通道，后续按 payload 偏移读取。
        channel.slice(payload.dataOffset, payload.size).use { payloadChannel ->
            Payload(payloadChannel).extract(
                outFile,
                target,
                onConsole,
                onConsole,
            )
        }
    }

    /** 处理 Google 工厂镜像：boot 镜像可能在根目录，也可能在嵌套的 image-*.zip 中。 */
    @Throws(IOException::class)
    private fun extractFromFactoryImage(
        zipFile: ZipFile,
        channel: DataSourceChannel,
        target: String,
    ) {
        onConsole("- Processing as factory image package")

        findBootImageEntry(zipFile, target)?.let { entry ->
            return extractImageFile(zipFile, entry, channel)
        }

        // 工厂镜像通常把各分区再打成一个 image-<build>.zip。
        val imageZipEntry = zipFile.entries.asSequence().find { entry ->
            val fileName = entry.name.substringAfterLast('/')
            fileName.startsWith("image-") && fileName.endsWith(".zip")
        }
        if (imageZipEntry != null) {
            zipFile.getRawInputStream(imageZipEntry)
            return extractFromInnerImageZip(imageZipEntry, channel, target)
        }

        throw IOException("inner image ZIP not found in factory image package")
    }

    /** 按目标分区名查找对应的 boot 镜像条目；未知分区名返回 null。 */
    private fun findBootImageEntry(zipFile: ZipFile, target: String): ZipArchiveEntry? {
        val imageName = BOOT_IMAGE_NAMES[target] ?: return null
        return zipFile.entries.asSequence().find {
            it.name.substringAfterLast('/') == imageName
        }
    }

    /** 在内层 image-*.zip 中查找并提取目标镜像；内层同样要求 STORED。 */
    @Throws(IOException::class)
    private fun extractFromInnerImageZip(
        entry: ZipArchiveEntry,
        channel: DataSourceChannel,
        target: String,
    ) {
        onConsole("Found inner image ZIP: ${entry.name}")

        if (entry.method != ZipMethod.STORED.code) {
            throw IOException("image ZIP is compressed, expected STORED method")
        }

        channel.slice(entry.dataOffset, entry.size).use { innerZipChannel ->
            ZipFile.builder()
                .setSeekableByteChannel(innerZipChannel)
                .setIgnoreLocalFileHeader(true)
                .get().use { innerZipFile ->
                    val targetEntry = findBootImageEntry(innerZipFile, target)
                        ?: throw IOException("boot image not found in inner image ZIP")
                    return extractImageFile(innerZipFile, targetEntry, innerZipChannel)
                }
        }
    }

    /**
     * 把 ZIP 中的镜像条目写到 [outFile]。
     * STORED 走零拷贝 mmap；DEFLATED 走流式解压；其他压缩方式不支持。
     */
    @Throws(IOException::class)
    private fun extractImageFile(
        zipFile: ZipFile,
        entry: ZipArchiveEntry,
        channel: DataSourceChannel,
    ) {
        onConsole("- Found boot image entry: ${entry.name} (${entry.size} bytes)")
        onConsole("- Downloading")

        zipFile.getRawInputStream(entry)
        when (entry.method) {
            ZipMethod.STORED.code -> {
                FileChannel.open(
                    outFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { fileChannel ->
                    // 直接映射目标文件，再让源通道写入，避免中转缓冲区。
                    val mapped = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, entry.size)
                    val sourceChannel = channel.slice(entry.dataOffset, entry.size)
                    sourceChannel.read(mapped)
                }
            }

            ZipMethod.DEFLATED.code -> {
                InflaterInputStream(
                    channel.streamRead(entry.dataOffset, entry.compressedSize),
                    Inflater(true),
                    16 * 1024
                ).use { input ->
                    FileOutputStream(outFile).use { out ->
                        input.copyTo(out)
                    }
                }
            }

            else -> throw IOException("unsupported method: ${entry.method}")
        }
    }

    companion object {
        /** 逻辑分区名到镜像文件名的映射；只有这些分区会被当作可安装目标。 */
        private val BOOT_IMAGE_NAMES = mapOf(
            "boot" to "boot.img",
            "init_boot" to "init_boot.img",
            "vendor_boot" to "vendor_boot.img",
        )

        /** 探测普通 ZIP / OTA / 工厂镜像中的可用分区和 KMI（不下载完整内容）。 */
        @Throws(IOException::class)
        fun probe(
            channel: DataSourceChannel,
            withKmi: Boolean = true,
            onProgress: ((String) -> Unit)? = null,
        ): ProbeResult {
            return probeEntries(channel, withKmi, onProgress)
        }

        /** 探测原始 payload.bin 的分区列表与 KMI。 */
        @Throws(IOException::class)
        fun probePayload(
            channel: DataSourceChannel,
            withKmi: Boolean = true,
            onProgress: ((String) -> Unit)? = null,
        ): ProbeResult {
            val payload = Payload(channel)
            return ProbeResult(
                partitions = filterBootPartitions(payload.partitionNames()),
                kmi = if (withKmi) payload.kernelKmi(onProgress) else null,
            )
        }

        /** 递归探测 ZIP 内容：payload.bin、根目录 boot 镜像或嵌套的 image-*.zip。 */
        @Throws(IOException::class)
        private fun probeEntries(
            channel: DataSourceChannel,
            withKmi: Boolean,
            onProgress: ((String) -> Unit)?,
        ): ProbeResult {
            ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setIgnoreLocalFileHeader(true)
                .get().use { zipFile ->
                    val payload = zipFile.getEntry("payload.bin")
                    if (payload != null) {
                        if (payload.method != ZipMethod.STORED.code) {
                            throw IOException("payload.bin is compressed, expected STORED method")
                        }
                        zipFile.getRawInputStream(payload)
                        channel.slice(payload.dataOffset, payload.size).use { payloadChannel ->
                            val payload = Payload(payloadChannel)
                            return ProbeResult(
                                partitions = filterBootPartitions(payload.partitionNames()),
                                kmi = if (withKmi) payload.kernelKmi(onProgress) else null,
                            )
                        }
                    }

                    // 根目录直接放 boot.img 等镜像的工厂镜像布局。
                    val bootParts = BOOT_IMAGE_NAMES.entries
                        .filter { (_, imageName) ->
                            zipFile.entries.asSequence().any {
                                it.name.substringAfterLast('/') == imageName
                            }
                        }
                        .map { it.key }
                    if (bootParts.isNotEmpty()) {
                        val kmi = if (withKmi) {
                            KERNEL_IMAGE_NAMES.firstNotNullOfOrNull { imageName ->
                                zipFile.entries.asSequence()
                                    .firstOrNull { it.name.substringAfterLast('/') == imageName }
                                    ?.let { readBootImageKmi(zipFile, it) }
                            }
                        } else {
                            null
                        }
                        return ProbeResult(bootParts, kmi)
                    }

                    // 否则继续深入内层 image-*.zip。
                    val imageZip = zipFile.entries.asSequence().find { entry ->
                        val fileName = entry.name.substringAfterLast('/')
                        fileName.startsWith("image-") && fileName.endsWith(".zip")
                    }
                    if (imageZip != null) {
                        if (imageZip.method != ZipMethod.STORED.code) {
                            throw IOException("image ZIP is compressed, expected STORED method")
                        }
                        zipFile.getRawInputStream(imageZip)
                        channel.slice(imageZip.dataOffset, imageZip.size).use { innerChannel ->
                            return probeEntries(innerChannel, withKmi, onProgress)
                        }
                    }

                    throw IOException("boot image not found in package")
                }
        }

        /** 读取单个镜像条目的 KMI；仅支持未压缩或 deflate 的数据。 */
        private fun readBootImageKmi(zipFile: ZipFile, entry: ZipArchiveEntry): String? {
            if (entry.method != ZipMethod.STORED.code && entry.method != ZipMethod.DEFLATED.code) return null
            return zipFile.getInputStream(entry).use { input ->
                BootKernelVersion.parseKmiFromBoot(input)
            }
        }

        /** 只保留客户端认识、可安装的 boot 相关分区。 */
        private fun filterBootPartitions(names: List<String>): List<String> {
            return BOOT_IMAGE_NAMES.keys.filter { it in names }
        }

        /** 用于解析 KMI 的镜像优先级列表，目前只有 boot.img 携带内核。 */
        private val KERNEL_IMAGE_NAMES = listOf("boot.img")
    }
}
