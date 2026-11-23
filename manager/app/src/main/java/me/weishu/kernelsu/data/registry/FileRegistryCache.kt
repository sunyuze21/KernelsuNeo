package me.weishu.kernelsu.data.registry

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Keeps the last good registry documents on disk. Writes go to a uniquely named temporary
 * file that is fsynced before it atomically replaces the entry, so concurrent writers never
 * share a temp file and a crash never leaves a truncated entry behind.
 */
class FileRegistryCache(private val dir: File) : RegistryCache {

    /** key 会先替换掉非安全字符，再落到 `{key}.cache`，避免路径穿越。 */
    private fun fileFor(key: String): File =
        File(dir, key.replace(UNSAFE, "_") + ".cache")

    /** 读取缓存文本；文件不存在时返回 null，损坏内容由调用方解析时处理。 */
    override fun read(key: String): String? {
        val file = fileFor(key)
        return if (file.isFile) file.readText() else null
    }

    /** 原子写入缓存：先写临时文件并 fsync，成功后才 rename 到正式文件名。 */
    override fun write(key: String, value: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val target = fileFor(key)
        val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(value.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Failed to write registry cache $key")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private companion object {
        /** 不允许出现在缓存文件名中的字符。 */
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
