package me.weishu.kernelsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.core.tasks.BootKernelVersion
import me.weishu.kernelsu.core.tasks.ExtractImage
import me.weishu.kernelsu.core.tasks.ProbeResult
import me.weishu.kernelsu.core.utils.DataSourceChannel
import me.weishu.kernelsu.data.registry.DownloadVerificationException
import me.weishu.kernelsu.data.registry.RegistryDownloadStore
import me.weishu.kernelsu.data.registry.copyForInstall
import me.weishu.kernelsu.ksuApp
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * @author weishu
 * @date 2023/1/1.
 *
 * 说明：本文件封装所有通过 root shell 调用 ksud 的操作。命令参数来自 UI 或
 * 外部 URI 时必须先校验/转义，避免命令注入。
 */
private const val TAG = "KsuCli"

// ksud 以 libksud.so 的形式随 APK 打包在 nativeLibraryDir，可直接执行。
private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

// Single-quote a value for safe interpolation
// 说明：用单引号包裹并把内部单引号转义为 '\''，防止 shell 注入。
private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

/** 一次刷写/安装操作的结果；[showReboot] 表示成功后是否需要提示重启。 */
data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

/**
 * 全局复用的 root shell。
 *
 * 普通命名空间与 global mount 命名空间各保持一个长驻 shell；前者用于查询，
 * 后者让模块挂载可见，适合执行 ksud 的安装/操作命令。
 */
object KsuCli {
    val SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

/** 获取长驻 root shell；[globalMnt] 为 true 时返回全局挂载命名空间的实例。 */
fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

/**
 * 创建一次性 root shell 并执行 [block]，结束后自动关闭。
 *
 * 适合需要独立环境的刷写/脚本操作，避免污染长驻 shell 的 cwd 与环境变量。
 */
inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

/** 通过 ContentResolver 查询 URI 的显示文件名；查询失败返回 null。 */
fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

/**
 * 创建 root shell，按可用性依次降级。
 *
 * 优先使用随包提供的 ksud `su`（可保证与内核版本匹配），失败后退回系统
 * `su`/`su -mm`，最终回退到普通 `sh`。返回值可能是非 root shell，调用方
 * 需通过 [Shell.isRoot] 判断实际权限。
 */
fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        // 通过 ksud 的 su 入口进入 root；-g 表示加入全局挂载命名空间。
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            // 回退到系统 su；-mm 对应 Magisk 的全局挂载模式。
            if (globalMnt) {
                builder.build("su", "-mm")
            } else {
                builder.build("su")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            // 完全无 root 时仍返回可用 shell，避免直接崩溃。
            builder.build("sh")
        }
    }
}

/** 用 root shell 执行单条 ksud 命令，返回是否成功（退出码为 0）。 */
fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(globalMnt), "${getKsuDaemonPath()} $args")
    }
}

/** 查询某个内核特性是否可用，返回 ksud 的原始状态字符串（如 supported/unsupported）。 */
suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

/** 读取持久化配置中的特性值（--config），与内核当前值区分。 */
suspend fun getFeaturePersistValue(feature: String): Long? = readFeatureValue("--config $feature")

// Current value in kernel
/** 读取内核中当前生效的特性值。 */
suspend fun getFeatureValue(feature: String): Long? = readFeatureValue(feature)

