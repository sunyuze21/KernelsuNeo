package me.weishu.kernelsu.data.script

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.registry.DownloadVerificationException
import me.weishu.kernelsu.data.registry.RegistryDownloadStore
import me.weishu.kernelsu.data.registry.copyForInstall
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.removeAsRoot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** A validated script, extracted and ready to run. */
data class PreparedScript(
    val manifest: ScriptManifest,
    /** Extraction directory: the script's working directory and `$MODPATH`. */
    val dir: File,
    /** ZIP 内容的 SHA-256，用于区分同 id 的不同版本运行目录。 */
    val sha256: String,
    /** 已解析的参数声明；没有 params.json 时为 [ScriptParams.EMPTY]。 */
    val params: ScriptParams,
    /** action.sh 源码，供运行页展示。 */
    val actionSource: String,
    /** action.sh 超过 [ScriptRepository] 的源码上限而被截断。 */
    val actionSourceTruncated: Boolean,
)

/** The page released the extraction while it was being prepared; nobody waits for the result. */
class ReleasedException : IOException("Extraction released during preparation")

/** The registry ZIP is not (or no longer) a verified download of the store. */
class UnverifiedScriptException : IOException("Registry download is not verified")

/**
 * 一次性脚本的准备工作与运行记录。
 * prepare/release 用 owner token 管理临时解压目录；历史记录存 SharedPreferences。
 */
object ScriptRepository {
    /** 日志标签。 */
    private const val TAG = "ScriptRepository"
    /** 解压目录位于应用私有 files 目录下。 */
    const val DIR_NAME = "oneshot"
    /** 运行历史使用的 SharedPreferences 文件名。 */
    private const val PREFS = "oneshot_scripts"
    /** action.sh 最多展示的字符数，避免大文件拖垮 UI。 */
    private const val MAX_SOURCE_CHARS = 256 * 1024

    /** 解压根目录。 */
    private fun root(): File = File(ksuApp.filesDir, DIR_NAME)

    /**
     * Extraction held by each owner (one preparation of a run page); everything else under
     * [root] is stale. Guarded by itself together with [released].
     */
    private val extractions = HashMap<String, File>()

    /** Owners released before their preparation registered an extraction. */
    private val released = HashSet<String>()

    /** Deletes released extractions off the caller's (often the main) thread. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises preparations, so cleanup never sees another preparation's half-done files. */
    private val prepareLock = Mutex()

    /** 运行历史，延迟创建 SharedPreferences 存储。 */
    val history: ScriptRunHistory by lazy {
        val prefs = ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ScriptRunHistory(object : ScriptRunStorage {
            /** 从 SharedPreferences 读取记录 JSON。 */
            override fun read(key: String): String? = prefs.getString(key, null)
            /** 异步写回记录，不阻塞调用线程。 */
            override fun write(key: String, value: String) {
                prefs.edit().putString(key, value).apply()
            }
        })
    }

    /** 导出给脚本的 KernelSU 环境信息，版本号取自 Manager 与内核。 */
    fun hostInfo(): ScriptHostInfo = ScriptHostInfo(
        // Same value as ksud's VERSION_NAME (git describe without the leading "v").
        ksuVersion = BuildConfig.VERSION_NAME.removePrefix("v"),
        ksuVersionCode = BuildConfig.VERSION_CODE,
        kernelVersionCode = Natives.version,
        lateLoad = Natives.isLateLoadMode,
    )

    /** module.prop of [uri] if it is a one-shot script ZIP, otherwise (or when unreadable) null. */
    suspend fun peekOneshot(uri: Uri): ScriptManifest? = withContext(Dispatchers.IO) {
        runCatching {
            ksuApp.contentResolver.openInputStream(uri)?.use { ScriptPackage.readOneshotManifest(it) }
        }.getOrNull()
    }

    /**
     * Copies, verifies, validates and extracts the ZIP at [uri]. With [registryId] the ZIP must
     * be a verified registry download (re-hashed here like a module install) whose module.prop
     * id equals [registryId]. The extraction belongs to [owner], a token unique to this
     * preparation, until [release]; releasing it earlier (even before this returns) drops it.
     */
    suspend fun prepare(uri: Uri, registryId: String?, owner: String): PreparedScript = withContext(Dispatchers.IO) {
        try {
            prepareLock.withLock { prepareLocked(uri, registryId, owner) }
        } finally {
            // The token is never reused: forget an early release that found nothing to drop.
            synchronized(extractions) { released.remove(owner) }
        }
    }

    /** 串行执行的实际准备工作：校验来源、下载、解压并登记到 [extractions]。 */
    private fun prepareLocked(uri: Uri, registryId: String?, owner: String): PreparedScript {
        val storeRoot = File(ksuApp.filesDir, RegistryDownloadStore.DIR_NAME)
        // 只有来自下载仓库的 file:// URI 才携带可信的期望大小/哈希。
        val expected = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { RegistryDownloadStore.expectedFor(storeRoot, File(it)) }
        } else {
            null
        }
        if (registryId != null && expected == null) throw UnverifiedScriptException()

