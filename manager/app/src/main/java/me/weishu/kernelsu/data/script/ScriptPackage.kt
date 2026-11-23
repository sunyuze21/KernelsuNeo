package me.weishu.kernelsu.data.script

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

/** 脚本 ZIP 校验/解压失败；[reason] 区分损坏、不安全条目、超限、清单错误等原因。 */
class ScriptPackageException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** 校验/解压失败的原因分类。 */
    enum class Reason {
        /** Not a readable ZIP (or an unsupported compression / encryption). */
        CORRUPT,

        /** Absolute path, `..`, symlink, special file or duplicate entry. */
        UNSAFE_ENTRY,
        /** 内容或条目数超过上限（防 ZIP bomb）。 */
        TOO_LARGE,
        /** 缺少 module.prop。 */
        MISSING_MODULE_PROP,
        /** module.prop 未声明 oneshot=true。 */
        NOT_ONESHOT,
        /** 模块 id 格式非法。 */
        INVALID_ID,
        /** module.prop 的 id 与预期的注册表 id 不一致。 */
        ID_MISMATCH,
        /** 缺少根目录 action.sh。 */
        MISSING_ACTION,
        /** params.json 不符合 schema。 */
        INVALID_PARAMS,
    }
}

/** module.prop of a one-shot script. */
data class ScriptManifest(
    /** 模块 id，同时用作运行记录的 key。 */
    val id: String,
    val name: String,
    val version: String,
    /** versionCode 缺失或非数字时为 null。 */
    val versionCode: Int?,
    val author: String,
    val description: String,
)

/**
 * Validation and extraction of one-shot script ZIPs. Only the central directory is trusted;
 * entries must be plain files or directories below the root, and everything written is
 * counted against [MAX_TOTAL_BYTES] while decompressing (declared sizes are not trusted).
 */
object ScriptPackage {
    /** 一次性脚本必须有 module.prop，用于声明 id 与 oneshot。 */
    const val MODULE_PROP = "module.prop"
    /** 入口脚本名，必须位于 ZIP 根目录。 */
    const val ACTION = ScriptCommand.ENTRY
    /** 可选参数声明文件。 */
    const val PARAMS = "params.json"

    /** 解压后允许写入的总字节数，按实际解压计数而非 ZIP 声明大小。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** Cap on the ZIP itself (contents + headers) for sources that are not size-verified. */
    const val MAX_ZIP_BYTES = MAX_TOTAL_BYTES + 16L * 1024 * 1024
    /** ZIP 条目数上限，防止中央目录本身耗尽内存。 */
    const val MAX_ENTRIES = 4096
    /** 读取 module.prop 的字节上限。 */
    const val MAX_PROP_BYTES = 64 * 1024
    /** 读取 params.json 的字节上限。 */
    const val MAX_PARAMS_BYTES = 64 * 1024

    /** 模块 id：字母开头，2~64 位，只允许字母数字与 ._-。 */
    private val MODULE_ID = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")
    // unix mode 中的文件类型位与权限位，用于拒绝符号链接/设备文件等特殊条目。
    private const val S_IFMT = 0xF000
    /** 普通文件类型位。 */
    private const val S_IFREG = 0x8000
    /** 目录类型位。 */
    private const val S_IFDIR = 0x4000
    /** 属主可执行权限位，解压后保留脚本可执行性。 */
    private const val S_IXUSR = 0x40

    /** Same rule as ksud's `metamodule`: trimmed value is "1" or ASCII case-insensitive "true". */
    fun isOneshot(value: String?): Boolean {
        val v = value?.trim() ?: return false
        return v == "1" || v.equals("true", ignoreCase = true)
    }

    /** 校验脚本模块 id；与 ksud 的模块 id 规则一致。 */
    fun isValidModuleId(id: String): Boolean = id.length in 2..64 && MODULE_ID.matches(id)

    /**
     * Java properties, like ksud (java-properties crate) and the registry validator read
     * module.prop: `=`, `:` or whitespace separators, escapes, continuation lines, the last
     * duplicate wins. A UTF-8 BOM is dropped.
     */
    fun parseModuleProp(text: String): Map<String, String> {
        val props = Properties()
        props.load(StringReader(text.removePrefix("\uFEFF")))
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    /** 从 properties 提取脚本清单；缺省 name 回退到 id。 */
    fun manifestOf(props: Map<String, String>): ScriptManifest {
        val id = props["id"].orEmpty().trim()
        return ScriptManifest(
            id = id,
            name = props["name"].orEmpty().trim().ifBlank { id },
            version = props["version"].orEmpty().trim(),
            versionCode = props["versionCode"]?.trim()?.toIntOrNull(),
            author = props["author"].orEmpty().trim(),
            description = props["description"].orEmpty().trim(),
        )
    }

    /**
     * The path [name] extracts to, normalized like ksud's zip crate and the registry validator:
     * empty and `.` segments are dropped (`./a//b` -> `a/b`, `./` -> `""`, the root). Throws for
     * an absolute path, a `..` segment, a backslash or a control character.
     */
    fun normalizeEntryName(name: String): String {
        /** 统一抛出 UNSAFE_ENTRY，避免泄露内部路径处理细节。 */
        fun unsafe(): Nothing = throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Unsafe entry: $name")
        if (name.isEmpty() || name.startsWith("/") || '\\' in name || name.any { it < ' ' || it == '\u007f' }) unsafe()
        // 丢弃空段与 "." 段，保留其余层级。
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) unsafe()
        return segments.joinToString("/")
    }

