package me.weishu.kernelsu.ui.screen.modulerepo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.RepoRelease
import me.weishu.kernelsu.data.registry.DownloadVerificationException
import me.weishu.kernelsu.data.registry.ExpectedFile
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.data.script.ScriptRepository
import me.weishu.kernelsu.data.script.ScriptRunRecord
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.util.DownloadManager
import me.weishu.kernelsu.ui.util.download
import me.weishu.kernelsu.ui.viewmodel.ModuleRepoDetailViewModel
import me.weishu.kernelsu.ui.viewmodel.ModuleRepoViewModel
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 仓库列表页入口：合并仓库数据与本地已装版本，再按主题分发到两套 UI。 */
@Composable
fun ModuleRepoScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<ModuleRepoViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installedVm = viewModel<ModuleViewModel>()
    val installedUiState by installedVm.uiState.collectAsStateWithLifecycle()

    // 只在首次进入时拉取，避免每次重组重复请求；ViewModel 负责后续缓存与刷新
    LaunchedEffect(Unit) {
        if (uiState.modules.isEmpty()) {
            viewModel.refresh()
        }
        if (installedUiState.moduleList.isEmpty()) {
            installedVm.fetchModuleList()
        }
    }

    // 只依赖已装模块，版本号变化时才重新构建映射，减少无谓重组
    val installedVersions = remember(installedUiState.modules) {
        installedUiState.modules.associate { it.id to it.versionCode }
    }
    val state = remember(uiState, installedVersions) { uiState.copy(installedVersions = installedVersions) }

    val actions = ModuleRepoActions(
        onBack = { navigator.pop() },
        onRefresh = viewModel::refresh,
        onSearchTextChange = viewModel::updateSearchText,
        onClearSearch = { viewModel.updateSearchText("") },
        onSearchStatusChange = viewModel::updateSearchStatus,
        onSetSortOrder = viewModel::setSortOrder,
        onOpenRepoDetail = { module ->
            // 详情页需要 Parcelable 参数，这里把列表项拍平成 RepoModuleArg
            val args = RepoModuleArg(
                moduleId = module.moduleId,
                moduleName = module.moduleName,
                authors = module.authors,
                authorsList = module.authorList.map { AuthorArg(it.name, it.link) },
                latestVersion = module.latestVersion,
                latestVersionCode = module.latestVersionCode,
                latestReleaseTime = module.latestReleaseTime,
                metamodule = module.metamodule,
                script = module.isScript,
            )
            navigator.push(Route.ModuleRepoDetail(args))
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ModuleRepoScreenMiuix(state, actions)
        UiMode.Material -> ModuleRepoScreenMaterial(state, actions)
    }
}

/** 仓库详情页：加载详情、对比已装版本，并处理下载后的安装/运行分流。 */
@Composable
fun ModuleRepoDetailScreen(module: RepoModuleArg) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    // 以模块 id 作为 ViewModel key，保证不同模块详情互不复用状态
    val detailVm = viewModel<ModuleRepoDetailViewModel>(key = "module-repo-detail-${module.moduleId}")
    val detailState by detailVm.state.collectAsStateWithLifecycle()
    val installedVm = viewModel<ModuleViewModel>()
    val installedUiState by installedVm.uiState.collectAsStateWithLifecycle()

    // 切换到其他模块时重新加载详情；已装列表为空则补拉一次用于版本对比
    LaunchedEffect(module.moduleId) {
        if (installedUiState.modules.isEmpty()) {
            installedVm.fetchModuleList()
        }
        detailVm.load(module.moduleId)
    }

    // Scripts are never installed: an installed module of the same id is unrelated.
    val installedVersionCode = if (detailState.detail?.module?.isScript ?: module.script) {
        null
    } else {
        installedUiState.modules.firstOrNull { it.id == module.moduleId }?.versionCode
    }
    val runCount by ScriptRepository.runCount.collectAsStateWithLifecycle()
    // SharedPreferences load from disk on first use: keep it off the main thread.
    val lastRun by produceState<ScriptRunRecord?>(null, module.moduleId, runCount) {
        value = withContext(Dispatchers.IO) { ScriptRepository.history.last(module.moduleId) }
    }
    val state = ModuleRepoDetailUiState(
        module = module,
        lastRun = lastRun,
        detail = detailState.detail,
        loaded = detailState.loaded,
        notInIndex = detailState.notInIndex,
        loadFailed = detailState.loadFailed,
        installedVersionCode = installedVersionCode,
    )
    // Only web links leave the app; registry documents are validated, this is defence in depth.
    val openUrl: (String) -> Unit = { url -> if (RegistryUrls.isWebUrl(url)) uriHandler.openUri(url) }
    val actions = ModuleRepoDetailActions(
        onBack = { navigator.pop() },
        onOpenWebUrl = { state.webUrl.takeIf { it.isNotEmpty() }?.let(openUrl) },
        onOpenUrl = openUrl,
        onInstallModule = { uri ->
            // 脚本走运行页；普通模块进入刷写流程，由刷写页负责最终安装与热安装判断
            if (state.isScript) {
                val name = state.detail?.module?.moduleName ?: module.moduleName
                navigator.push(Route.ScriptRun(ScriptRunArg(uri, registryId = module.moduleId, displayName = name)))
            } else {
                navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
            }
        },
        onRetry = { detailVm.load(module.moduleId, force = true) },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ModuleRepoDetailScreenMiuix(state, actions)
        UiMode.Material -> ModuleRepoDetailScreenMaterial(state, actions)
    }
}

