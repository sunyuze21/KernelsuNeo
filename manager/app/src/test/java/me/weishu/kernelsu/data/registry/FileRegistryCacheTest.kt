package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 覆盖文件缓存的读写、原子替换与并发写入时临时文件隔离。 */
class FileRegistryCacheTest {

    /** 每个测试独立临时目录，避免缓存文件残留影响断言。 */
    @get:Rule
    val tmp = TemporaryFolder()

    // 写入应原子完成：旧值被整体替换，键中的路径分隔符被转义为安全文件名。
    @Test
    fun writesReadsAndReplacesWithoutLeftovers() {
        val dir = File(tmp.root, "cache")
        val cache = FileRegistryCache(dir)
        // 未写入前读取返回 null，调用方可回退到网络。
        assertNull(cache.read("catalog"))
        cache.write("catalog", "one")
        // 覆盖写必须替换旧内容，不能追加。
        cache.write("catalog", "two ✓")
        assertEquals("two ✓", cache.read("catalog"))
        // 即使键里带 ..，也不能逃出缓存目录。
        cache.write("detail/../x", "safe")
        assertEquals("safe", cache.read("detail/../x"))
        assertEquals(listOf("catalog.cache", "detail_.._x.cache"), dir.list()!!.sorted())
    }

    // 并发写同一键时，每个线程使用独立临时文件，最终只能留下一个完整值和一个 .cache 文件。
    @Test
    fun concurrentWritersUseDistinctTempFiles() {
        val dir = File(tmp.root, "cache")
        val cache = FileRegistryCache(dir)
        val pool = Executors.newFixedThreadPool(8)
        // 值带较大固定前缀，放大交错写入的概率，便于暴露共享临时文件问题。
        val values = (0 until 64).map { "value-$it-" + "x".repeat(10_000) }
        values.forEach { v -> pool.submit { cache.write("same", v) } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        // Whatever won, it is one complete value and no temp file is left behind.
        assert(cache.read("same") in values)
        assertEquals(listOf("same.cache"), dir.list()!!.sorted())
    }
}
