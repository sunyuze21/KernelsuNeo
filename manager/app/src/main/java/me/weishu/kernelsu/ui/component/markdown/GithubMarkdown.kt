package me.weishu.kernelsu.ui.component.markdown

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import me.weishu.kernelsu.data.registry.RegistryUrls
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.adjustLightnessArgb
import me.weishu.kernelsu.ui.util.cssColorFromArgb
import me.weishu.kernelsu.ui.util.ensureVisibleByMix
import me.weishu.kernelsu.ui.util.relativeLuminance
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs

// 受限模式下把应用内资源映射到这个虚拟源；同时它也充当普通模式文档的基准 URL
private const val APP_ASSETS_ORIGIN = "https://appassets.androidplatform.net"
// content-type 头的解析规则：先按分号取 MIME，再按等号取 charset，容忍多余空格
private val SEMICOLON_SPLIT = ";\\s*".toRegex()
// charset 段的等号拆分规则，与 SEMICOLON_SPLIT 配套使用
private val EQUALS_SPLIT = "=\\s*".toRegex()

/**
 * 基于 WebView 的 Markdown 渲染组件。
 *
 * 普通模式开启 JavaScript 与 DOM 存储，并注册 AndroidScroll 桥接，用于让
 * 宽表格/代码块的横向滚动与父级手势协同；受限模式（传入 [restrictedBaseUrl]）
 * 则关闭 JS/存储、剥离 `<script>`、只放行同源子资源，且拦截页面内导航，
 * 专门渲染来自模块仓库的不可信 README/更新日志。
 *
 * 解析与 HTML 渲染在组合阶段完成并 remember 缓存；加载到 WebView 时用 html
 * 字符串作为 tag 去重，因此滚动、主题无关的重组不会触发重复 load，
 * 只有内容或配色真正变化时才重新加载页面。
 *
 * @param content Markdown 源文本或已渲染好的 HTML
 * @param isMarkdown 为 true 时走 commonmark 解析，否则把 content 当作 HTML
 * @param onLoadingChange 页面开始加载/可见时回调，用于驱动外层淡入
 * @param containerColor Miuix 模式下的内容底色，null 表示取主题默认
 */