// 从 `feature get` 的输出中解析 "Value:" 行；格式不符时返回 null。
private suspend fun readFeatureValue(args: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get $args").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

/**
 * 把当前 APK 内的 ksud 安装/更新到系统可执行位置。
 *
 * [BuildConfig] 关闭时只在刷写成功后调用；libadbroot 与设备保护数据目录
 * 必须显式传入，ksud 才能正确放置 adb root 组件。
 */
fun install() {
    val start = SystemClock.elapsedRealtime()
    // 随包提供的 adb root 库路径，ksud 安装时需要复制。
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot --data-path ${ksuApp.applicationInfo.deviceProtectedDataDir}", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

/** 调用 ksud 列出已安装模块，返回 JSON 数组字符串；无模块时返回 "[]"。 */
fun listModules(): String {
    val shell = getRootShell()

    val out = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

/** 统计已安装模块数量；JSON 解析失败时返回 0。 */
fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

/** 已授权 root 的应用数量，直接读内核 allowlist。 */
fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

/**
 * 启用/禁用模块。
 *
 * 使用一次性 shell 调用 ksud，让挂载状态立即按新配置重建。
 */
fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

/** 撤销“待卸载”标记，模块重启后仍会保留。 */
fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

/** 卸载模块（实际由 ksud 在重启后完成清理）。 */
fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

/**
 * 在新 root shell 中执行耗时命令，并把 stdout/stderr 逐行回调给 UI。
 *
 * 使用独立 shell 是为了不阻塞长驻 shell，同时保证命令在全局挂载命名空间中执行。
 */
private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stdout；空行也转发，保持原始输出顺序。
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stderr，供 UI 显示错误详情。
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

/**
 * 安装模块 ZIP。
 *
 * 先复制到应用缓存（ksud 需要可读的本地路径），仓库下载还要按 expected
 * 再做一次完整性校验；校验失败会删除源文件并拒绝安装。
 */
fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    // Registry downloads live in app-private storage and carry their expected size + SHA-256;
    // the exact copy handed to ksud is re-hashed right before installing.
    val storeRoot = File(ksuApp.filesDir, RegistryDownloadStore.DIR_NAME)
    val expected = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        uri.path?.let { RegistryDownloadStore.expectedFor(storeRoot, File(it)) }
    } else {
        null
    }
    // 缓存副本供 ksud 读取，结束后无论成败都删除。
    val file = File.createTempFile("module-", ".zip", ksuApp.cacheDir)
    try {
        try {
            val input = ksuApp.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            input.use { copyForInstall(it, file, expected) }
        } catch (e: IOException) {
            Log.w("KernelSU", "module $uri not installed: ${e.message}")
            if (e is DownloadVerificationException) {
                // Corrupted or tampered after download: never keep it around for a retry.
                uri.path?.let { RegistryDownloadStore.delete(storeRoot, File(it)) }
            }
            val message = "! ${e.message ?: "Failed to read module"}"
            onStderr(message)
            return FlashResult(1, message, false)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")
        return FlashResult(result, result.isSuccess && moduleNeedsReboot(file))
    } finally {
        file.delete()
    }
}

// 模块 id 会拼进 shell 命令与文件路径，必须严格限制字符集以防注入/路径穿越。
private val MODULE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

// A hot installed module goes live without leaving the "update" flag behind
/**
 * 判断模块安装后是否需要重启。
 *
 * 若模块目录已存在且没有 update 标记，说明热安装已生效；任何读取/检查失败
 * 都保守地返回 true，提示用户重启以确保生效。
 */
private fun moduleNeedsReboot(zip: File): Boolean {
    val id = runCatching {
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("module.prop") ?: return@use null
            archive.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.trim()
            }
        }
    }.getOrNull()
    if (id == null || !MODULE_ID_REGEX.matches(id)) return true
    val dir = "/data/adb/modules/$id"
    // Any failure to check counts as needing a reboot
    return !ShellUtils.fastCmdResult(getRootShell(), "[ -d $dir ] && [ ! -e $dir/update ]")
}

/**
 * 执行模块的 action.sh。
 *
 * 先在本地校验模块 id，再在全局挂载命名空间的新 root shell 中运行，
 * 让脚本能看到模块挂载后的文件系统。
 */
fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    if (!MODULE_ID_REGEX.matches(moduleId)) {
        Log.e(TAG, "runModuleAction: rejecting invalid module id: $moduleId")
        return false
    }
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stdout；空行也转发，保持原始输出顺序。
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stderr，供 UI 显示错误详情。
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action '$moduleId'")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result.isSuccess
}

/**
 * Runs a prepared one-shot script command (see ScriptCommand) in a new root shell in the
 * global mount namespace, like a module action. Returns the exit code.
 *
 * 说明：脚本内容由调用方预先拼好，退出码原样返回，调用方据此展示结果。
 */
fun runOneshotScript(
    command: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Int {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stdout；空行也转发，保持原始输出顺序。
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stderr，供 UI 显示错误详情。
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add(command).to(stdoutCallback, stderrCallback).exec()
    }
    Log.i(TAG, "one-shot script exit code: ${result.code}")
    return result.code
}