    /** [input] capped at [limit] bytes: reading more fails with [ScriptPackageException.Reason.TOO_LARGE]. */
    fun bounded(input: InputStream, limit: Long): InputStream = object : FilterInputStream(input) {
        private var count = 0L

        /** 累计读取量并在超过上限时立即失败。 */
        private fun counted(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > limit) throw ScriptPackageException(ScriptPackageException.Reason.TOO_LARGE, "Input exceeds $limit bytes")
            }
            return n
        }

        /** 单字节读取也计入总量。 */
        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
        /** 批量读取计入总量。 */
        override fun read(b: ByteArray, off: Int, len: Int): Int = counted(super.read(b, off, len))
        /** skip 同样计入总量，防止绕过上限。 */
        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) counted(it.toInt()) }
    }

    /**
     * Reads module.prop from a ZIP stream to tell whether it is a one-shot script; null if it
     * is not, or cannot be read. Only a hint: [validate] is what decides before running.
     */
    fun readOneshotManifest(input: InputStream): ScriptManifest? = try {
        // The compressed stream is capped too: endless empty deflate blocks inflate to nothing.
        ZipInputStream(bounded(input, MAX_ZIP_BYTES)).use { zip ->
            var manifest: ScriptManifest? = null
            // nextEntry() would inflate a skipped entry in full: drain it here, within the same
            // budget extraction has, so entries before module.prop cannot be a ZIP bomb.
            var skipped = 0L
            val buf = ByteArray(8192)
            for (i in 0 until MAX_ENTRIES) {
                val entry = zip.nextEntry ?: break
                if (normalizeEntryName(entry.name) == MODULE_PROP && !entry.isDirectory) {
                    val props = parseModuleProp(readLimited(zip, MAX_PROP_BYTES).toString(Charsets.UTF_8))
                    if (isOneshot(props["oneshot"])) manifest = manifestOf(props)
                    break
                }
                // 手动读空条目，确保解压量计入总预算。
                while (true) {
                    val n = zip.read(buf)
                    if (n == -1) break
                    skipped += n
                    if (skipped > MAX_TOTAL_BYTES) return@use null
                }
            }
            manifest
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 读取最多 [limit] 字节；超过上限视为压缩炸弹。 */
    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw ScriptPackageException(ScriptPackageException.Reason.TOO_LARGE, "Entry exceeds $limit bytes")
        }
        return out.toByteArray()
    }

    /** 打开 ZIP；IO 异常统一映射为 CORRUPT。 */
    private fun open(zip: File): ZipFile = try {
        ZipFile.builder().setFile(zip).get()
    } catch (e: IOException) {
        throw ScriptPackageException(ScriptPackageException.Reason.CORRUPT, "Not a valid ZIP: ${e.message}", e)
    }

    /** 目录条目的名称以 '/' 结尾（不能依赖 unix mode，部分打包器不写）。 */
    private fun isDirectory(entry: ZipArchiveEntry): Boolean = entry.name.endsWith("/")

    /** Checks every entry of the central directory; returns the entries by normalized path. */
    private fun checkEntries(file: ZipFile): Map<String, ZipArchiveEntry> {
        val byName = LinkedHashMap<String, ZipArchiveEntry>()
        var declared = 0L
        var count = 0
        for (entry in file.entries) {
            // Counts every record, including the skipped "./" ones.
            if (++count > MAX_ENTRIES) {
                throw ScriptPackageException(ScriptPackageException.Reason.TOO_LARGE, "More than $MAX_ENTRIES entries")
            }
            val key = normalizeEntryName(entry.name)
            if (entry.isUnixSymlink) {
                throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Symlink entry: ${entry.name}")
            }
            // 仅接受普通文件与目录；设备节点、FIFO 等一律拒绝。
            if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX) {
                val type = entry.unixMode and S_IFMT
                val expected = if (isDirectory(entry)) S_IFDIR else S_IFREG
                if (type != 0 && type != expected) {
                    throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Special file entry: ${entry.name}")
                }
            }
            if (key.isEmpty()) {
                // "./" is the extraction root itself; a file cannot be.
                if (isDirectory(entry)) continue
                throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Unsafe entry: ${entry.name}")
            }
            if (byName.put(key, entry) != null) {
                throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Duplicate entry: ${entry.name}")
            }
            if (!isDirectory(entry)) {
                // 不支持的压缩/加密方式在解压时无法处理，提前拒绝。
                if (!file.canReadEntryData(entry)) {
                    throw ScriptPackageException(ScriptPackageException.Reason.CORRUPT, "Unsupported entry: ${entry.name}")
                }
                if (entry.size > 0) declared += entry.size
                if (declared > MAX_TOTAL_BYTES) {
                    throw ScriptPackageException(ScriptPackageException.Reason.TOO_LARGE, "Contents exceed $MAX_TOTAL_BYTES bytes")
                }
            }
        }
        return byName
    }

    /** 读取文本条目，最多 [limit] 字节，按 UTF-8 解码。 */
    private fun readEntry(file: ZipFile, entry: ZipArchiveEntry, limit: Int): String =
        file.getInputStream(entry).use { readLimited(it, limit) }.toString(Charsets.UTF_8)

    /**
     * Validates a script ZIP: safe entries, module.prop with `oneshot`, a valid `id` (equal to
     * [expectedId] when given), a root action.sh and, if present, a valid params.json.
     */
    fun validate(zip: File, expectedId: String? = null): ScriptManifest = open(zip).use { file ->
        val entries = checkEntries(file)
        val propEntry = entries[MODULE_PROP]?.takeIf { !isDirectory(it) }
            ?: throw ScriptPackageException(ScriptPackageException.Reason.MISSING_MODULE_PROP, "module.prop not found")
        val props = parseModuleProp(readEntry(file, propEntry, MAX_PROP_BYTES))
        if (!isOneshot(props["oneshot"])) {
            throw ScriptPackageException(ScriptPackageException.Reason.NOT_ONESHOT, "module.prop does not declare oneshot=true")
        }
        val manifest = manifestOf(props)
        if (!isValidModuleId(manifest.id)) {
            throw ScriptPackageException(ScriptPackageException.Reason.INVALID_ID, "Invalid id: ${manifest.id}")
        }
        // 来自注册表的脚本必须与下载时使用的 id 一致，防止替换成其他脚本。
        if (expectedId != null && manifest.id != expectedId) {
            throw ScriptPackageException(ScriptPackageException.Reason.ID_MISMATCH, "id ${manifest.id} does not match $expectedId")
        }
        if (entries[ACTION]?.takeIf { !isDirectory(it) } == null) {
            throw ScriptPackageException(ScriptPackageException.Reason.MISSING_ACTION, "action.sh not found")
        }
        entries[PARAMS]?.takeIf { !isDirectory(it) }?.let { parseParams(readEntry(file, it, MAX_PARAMS_BYTES)) }
        manifest
    }

    /** 解析 params.json；错误统一转成 INVALID_PARAMS。 */
    fun parseParams(text: String): ScriptParams = try {
        ScriptParamsParser.parse(text)
    } catch (e: ScriptParamsException) {
        throw ScriptPackageException(ScriptPackageException.Reason.INVALID_PARAMS, "Invalid params.json: ${e.message}", e)
    }

    /**
     * Extracts [zip] to [dest] (replacing it). Files are written to a sibling temporary
     * directory first, so [dest] only ever holds a complete extraction.
     */
    fun extract(zip: File, dest: File) {
        val parent = dest.absoluteFile.parentFile ?: throw IOException("No parent for $dest")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
        // 同目录临时目录保证 rename 原子性，且失败时不会污染 dest。
        val tmp = File(parent, ".tmp-${UUID.randomUUID()}")
        try {
            open(zip).use { file ->
                val entries = checkEntries(file)
                if (!tmp.mkdir()) throw IOException("Cannot create $tmp")
                val root = tmp.canonicalFile
                var total = 0L
                val buf = ByteArray(64 * 1024)
                for ((path, entry) in entries) {
                    val target = File(root, path)
                    // Defence in depth on top of normalizeEntryName.
                    if (!target.canonicalPath.startsWith(root.path + File.separator)) {
                        throw ScriptPackageException(ScriptPackageException.Reason.UNSAFE_ENTRY, "Unsafe entry: ${entry.name}")
                    }
                    if (isDirectory(entry)) {
                        if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create ${entry.name}")
                        continue
                    }
                    target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ${entry.name}") }
                    file.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                total += n
                                // 实际解压总量才是可信的，不能只看 ZIP 声明的大小。
                                if (total > MAX_TOTAL_BYTES) {
                                    throw ScriptPackageException(
                                        ScriptPackageException.Reason.TOO_LARGE,
                                        "Contents exceed $MAX_TOTAL_BYTES bytes"
                                    )
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    // Keep helpers the script runs directly executable; setuid / setgid are never restored.
                    if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX && (entry.unixMode and S_IXUSR) != 0) {
                        if (!target.setExecutable(true, true)) throw IOException("Cannot make ${entry.name} executable")
                    }
                }
            }
            if (dest.exists() && !dest.deleteRecursively()) throw IOException("Cannot replace $dest")
            if (!tmp.renameTo(dest)) throw IOException("Cannot move extraction to $dest")
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }
}