        // 临时 ZIP 放在 cacheDir，准备完成后删除。
        val zip = File.createTempFile("oneshot-", ".zip", ksuApp.cacheDir)
        try {
            try {
                val input = ksuApp.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                // A local source has no verified size and may never end.
                input.use { copyForInstall(if (expected == null) ScriptPackage.bounded(it, ScriptPackage.MAX_ZIP_BYTES) else it, zip, expected) }
            } catch (e: DownloadVerificationException) {
                // Corrupted or tampered after download: never keep it around for a retry.
                uri.path?.let { RegistryDownloadStore.delete(storeRoot, File(it)) }
                throw e
            }
            val manifest = ScriptPackage.validate(zip, registryId)
            val sha256 = sha256(zip)
            // One directory per preparation: the same ZIP may be open on two run pages at once.
            val dir = File(root(), "$sha256-${UUID.randomUUID()}")
            synchronized(extractions) {
                // 页面可能在解压前就退出，此时直接放弃并清理。
                if (released.remove(owner)) throw ReleasedException()
                extractions[owner] = dir
            }
            cleanup()
            try {
                ScriptPackage.extract(zip, dir)
                val prepared = readPrepared(manifest, dir, sha256)
                // Released while extracting: release() may have run before the directory existed.
                if (synchronized(extractions) { extractions[owner] != dir }) throw ReleasedException()
                return prepared
            } catch (e: Throwable) {
                release(owner)
                delete(dir)
                throw e
            }
        } finally {
            zip.delete()
        }
    }

    /** 读取解压后的 params.json 与 action.sh；源码只保留上限内的字符。 */
    private fun readPrepared(manifest: ScriptManifest, dir: File, sha256: String): PreparedScript {
        val paramsFile = File(dir, ScriptPackage.PARAMS)
        val params = if (paramsFile.isFile) {
            ScriptPackage.parseParams(paramsFile.readText())
        } else {
            ScriptParams.EMPTY
        }
        // Read at most one char past the limit: action.sh itself may be up to the ZIP size cap.
        val source = File(dir, ScriptPackage.ACTION).bufferedReader().use { reader ->
            val buf = CharArray(MAX_SOURCE_CHARS + 1)
            var n = 0
            while (n < buf.size) {
                val read = reader.read(buf, n, buf.size - n)
                if (read == -1) break
                n += read
            }
            String(buf, 0, n)
        }
        return PreparedScript(
            manifest = manifest,
            dir = dir,
            sha256 = sha256,
            params = params,
            actionSource = source.take(MAX_SOURCE_CHARS),
            actionSourceTruncated = source.length > MAX_SOURCE_CHARS,
        )
    }

    /**
     * Drops the extraction of [owner] (page left, or the preparation failed). Not for a running
     * script: the run removes its directory as root, since the script may create root-owned files.
     */
    fun release(owner: String) {
        val dir = synchronized(extractions) {
            extractions.remove(owner) ?: run { released.add(owner); null }
        } ?: return
        ioScope.launch { delete(dir) }
    }

    /** 每次运行计数，供 UI 观察并刷新“上次运行”状态。 */
    private val _runCount = MutableStateFlow(0)

    /** Bumped after every recorded run, so screens showing the last run can refresh. */
    val runCount: StateFlow<Int> = _runCount.asStateFlow()

    /** 记录一次运行结果（id/versionCode/退出码/时间），并通知观察者。 */
    fun recordRun(prepared: PreparedScript, exitCode: Int) {
        history.record(
            ScriptRunRecord(
                id = prepared.manifest.id,
                versionCode = prepared.manifest.versionCode,
                time = System.currentTimeMillis(),
                exitCode = exitCode,
            )
        )
        _runCount.update { it + 1 }
    }

    /** Drops extractions no page holds, e.g. left behind by a killed process. */
    private fun cleanup() {
        // 只保留当前 owner 登记的目录，其余都是崩溃残留。
        val held = synchronized(extractions) { extractions.values.mapTo(HashSet()) { it.name } }
        root().listFiles()?.forEach { f -> if (f.name !in held) delete(f) }
    }

    /**
     * Deletes [dir] without following symlinks (a script may have linked to anywhere), and as
     * root when the app cannot (root-owned files left by a run whose own cleanup failed).
     */
    @OptIn(ExperimentalPathApi::class)
    private fun delete(dir: File) {
        val path = dir.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        try {
            path.deleteRecursively()
        } catch (e: IOException) {
            // 脚本可能以 root 身份创建文件，普通删除失败时回退到 root 删除。
            Log.w(TAG, "cannot delete $dir, retrying as root", e)
            runCatching { removeAsRoot(dir.absolutePath) }
                .onSuccess { removed -> if (!removed) Log.w(TAG, "cannot remove $dir as root") }
                .onFailure { Log.w(TAG, "cannot remove $dir as root", it) }
        }
    }

    /** 计算文件的 SHA-256 十六进制串。 */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