/** Removes a script's extraction directory, including files the script created as root. */
// 说明：脚本可能以 root 创建文件，普通 File.delete 删不掉，必须走 root shell；
// 路径经过 shellQuote 转义，防止包含空格或引号时出错。
fun removeAsRoot(path: String): Boolean {
    return ShellUtils.fastCmdResult(getRootShell(), "rm -rf ${path.shellQuote()}")
}

/** 用已备份的镜像恢复 boot；-f 表示直接刷写。 */
fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

/** 永久卸载 KernelSU；传入包名让 ksud 同时清理 Manager。 */
fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

/**
 * LKM（可加载内核模块）的选择方式。
 *
 * - [LkmUri]：用户提供 .ko 文件；
 * - [KmiString]：指定 KMI 由 ksud 选择内置模块；
 * - [KmiNone]：不加载 LKM，由 ksud 根据 boot 镜像自动探测 KMI。
 */
@Parcelize
sealed class LkmSelection : Parcelable {
    // 用户提供的 .ko 文件。
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    // 用户手动指定的 KMI 字符串。
    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    // 不加载 LKM，由 ksud 自动探测。
    @Parcelize
    data object KmiNone : LkmSelection()
}

// 把用户选择的 .ko 复制到缓存，返回路径；非 URI 选择返回 null。
private fun writeLkmFile(lkm: LkmSelection): File? {
    if (lkm !is LkmSelection.LkmUri) return null
    val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
    ksuApp.contentResolver.openInputStream(lkm.uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    }
    return file
}

// 把刷写选项映射为 ksud 命令行参数；这些开关会改变 root/ADB 行为，必须显式传递。
private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
}

/**
 * 安装/修补 boot 镜像。
 *
 * [bootUri] 为空表示直接安装（使用设备当前 boot）；否则把用户选择的镜像复制
 * 到缓存后交给 ksud。OTA 模式、分区、LKM 与安全选项都会转成 CLI 参数。
 */
fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    // 缓存的 boot 镜像副本，刷写结束后删除。
    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    // 逐段拼接 ksud 参数，所有路径都由本进程生成，避免外部注入。
    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    if (ota) {
        cmd += " -u"
    }

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }

    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

/**
 * 从远程 URL 下载 boot 镜像并修补。
 *
 * 该镜像与本机无关，KMI 必须从镜像本身探测，不能让 ksud 使用本机内核版本；
 * 同时需要显式指定分区与输出目录（root shell 的 cwd 通常是只读的）。
 */
fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    // 远程镜像先落到缓存，再交给 ksud 修补。
    val bootFile = File(ksuApp.cacheDir, "download-boot.img")
    var probedKmi: String? = null
    try {
        onStdout("- Downloading and extracting boot image")
        val channel = DataSourceChannel(newDownloadClient(), url)
        // CrAU 头表示 payload 格式（OTA），否则按普通 boot 镜像解析。
        val magic = readMagic(channel)
        val image = ExtractImage(bootFile, onStdout)
        // Extract the KMI here while the payload is open. ZipFile closes the
        // channel it is built on, so probe on a separate channel.
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        if (magic == "CrAU") {
            image.consumePayload(channel, partition)
        } else {
            image.consume(channel, partition)
        }
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, e.message ?: "Download failed", false)
    }

    // init_boot/vendor_boot carry no kernel, so their KMI comes from the
    // payload's boot probe and must be passed explicitly. A remote download
    // is unrelated to this device, so ksud must not use the local kernel.
    val autoKmi = if (lkm is LkmSelection.KmiNone) {
        (probedKmi ?: BootKernelVersion.parseKmiFromBoot(bootFile))?.also {
            onStdout("- Auto detected KMI: $it")
        }
    } else {
        null
    }
    if (autoKmi == null && lkm is LkmSelection.KmiNone) {
        bootFile.delete()
        return FlashResult(-1, "Failed to determine KMI from the package", false)
    }

    // 远程镜像必须显式指定 KMI 与分区，避免 ksud 误用本机信息。
    var cmd = "${getKsuDaemonPath()} boot-patch -b ${bootFile.absolutePath}"
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup)

    val lkmFile = writeLkmFile(lkm)
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }
    if (autoKmi != null) cmd += " --kmi $autoKmi"
    cmd += " --partition $partition"
    // ksud defaults to cwd, which is read-only in the su session; use Downloads.
    val downloadsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cmd += " -o $downloadsDir"

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stdout；空行也转发，保持原始输出顺序。
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }
    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        // 回调每行 stderr，供 UI 显示错误详情。
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    // 注意：这里使用全局 Shell 而非临时 shell，保持与既有刷写流程一致。
    val result = Shell.getShell().newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    lkmFile?.delete()
    bootFile.delete()
    return FlashResult(result, false)
}