/** UI state of downloading one release ZIP. */
@Stable
class ReleaseDownloadHandle(private val sha256: String, downloadedUri: Uri?) {
    var isDownloading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var downloadedUri by mutableStateOf(downloadedUri)
        private set

    /** 记录下载结果；校验通过的 uri 会写入进程级缓存，供返回详情页后直接安装。 */
    fun setDownloaded(uri: Uri?) {
        downloadedUri = uri
        if (uri != null) verifiedDownloads[sha256] = uri else verifiedDownloads.remove(sha256)
    }
}

/**
 * Verified release ZIPs of this process, keyed by SHA-256, so the Install / Run button survives
 * leaving and reopening the detail page. Callers still check [isDownloadAvailable] before use.
 */
private val verifiedDownloads = ConcurrentHashMap<String, Uri>()

/** 按 zipUrl 与 sha256 记住下载句柄；同一发布版本复用时保留已下载结果。 */
@Composable
fun rememberReleaseDownload(release: RepoRelease): ReleaseDownloadHandle =
    remember(release.zipUrl, release.sha256) {
        ReleaseDownloadHandle(release.sha256, verifiedDownloads[release.sha256])
    }

/**
 * Downloads a release ZIP. The download service only accepts GitHub Release URLs (and their CDN
 * redirect targets) and checks size + SHA-256 before the file is published; on mismatch the
 * file is deleted and an error is shown, so [ReleaseDownloadHandle.downloadedUri] is only set
 * for verified files.
 */
fun CoroutineScope.startReleaseDownload(context: Context, release: RepoRelease, handle: ReleaseDownloadHandle) {
    handle.isDownloading = true
    launch(Dispatchers.IO) {
        download(
            url = release.zipUrl,
            fileName = release.zipName,
            onDownloaded = { uri ->
                handle.isDownloading = false
                handle.setDownloaded(uri)
            },
            onDownloading = { handle.isDownloading = true },
            // 进度回调切回主线程更新状态，避免跨线程写 Compose 状态
            onProgress = { p -> launch(Dispatchers.Main) { handle.progress = p } },
            expected = ExpectedFile(size = release.zipSize, sha256 = release.sha256),
            onFailed = { failed ->
                Toast.makeText(context, downloadFailureMessage(context, failed, release.zipName), Toast.LENGTH_LONG).show()
            },
        )
        handle.isDownloading = false
    }
}

/** 把下载失败原因翻译成用户可读文案；校验失败需明确指出文件名。 */
fun downloadFailureMessage(context: Context, state: DownloadManager.DownloadState, fileName: String): String =
    when (state.verificationFailure) {
        DownloadVerificationException.Reason.URL_NOT_ALLOWED -> context.getString(R.string.module_repo_download_url_blocked)
        DownloadVerificationException.Reason.SIZE_MISMATCH,
        DownloadVerificationException.Reason.SHA256_MISMATCH -> context.getString(R.string.module_repo_download_verify_failed, fileName)

        null -> context.getString(R.string.download_failed_content, fileName)
    }

/** 以 1024 进制格式化文件大小；固定 Locale.ROOT，避免小数点随语言变化。 */
fun formatFileSize(size: Long): String = when {
    size >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", size / (1024f * 1024f * 1024f))
    size >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", size / (1024f * 1024f))
    size >= 1024L -> String.format(Locale.ROOT, "%.0f KB", size / 1024f)
    else -> "$size B"
}

/** "abcdef12…34567890" for compact display; the full hash is still selectable in the info text. */
fun shortHash(hash: String, head: Int = 12, tail: Int = 8): String =
    if (hash.length <= head + tail + 1) hash else hash.take(head) + "…" + hash.takeLast(tail)

// 图标下载上限与解码尺寸限制，防止异常资源占用过多内存
private const val MAX_ICON_BYTES = 512L * 1024
/** 图标解码后的目标边长（像素），用于限制列表图标的内存占用。 */
private const val ICON_DECODE_PX = 192
// 进程级图标缓存，key 为内容寻址的 registry 资源地址
private val iconCache = LruCache<String, ImageBitmap>(128)

/** 只允许访问 registry 源、且不跟随重定向的 OkHttp 客户端，避免图标下载被劫持。 */
private val iconClient by lazy {
    // Icons are content-hashed registry assets: no redirects, registry origin only.
    ksuApp.okhttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
}

/** 校验并下载 registry 图标；失败时返回 null，由调用方选择不渲染图标。 */
private fun loadRegistryIcon(url: String): ImageBitmap? {
    if (!RegistryUrls.isAllowedRestrictedResource(url, RegistryUrls.DEFAULT_BASE_URL)) return null
    return runCatching {
        iconClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val source = resp.body.source()
            // 先探测是否超限，避免把超大响应体读入内存
            if (source.request(MAX_ICON_BYTES + 1)) return null
            val bytes = source.readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            // Bound the larger side so very wide or tall images are downsampled too.
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_DECODE_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }
    }.getOrNull()?.also { iconCache.put(url, it) }
}

/** Module icon from the registry (raster formats only); renders nothing until/unless it loads. */
@Composable
fun RepoModuleIcon(url: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (url == null) return
    // 先读缓存；未命中时在 IO 线程拉取，url 变化会取消旧请求
    val bitmap by produceState(iconCache.get(url), url) {
        if (value == null) value = withContext(Dispatchers.IO) { loadRegistryIcon(url) }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}