@SuppressLint("JavascriptInterface", "SetJavaScriptEnabled", "WrongConstant")
@Composable
fun GithubMarkdown(
    modifier: Modifier = Modifier,
    content: String,
    isMarkdown: Boolean = false,
    onLoadingChange: (Boolean) -> Unit = {},
    containerColor: androidx.compose.ui.graphics.Color? = null,
    /**
     * Site root of untrusted, already sanitized remote HTML (e.g. KernelSU Modules readmes and
     * changelogs). When set, the WebView runs locked down: JavaScript and DOM storage are off,
     * no JS bridge, no file/content access, no mixed content, sub-resources only from this
     * site's host (plus bundled assets), relative URLs resolve against it, raw HTML in Markdown
     * is escaped, nothing is ever loaded in place, and only web links the user tapped are
     * handed to an external browser.
     */
    restrictedBaseUrl: String? = null,
) {
    val restricted = restrictedBaseUrl != null
    // 受限客户端按来源缓存，避免每次组合都重建 OkHttp 连接池与拦截器链
    val restrictedClient = remember(restrictedBaseUrl) { restrictedBaseUrl?.let(::restrictedResourceClient) }
    val density = LocalDensity.current
    val systemDensity = LocalResources.current.displayMetrics.density
    val fontScale = density.fontScale
    val pageScale = density.density / systemDensity
    // WebView 的 textZoom 以系统密度为基准，这里按 Compose 密度/用户字体缩放补偿，
    // 否则在大屏或改了字体大小的设备上，Markdown 字号会与周围 Compose 文本明显不一致
    val newTextZoom = (90 * pageScale * fontScale).toInt()
    // JS 桥接对象需跨重组稳定，否则 onPageFinished 注入的脚本会指向旧实例
    val scrollInterface = remember { MarkdownScrollInterface() }

    val isDark = isInDarkTheme()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "rtl" else "ltr"

    // 代码块/表格隔行底色由主题色派生，保证两种 UI 模式下都有足够对比度
    val colors = getMarkdownColors(containerColor)
    val bgCode = colors.bgCode
    val bgRowAlt = colors.bgRowAlt
    val fgDefault = colors.fgDefault
    val fgLink = colors.fgLink

    // 模板只在明暗主题切换时重新读取，避免每次重组都做 assets IO
    val template = remember(isDark) {
        val name = if (isDark) "webview/template_dark.html" else "webview/template.html"
        ksuApp.assets.open(name).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
    // commonmark 扩展对象无状态，只构建一次；parser/renderer 也随之复用
    val extensions = remember {
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.builder().requireTwoTildes(true).build(),
            AutolinkExtension.create(),
            TaskListItemsExtension.create(),
        )
    }
    val parser = remember(extensions) { Parser.builder().extensions(extensions).build() }
    // 受限模式额外开启 URL 清洗与 HTML 转义，防止不可信 README 注入脚本/外链
    val renderer = remember(extensions, restricted) {
        HtmlRenderer.builder().extensions(extensions).sanitizeUrls(restricted).escapeHtml(restricted).build()
    }
    // 解析结果按内容和模式缓存：Markdown 解析是纯 CPU 操作，不应随滚动/重组重复执行
    val rendered = remember(content, isMarkdown) {
        if (isMarkdown) renderer.render(parser.parse(content)) else content
    }
    // 把 Compose 主题色写进 CSS 变量；背景透明，让外层容器的底色/模糊效果透出
    val styleContent = """
        :root {
            --background: ${Color.TRANSPARENT};
            --pre-background: $bgCode;
            --code-background: $bgCode;
            --tr-alt-background: $bgRowAlt;
            --thead-background: $bgRowAlt;
            --textPrimary: $fgDefault;
            --link: $fgLink;
        }
        html, body { margin: 0; padding: 0 }
        img, video { max-width: 100%; height: auto; }
        .markdown-body { padding: 16px; }
        .markdown-body table { display: block; max-width: 100%; overflow-x: auto; }
        .markdown-body pre { overflow-x: auto; }
    """.trimIndent()
    val html = template
        .let { t ->
            // 受限模式需把内置样式表改写到资产源，并移除模板中的脚本；
            // 文档 URL 仍用站点根，这样相对链接与 `#anchor` 都能按原站语义解析
            // The document URL is the registry site root (relative links resolve against it and
            // `#anchor` links stay in the same document); bundled stylesheets keep loading from
            // the asset loader.
            if (restrictedBaseUrl == null) t else t
                .replace("href=\"/assets/webview/", "href=\"$APP_ASSETS_ORIGIN/assets/webview/")
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        }
        .replace("@dir@", dir)
        .replace("@style@", styleContent)
        .replace("@body@", rendered)

    AndroidView(
        factory = { context ->
            val frameLayout = FrameLayout(context)
            val webView = EdgeAwareWebView(context).apply {
                try {
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    settings.apply {
                        offscreenPreRaster = true
                        javaScriptEnabled = !restricted
                        domStorageEnabled = !restricted
                        mixedContentMode = if (restricted) {
                            WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        } else {
                            WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        allowContentAccess = false
                        allowFileAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        textZoom = newTextZoom
                        setSupportZoom(false)
                        setGeolocationEnabled(false)
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    if (!restricted) {
                        addJavascriptInterface(scrollInterface, "AndroidScroll")
                    }
                    webViewClient = object : WebViewClient() {
                        private val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                            .build()

                        // 页面加载后注入滚动探测脚本，把宽表格/代码块的横向可滚动状态回传给原生手势仲裁
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            if (restricted) return

                            val js = """
                                (function() {
                                    if (window.androidScrollInjected) return;
                                    window.androidScrollInjected = true;
                                
                                    function checkScroll(target) {
                                        if (!target || target === document.body || target === document.documentElement) return {l: false, r: false};
                                        var style = window.getComputedStyle(target);
                                        if (style.overflowX !== 'auto' && style.overflowX !== 'scroll') return {l: false, r: false};
                                        if (target.scrollWidth <= target.clientWidth) return {l: false, r: false};
                                        
                                        var atLeft = target.scrollLeft <= 0;
                                        var atRight = Math.ceil(target.scrollLeft + target.clientWidth) >= target.scrollWidth;
                                        
                                        return {l: !atLeft, r: !atRight};
                                    }
                                
                                    var lastTarget = null;
                                    var lastState = {l: false, r: false};
                                    
                                    function update(l, r) {
                                        if (lastState.l !== l || lastState.r !== r) {
                                            lastState = {l: l, r: r};
                                            AndroidScroll.updateScrollState(l, r);
                                        }
                                    }
                                
                                    document.addEventListener('touchstart', function(e) {
                                        var t = e.target;
                                        var found = false;
                                        while(t && t !== document.body) {
                                            var s = checkScroll(t);
                                            if (s.l || s.r) { 
                                                 lastTarget = t;
                                                 update(s.l, s.r);
                                                 found = true;
                                                 break;
                                            }
                                            t = t.parentElement;
                                        }
                                        if (!found) {
                                            lastTarget = null;
                                            update(false, false);
                                        }
                                    }, {passive: true});
                                
                                    document.addEventListener('touchmove', function(e) {
                                        if (lastTarget) {
                                             var s = checkScroll(lastTarget);
                                             update(s.l, s.r);
                                        }
                                    }, {passive: true});
                                    
                                    document.addEventListener('scroll', function(e) {
                                        if (lastTarget && (e.target === lastTarget || e.target.contains(lastTarget))) {
                                              var s = checkScroll(lastTarget);
                                              update(s.l, s.r);
                                        }
                                    }, {passive: true, capture: true});
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        }

                        // 所有导航都返回 true 表示“WebView 自己不跳转”；非受限模式把链接交给外部浏览器
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            if (restrictedBaseUrl != null) {
                                // `#anchor` of this document: the view is as tall as its content,
                                // so there is nothing to scroll to; never leave the app for it.
                                if (RegistryUrls.isInPageAnchor(request.url.toString(), restrictedBaseUrl)) return true
                                // Never navigate inside the view; only web links the user tapped
                                // leave the app (no automatic redirects such as <meta refresh>).
                                if (!request.hasGesture()) return true
                                val scheme = request.url.scheme?.lowercase()
                                if (scheme != "https" && scheme != "http") return true
                            }
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, request.url)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Log.w("GithubMarkdown", "No activity to handle: ${request.url}")
                            }
                            return true
                        }

                        // 首帧可见即结束 loading 状态，比 onPageFinished 更早，能减少白屏时间
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            onLoadingChange(false)
                        }

                        // 子资源统一走代理：先尝试资产加载器，再按受限策略校验来源，最后用 OkHttp 拉取
                        override fun shouldInterceptRequest(
                            view: WebView, request: WebResourceRequest
                        ): WebResourceResponse? {
                            assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                            if (restrictedBaseUrl != null) {
                                // Newer WebViews route the loadDataWithBaseURL document itself
                                // through here as a data: main-frame request; let it load. Links
                                // are handled by shouldOverrideUrlLoading, and Chromium blocks
                                // page-initiated top-level data: navigations.
                                if (request.isForMainFrame && request.url.scheme.equals("data", ignoreCase = true)) return null
                                // Same scheme + host + port as the registry, GET only; the
                                // restricted client re-checks every redirect hop.
                                val allowed = request.method.equals("GET", ignoreCase = true) &&
                                    RegistryUrls.isAllowedRestrictedResource(request.url.toString(), restrictedBaseUrl)
                                if (!allowed) return blockedResponse()
                            }
                            val scheme = request.url.scheme ?: return if (restricted) blockedResponse() else null
                            if (!scheme.startsWith("http")) return if (restricted) blockedResponse() else null
                            val client: OkHttpClient = restrictedClient ?: ksuApp.okhttpClient
                            val call = client.newCall(
                                Request.Builder()
                                    .url(request.url.toString())
                                    .method(request.method, null)
                                    .headers(request.requestHeaders.toHeaders())
                                    .build()
                            )
                            return try {
                                val reply = call.execute()
                                val header = reply.header("content-type", "text/plain; charset=utf-8")
                                val contentTypes = header?.split(SEMICOLON_SPLIT) ?: emptyList()
                                val mimeType = contentTypes.firstOrNull() ?: "image/*"
                                val charset = contentTypes.getOrNull(1)?.split(EQUALS_SPLIT)?.getOrNull(1) ?: "utf-8"
                                WebResourceResponse(mimeType, charset, reply.body.byteStream())
                            } catch (e: IOException) {
                                Log.e("GithubMarkdown", "Resource load failed", e)
                                if (restricted) return blockedResponse()
                                WebResourceResponse(
                                    "text/html", "utf-8",
                                    ByteArrayInputStream(Log.getStackTraceString(e).toByteArray())
                                )
                            }
                        }
                    }
                    if (restricted) {
                        setOnTouchListener(RestrictedTouchHandler(this))
                    } else setOnTouchListener(object : View.OnTouchListener {
                        // 锁定后本次手势期间不再向父级让渡，保证横向拖动不会被分页容器抢走
                        private var isHorizontalScrollLocked = false
                        private var initialDownX = 0f
                        private var initialDownY = 0f

                        // 横向拖动优先交给 WebView 内部滚动；只有内部已滚到边界时才让父级接管
                        @SuppressLint("ClickableViewAccessibility")
                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    initialDownX = event.x
                                    initialDownY = event.y
                                    isHorizontalScrollLocked = false
                                    v.parent.requestDisallowInterceptTouchEvent(true)
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    if (isHorizontalScrollLocked) {
                                        v.parent.requestDisallowInterceptTouchEvent(true)
                                    } else {
                                        val dx = event.x - initialDownX
                                        val dy = event.y - initialDownY
                                        if (abs(dx) > abs(dy)) {
                                            val canScroll = if (dx < 0) scrollInterface.canScrollRight else scrollInterface.canScrollLeft
                                            if (canScroll) {
                                                isHorizontalScrollLocked = true
                                                v.parent.requestDisallowInterceptTouchEvent(true)
                                            } else {
                                                v.parent.requestDisallowInterceptTouchEvent(false)
                                            }
                                        } else {
                                            v.parent.requestDisallowInterceptTouchEvent(false)
                                            return true
                                        }
                                    }
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    v.parent.requestDisallowInterceptTouchEvent(false)
                                    isHorizontalScrollLocked = false
                                }
                            }
                            return false
                        }
                    })
                } catch (e: Throwable) {
                    Log.e("GithubMarkdown", "WebView setup failed", e)
                }
            }
            frameLayout.addView(webView)
            frameLayout
        },
        update = { frameLayout ->
            val webView = frameLayout.getChildAt(0) as? WebView ?: return@AndroidView
            webView.settings.textZoom = newTextZoom
            // 以 html 作为内容指纹：只有页面内容变化才重新 load，滚动重组不会触发重载
            if (webView.tag != html) {
                webView.tag = html
                onLoadingChange(true)
                webView.loadDataWithBaseURL(
                    restrictedBaseUrl ?: APP_ASSETS_ORIGIN, html,
                    "text/html", StandardCharsets.UTF_8.name(), null
                )
            }
        },
        // 离开组合时主动停止加载并销毁 WebView，否则其渲染进程与 JS 上下文会一直驻留
        onRelease = { frameLayout ->
            val webView = frameLayout.getChildAt(0) as? WebView
            frameLayout.removeAllViews()
            webView?.apply {
                stopLoading()
                destroy()
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clipToBounds(),
    )
}

/** 构造一个 403 空响应，用于静默拦截受限模式下不允许访问的资源 */
private fun blockedResponse() = WebResourceResponse(
    "text/plain", "utf-8", 403, "Blocked", emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

// 按来源缓存受限客户端：OkHttpClient 自带连接池与线程，重复创建代价高且会泄漏资源
private val restrictedClients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

/**
 * HTTP client for sub-resources of registry content: redirects are only followed while they
 * stay on the registry origin (scheme + host + port); any other hop fails the request.
 */
private fun restrictedResourceClient(origin: String): OkHttpClient = restrictedClients.getOrPut(origin) {
    ksuApp.okhttpClient.newBuilder()
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url.toString()
            if (!RegistryUrls.isAllowedRestrictedResource(url, origin)) {
                throw IOException("Blocked cross-origin resource: ${chain.request().url.host}")
            }
            chain.proceed(chain.request())
        }
        .build()
}

/**
 * WebView that reports when a horizontal drag could not be consumed by the page (the root
 * scroller hit its edge, i.e. no inner `overflow-x: auto` element scrolled). Works without
 * JavaScript, so the restricted renderer can still scroll wide tables and code blocks.
 */
private class EdgeAwareWebView(context: android.content.Context) : WebView(context) {
    // 由 onOverScrolled 置位：表示本次横向拖动已到达页面滚动边界，可交还父级手势
    var horizontalEdgeReached = false

    /** 当横向滚动被钳制时标记“已到边界”，供触摸仲裁读取 */
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedX) horizontalEdgeReached = true
    }
}

/**
 * Touch arbitration for the restricted (JavaScript-off) renderer: horizontal drags go to the
 * page first (wide tables / code scroll natively) and are handed to the parent (e.g. a pager)
 * once the page reports it cannot scroll further; vertical drags always go to the parent.
 */
private class RestrictedTouchHandler(
    private val webView: EdgeAwareWebView,
) : View.OnTouchListener {
    // 用系统 touchSlop 判定方向，避免手指轻微抖动就把手势判成横向，导致页面无法纵向滚动
    private val touchSlop = android.view.ViewConfiguration.get(webView.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var direction = 0 // 0 undecided, 1 horizontal, 2 vertical

    /** 方向判定完成后按方向决定是否拦截：纵向立即让给父级，横向等页面到边界再让出 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                direction = 0
                webView.horizontalEdgeReached = false
                v.parent.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (direction == 0 && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    direction = if (abs(dx) > abs(dy)) 1 else 2
                }
                when (direction) {
                    1 -> if (webView.horizontalEdgeReached) v.parent.requestDisallowInterceptTouchEvent(false)
                    2 -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent.requestDisallowInterceptTouchEvent(false)
                direction = 0
            }
        }
        return false
    }
}

/**
 * JavaScript 与原生之间的滚动状态桥。
 *
 * 字段用 @Volatile 修饰：写入发生在 WebView 的 JS 线程，读取发生在 UI 线程，
 * 需要保证可见性；布尔读写本身原子，因此无需再加锁。
 */
class MarkdownScrollInterface {
    /** 内部可横向滚动的元素当前是否还能继续向左滚 */
    @Volatile
    var canScrollLeft = false

    /** 内部可横向滚动的元素当前是否还能继续向右滚 */
    @Volatile
    var canScrollRight = false

    /** 由注入脚本在滚动状态变化时调用，仅在状态确实改变时才回调以降低跨线程开销 */
    @JavascriptInterface
    fun updateScrollState(left: Boolean, right: Boolean) {
        canScrollLeft = left
        canScrollRight = right
    }
}

/** Markdown 页面所需的一组 CSS 颜色字符串，由主题色派生后注入模板 */
private data class MarkdownColors(
    val bgCode: String,
    val bgRowAlt: String,
    val fgDefault: String,
    val fgLink: String
)

/**
 * 按当前 UI 模式生成 Markdown 配色。
 *
 * Material 直接取 colorScheme；Miuix 没有对应的代码块/隔行色 token，
 * 因此从容器底色做亮度微调，并用 `ensureVisibleByMix` 保证与底色的对比度，
 * 避免深色主题下代码块“糊”进背景。
 */
@Composable
private fun getMarkdownColors(containerColor: androidx.compose.ui.graphics.Color?): MarkdownColors {
    val uiMode = LocalUiMode.current

    return when (uiMode) {
        UiMode.Material -> {
            MarkdownColors(
                bgCode = cssColorFromArgb(MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()),
                bgRowAlt = cssColorFromArgb(MaterialTheme.colorScheme.surfaceContainerLow.toArgb()),
                fgDefault = cssColorFromArgb(MaterialTheme.colorScheme.onSurface.toArgb()),
                fgLink = cssColorFromArgb(MaterialTheme.colorScheme.primary.toArgb())
            )
        }

        UiMode.Miuix -> {
            val bgArgb = containerColor?.toArgb() ?: MiuixTheme.colorScheme.surfaceContainer.toArgb()
            // 亮度决定微调方向：浅底压暗、深底提亮，否则色块会向背景“靠拢”而不可见
            val bgLuminance = relativeLuminance(bgArgb)

            /** 在底色基础上偏移亮度，并混入底色调直到满足最小可见对比度 */
            fun makeVariant(delta: Float, ratio: Double): Int {
                val candidate = adjustLightnessArgb(bgArgb, delta)
                val madeLighter = delta > 0f
                return ensureVisibleByMix(bgArgb, candidate, ratio, madeLighter)
            }

            val codeDelta = if (bgLuminance > 0.6) -0.05f else 0.05f
            val rowAltDelta = if (bgLuminance > 0.6) -0.02f else 0.02f

            MarkdownColors(
                bgCode = cssColorFromArgb(makeVariant(codeDelta, 1.1)),
                bgRowAlt = cssColorFromArgb(makeVariant(rowAltDelta, 1.05)),
                fgDefault = cssColorFromArgb(MiuixTheme.colorScheme.onSurface.toArgb()),
                fgLink = cssColorFromArgb(MiuixTheme.colorScheme.primary.toArgb())
            )
        }
    }
}