/** 只探测远程镜像包含哪些分区，不下载完整内容，也不解析 KMI。 */
suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "probe start: $url")
    val channel = DataSourceChannel(newDownloadClient(), url)
    Log.d(TAG, "probe connected, size=${channel.size()}")
    val magic = readMagic(channel)
    Log.d(TAG, "probe magic: $magic")
    // Only list the partitions here; the KMI is extracted later when the
    // payload is downloaded for patching.
    val result = if (magic == "CrAU") {
        ExtractImage.probePayload(channel, withKmi = false)
    } else {
        ExtractImage.probe(channel, withKmi = false)
    }
    Log.d(TAG, "probe partitions: ${result.partitions}")
    result
}

// 下载/探测用独立客户端：超时较短，避免弱网时长时间卡住 UI。
private fun newDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

// 读取文件头 4 字节并复位通道位置；用 ISO-8859-1 保证字节到字符一一对应。
private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

/**
 * 通过 root shell 重启设备。
 *
 * recovery 需要先发送电源键事件以绕过系统确认弹窗；soft_reboot 交给 ksud
 * 处理，其余原因直接调用 svc/reboot。命令失败时保留系统默认行为。
 */
fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true, true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

/** 当前 shell 是否真正拿到 root（而不是降级后的普通 sh）。 */
fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

/** 查询设备当前内核的 KMI 版本（如 android12-5.10）。 */
suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

/** 列出当前 KernelSU 构建支持的所有 KMI。 */
suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

/** 判断设备是否为 A/B 分区布局；影响槽位与分区选择。 */
suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

/**
 * 获取默认 boot 分区名。
 *
 * 有 root 时向 ksud 查询；无 root 时按内核版本粗略推断（Android 12+ 的
 * GKI 设备通常使用 init_boot），仅作为展示用默认值。
 */
suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

/** 获取当前槽位后缀（_a/_b）；[ota] 为 true 时返回 OTA 目标槽位。 */
suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

/** 列出设备上可刷写的分区，供用户选择。 */
suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

// 检测是否安装 Magisk：两者共存时需要特殊处理模块挂载。
fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

/**
 * 校验用户输入的 sepolicy 规则是否可被 ksud 接受。
 *
 * 规则文本经 shellQuote 转义后传入，null 视为合法（表示不设置规则）。
 */
fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check ${rules.shellQuote()}").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

// 读取包名对应的 sepolicy 规则；包名不来自可信输入，必须转义。
fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy ${pkg.shellQuote()}").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

// 写入 sepolicy 规则；包名与规则都经过 shellQuote，避免命令注入。
fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy ${pkg.shellQuote()} ${rules.shellQuote()}")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

/** 列出 kv 中保存的 app profile 模板名。 */
fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

/** 读取指定模板的完整内容；id 会拼进命令，因此需要转义。 */
fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template ${id.shellQuote()}")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

/** 保存或覆盖 app profile 模板。 */
fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val cmd = """${getKsuDaemonPath()} profile set-template ${id.shellQuote()} ${template.shellQuote()}"""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

/** 删除指定 app profile 模板。 */
fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template ${id.shellQuote()}")
        .to(ArrayList(), null).exec().isSuccess
}

/** 通过 root 强制停止应用；可指定 [userId] 以操作工作资料中的应用。 */
fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    // 只有明确指定用户时才附加 --user，避免误伤其他用户。
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

/**
 * 通过 root 启动应用。
 *
 * 先用 resolve-activity 找到入口 Activity，再交给 start-activity，
 * 这样无需知道具体 Activity 名。
 */
fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

/** 重启应用：先强制停止再重新拉起。 */
fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
