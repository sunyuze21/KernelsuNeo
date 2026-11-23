package me.weishu.kernelsu.core.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.io.input.BoundedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * 可随机读取的字节通道：底层可以是 OkHttp（HTTP Range）或本地 [FileChannel]。
 * 通过 [slice] 可以把 ZIP 内一段区间包装成独立通道，读取时自动带缓存并对齐 Range 请求。
 *
 * 注意：HTTP 模式要求服务端支持 Range；[streamRead] 会返回响应体流，调用方负责关闭。
 */
class DataSourceChannel private constructor(
    private val client: OkHttpClient?,
    private val url: String?,
    private val fileChannel: FileChannel?,
    /** 当前切片在整个远端/本地文件中的起始偏移。 */
    private val startOffset: Long,
    private val totalSize: Long,
) : SeekableByteChannel {

    /** 打开一个 HTTP 数据源；会先发一次 `Range: bytes=0-0` 请求确定总大小。 */
    constructor(client: OkHttpClient, url: String) : this(client, url, null, 0, fetchTotalSize(client, url))

    /** 当前读写位置（相对于本切片起点）。 */
    private var pos = 0L
    private var open = true
    /** 最近一次读取的缓存块及其起始偏移；[-1] 表示尚无缓存。 */
    private var cache: ByteArray? = null
    private var cacheStart = -1L

    /** 通道是否仍可读。 */
    override fun isOpen(): Boolean = open

    /** 本切片的总字节数（不是底层文件大小）。 */
    override fun size(): Long = totalSize

    /** 当前读写位置。 */
    override fun position(): Long = pos

    /** 设置读写位置；越界或已关闭时抛异常，不会自动裁剪。 */
    override fun position(newPosition: Long): DataSourceChannel {
        if (!open) throw ClosedChannelException()
        if (newPosition < 0) throw IllegalArgumentException("Position out of bounds: $newPosition")
        pos = newPosition
        return this
    }

    /** 关闭通道并释放缓存；底层 [fileChannel] 也会被关闭。 */
    override fun close() {
        open = false
        cache = null
        fileChannel?.close()
    }

    /** 从当前位置读取，并推进 [pos]；返回 -1 表示已到末尾。 */
    override fun read(dst: ByteBuffer): Int {
        val bytesRead = read(dst, pos)
        if (bytesRead > 0) pos += bytesRead
        return bytesRead
    }

    /**
     * 从指定位置读取到 [dst]；小读取走缓存，大读取直接流式读取。
     * 返回实际读取字节数，-1 表示 [position] 已超出末尾。
     */
    fun read(dst: ByteBuffer, position: Long): Int {
        if (!open) throw ClosedChannelException()
        if (position < 0) throw IllegalArgumentException("Position out of bounds: $position")
        if (position >= totalSize) return -1

        val requestSize = dst.remaining()
        if (requestSize == 0) return 0

        // 大块读取直接走 Range，避免先缓存再拷贝的双倍内存。
        if (requestSize > DIRECT_READ_THRESHOLD) {
            return handleLargeRead(dst, position)
        }

        var totalBytesRead = 0
        var currentPos = position
        if (isCacheHit(currentPos, 1)) {
            val bytesFromCache = readFromCache(dst, currentPos)
            totalBytesRead += bytesFromCache
            currentPos += bytesFromCache
        }

        if (dst.hasRemaining() && currentPos < totalSize) {
            loadCache(currentPos, requestSize)
            totalBytesRead += if (isCacheHit(currentPos, dst.remaining())) {
                readFromCache(dst, currentPos)
            } else {
                readDirectly(dst, currentPos)
            }
        }

        return totalBytesRead
    }

    /**
     * 返回本通道的子区间 [offset, offset+sliceSize)。
     * 子通道共享底层文件/URL，但大小和起始偏移独立；越界参数直接拒绝。
     */
    fun slice(offset: Long, sliceSize: Long): DataSourceChannel {
        if (offset == 0L && sliceSize == totalSize) return this
        if (offset < 0 || sliceSize <= 0 || offset + sliceSize > totalSize) {
            throw IllegalArgumentException("Invalid slice parameters")
        }
        return DataSourceChannel(client, url, fileChannel, startOffset + offset, sliceSize)
    }

    /** Reads up to [length] bytes at [position] into a fresh array. */
    fun readFully(position: Long, length: Long): ByteArray? {
        if (length > Int.MAX_VALUE) return null
        val buffer = ByteBuffer.allocate(length.toInt())
        val bytesRead = read(buffer, position)
        if (bytesRead <= 0) return null
        buffer.flip()
        val data = ByteArray(bytesRead)
        buffer.get(data)
        return data
    }

    /**
     * 返回 [position, position+length) 的输入流，用于解压/拷贝等顺序读取。
     * 本地模式不关闭底层文件通道；HTTP 模式返回响应体流，由调用方关闭。
     */
    fun streamRead(position: Long, length: Long): InputStream {
        val endPosition = minOf(position + length, totalSize) + startOffset
        val startPosition = startOffset + position
        val readLength = endPosition - startPosition

        if (fileChannel != null) {
            fileChannel.position(startPosition)
            return BoundedInputStream.builder()
                .setInputStream(Channels.newInputStream(fileChannel))
                .setMaxCount(readLength)
                .setPropagateClose(false)
                .get()
        }

        val request = Request.Builder()
            .url(url!!)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$startPosition-${endPosition - 1}")
            .build()

        val response = client!!.newCall(request).execute()
        // 必须收到 206，否则说明服务端忽略了 Range，会把整个文件当作这段数据。
        if (response.code != 206) {
            response.close()
            throw IOException("Unexpected response code ${response.code}")
        }
        return response.body.byteStream()
    }

    /** 大块读取：先消费可能命中的缓存，剩余部分直接流式读取。 */
    private fun handleLargeRead(dst: ByteBuffer, position: Long): Int {
        var bytesFromCache = 0
        var currentPos = position
        if (isCacheHit(currentPos, 1)) {
            bytesFromCache = readFromCache(dst, currentPos)
            currentPos += bytesFromCache
        }

        return if (dst.hasRemaining() && currentPos < totalSize) {
            bytesFromCache + readDirectly(dst, currentPos)
        } else {
            bytesFromCache
        }
    }

    /** 根据请求大小与是否顺序读取选择缓存块大小，然后重新填充缓存。 */
    private fun loadCache(requestPos: Long, requestSize: Int) {
        val lastCacheEnd = cache?.let { cacheStart + it.size } ?: -1L
        val newCacheSize: Int
        val newCacheStart: Long

        // 大请求或紧接上次缓存末尾时按顺序读处理，否则按随机读在请求前后各留一半。
        if (requestSize > SEQ_READ_THRESHOLD || lastCacheEnd == requestPos) {
            newCacheSize = SEQ_READ_CACHE_SIZE
            newCacheStart = requestPos
        } else {
            newCacheSize = RANDOM_READ_CACHE_SIZE
            newCacheStart = maxOf(0L, requestPos - newCacheSize / 2)
        }

        loadCacheAt(newCacheStart, newCacheSize)
    }

    /** 在 [cacheStart] 处读取 [cacheSize] 字节作为缓存；读不满视为 IO 失败。 */
    private fun loadCacheAt(cacheStart: Long, cacheSize: Int) {
        val maxEnd = minOf(cacheStart + cacheSize, totalSize)
        val start = maxOf(0L, maxEnd - cacheSize)

        val buffer = ByteBuffer.allocate((maxEnd - start).toInt())
        val bytesRead = readDirectly(buffer, start)
        if (bytesRead != buffer.capacity()) {
            throw IOException("Failed to fill cache.")
        }

        cache = buffer.array()
        this.cacheStart = start
    }

    /** 判断 [pos, pos+bytesToRead) 是否完全落在当前缓存内。 */
    private fun isCacheHit(pos: Long, bytesToRead: Int): Boolean {
        val cache = cache ?: return false
        val cacheEnd = cacheStart + cache.size
        val readEnd = minOf(pos + bytesToRead, totalSize)
        return pos >= cacheStart && readEnd <= cacheEnd
    }

    /** 从缓存拷贝数据到 [dst]，返回拷贝字节数。 */
    private fun readFromCache(dst: ByteBuffer, position: Long): Int {
        val cache = cache ?: return 0
        val relativePos = position - cacheStart
        val available = minOf(dst.remaining().toLong(), cache.size - relativePos).toInt()
        dst.put(cache, relativePos.toInt(), available)
        return available
    }

    /** 绕开缓存，直接通过 [streamRead] 读取；不足一次读满时会循环。 */
    private fun readDirectly(dst: ByteBuffer, position: Long): Int {
        Channels.newChannel(streamRead(position, dst.remaining().toLong())).use { channel ->
            var totalBytesRead = 0
            while (true) {
                val bytesRead = channel.read(dst)
                if (bytesRead <= 0) break
                totalBytesRead += bytesRead
            }
            return totalBytesRead
        }
    }

    /** 只读通道，写入始终抛异常。 */
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    /** 只读通道，截断始终抛异常。 */
    override fun truncate(size: Long): DataSourceChannel = throw NonWritableChannelException()

    companion object {
        /** 随机小读取的缓存大小；请求位置会尽量居中。 */
        private const val RANDOM_READ_CACHE_SIZE = 16 * 1024
        /** 顺序读取的缓存大小，一次覆盖多个 ZIP 头部/小文件。 */
        private const val SEQ_READ_CACHE_SIZE = 1024 * 1024
        /** 超过该大小的请求按顺序读处理，预取完整缓存块。 */
        private const val SEQ_READ_THRESHOLD = 1024
        /** 超过该大小的读取不再走缓存，直接流式读取。 */
        private const val DIRECT_READ_THRESHOLD = 512 * 1024
        private val SIZE_CONTENT_RANGE = Regex("bytes 0-0/(\\d+)", RegexOption.IGNORE_CASE)

        /** 通过 `Range: bytes=0-0` 的 Content-Range 解析远端文件总大小。 */
        private fun fetchTotalSize(client: OkHttpClient, url: String): Long {
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    throw IOException("Expected HTTP 206 for byte range, got ${response.code}")
                }
                val contentRange = response.header("Content-Range")
                    ?: throw IOException("Could not determine file size: missing Content-Range")
                return SIZE_CONTENT_RANGE.matchEntire(contentRange.trim())
                    ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IOException("Invalid Content-Range: $contentRange")
            }
        }
    }
}
