package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 覆盖仓库客户端的目录/详情加载、build 一致性、缓存回退与字段校验策略。 */
class RegistryClientTest {

    // 测试基线地址与各 API 端点拼接规则，需与 RegistryUrls.resolve 的约定一致。
    private val base = "https://modules.kernelsu.org/"
    private val indexUrl = base + "api/v1/index.json"
    private val listUrl = base + "api/v1/modules.json"
    // 详情与 readme 的 URL 由模块 id 拼出，避免测试里重复硬编码。
    private fun detailUrl(id: String) = base + "api/v1/modules/$id.json"
    // readme 端点没有扩展名，单独拼接以贴近真实 API。
    private fun readmeUrl(id: String) = base + "api/v1/modules/$id/readme"

    // 三个不同的 buildId，用于模拟仓库重新部署前后版本交错的情况。
    private val b1 = "1111111111111111"
    private val b2 = "2222222222222222"
    private val b3 = "3333333333333333"
    // 合法 SHA-256（64 位）与 commit（40 位）占位值。
    private val sha = "a".repeat(64)
    private val commit = "c".repeat(40)

    // 测试用 RegistryFetcher：按 URL 返回预置响应，并记录调用次数。
    private class FakeFetcher : RegistryFetcher {
        // 每个 URL 对应一个响应队列；队列耗尽后继续返回最后一个响应，便于模拟“重启后同一份数据”。
        val responses = HashMap<String, ArrayDeque<String>>()
        // 按调用顺序记录 URL，用于断言重试/刷新次数。
        val calls = ArrayList<String>()
        // 置为 true 时所有请求都抛 IOException，模拟离线。
        var offline = false

        // 为指定 URL 设置响应序列；多个 body 表示按次返回。
        fun set(url: String, vararg bodies: String) {
            responses[url] = ArrayDeque(bodies.toList())
        }

        // 模拟 HTTP 获取：未注册的 URL 视为 404。
        override fun fetch(url: String): String {
            calls += url
            if (offline) throw IOException("offline")
            val queue = responses[url] ?: throw IOException("HTTP 404 for $url")
            // Keep returning the last body once the queue is drained.
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        // 统计某个 URL 的请求次数，验证“只刷新一次”等约束。
        fun count(url: String) = calls.count { it == url }
    }

    // 内存版 RegistryCache，避免测试触碰真实文件系统。
    private class MemoryCache : RegistryCache {
        // 以 key 为索引保存序列化后的 JSON 文本。
        val map = HashMap<String, String>()
        // 命中返回缓存内容，未命中返回 null 以触发网络回退。
        override fun read(key: String): String? = map[key]
        // 直接覆盖写入，模拟原子缓存语义。
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    // 构造 index.json：故意带未知字段，验证解析器忽略新增字段的向前兼容性。
    private fun index(buildId: String, schema: Int = 1) = """
        {"schemaVersion":$schema,"buildId":"$buildId","repoCommit":"$commit",
         "generatedAt":"2026-10-01T00:00:00Z","modules":"api/v1/modules.json","someFutureField":{"x":[1,2]}}
    """.trimIndent()

    // 构造 modules.json 中的单条摘要；extra 用于在对象末尾追加额外字段。
    private fun summary(id: String, versionCode: Long = 100, extra: String = "") = """
        {"id":"$id","name":"Name $id","summary":"Sum",
         "authors":[{"name":"Alice","url":"https://github.com/alice"}],"tags":["tools"],"metamodule":false,
         "icon":"assets/abc.png","stable":{"version":"1.0","versionCode":$versionCode,"publishedAt":"2026-10-01T00:00:00Z"},
         "detail":"api/v1/modules/$id.json","updatedAt":"2026-10-01T00:00:00Z","unknownField":true$extra}
    """.trimIndent()

    // 把若干摘要拼成 modules.json；joinToString 不额外加空格，保持紧凑 JSON。
    private fun modules(buildId: String, vararg items: String) =
        """{"schemaVersion":1,"buildId":"$buildId","modules":[${items.joinToString(",")}]}"""

    // 构造 release 对象；attestationUrl 为 null 时输出 JSON null，便于测试可空字段。
    private fun release(
        tag: String,
        versionCode: Int,
        url: String,
        prerelease: Boolean = false,
        releaseUrl: String = "https://github.com/owner/repo/releases/tag/$tag",
        attestationUrl: String? = null,
    ) = """
        {"tag":"$tag","name":null,"version":"$tag","versionCode":$versionCode,"prerelease":$prerelease,
         "publishedAt":"2026-10-01T00:00:00Z","changelogHtml":"<p>notes</p>",
         "zip":{"url":"$url","name":"m.zip","size":123,"sha256":"$sha"},"tagCommit":"$commit",
         "releaseUrl":"$releaseUrl","attestationUrl":${attestationUrl?.let { "\"$it\"" } ?: "null"},"immutable":true}
    """.trimIndent()

    // 构造详情 JSON；releases/readme/sourceUrl 可覆盖，用于验证字段被拒后的降级行为。
    private fun detail(
        id: String,
        buildId: String,
        releases: List<String>? = null,
        sourceUrl: String = "https://github.com/owner/repo",
        readme: String? = "api/v1/modules/$id/readme",
        extra: String = "",
    ): String {
        // 未显式提供时给一个合法 release，保证详情能通过最小校验。
        val rel = releases ?: listOf(release("v1", 100, "https://github.com/owner/repo/releases/download/v1/m.zip"))
        return """
            {"schemaVersion":1,"buildId":"$buildId","id":"$id","defaultLocale":"en",
             "name":"Name","summary":"Sum","authors":[],"tags":[],"metamodule":true,"icon":null,
             "stable":{"version":"v1","versionCode":100,"publishedAt":"2026-10-01T00:00:00Z"},
             "detail":"api/v1/modules/$id.json","updatedAt":"2026-10-01T00:00:00Z",
             "license":"MIT","homepage":null,"support":null,"compatibility":null,
             "source":{"host":"github","fullName":"owner/repo","url":"$sourceUrl","commit":"$commit"},
             "readme":${readme?.let { "\"$it\"" } ?: "null"},
             "releases":[${rel.joinToString(",")}],
             "health":{"status":"ok","lastSuccessAt":null},"newThing":1$extra}
        """.trimIndent()
    }

    // 默认使用内存缓存创建客户端；需要验证持久化行为时传入共享的 cache。
    private fun client(fetcher: FakeFetcher, cache: RegistryCache = MemoryCache()) =
        RegistryClient(base, fetcher, cache)

    // 目录加载应忽略 schema 中未知字段，并正确读取 index 与 modules 的 buildId。
    @Test
    fun loadsCatalogIgnoringUnknownFields() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo"), summary("bar")))
        val load = client(f).loadCatalog()
        assertNull(load.error)
        assertFalse(load.catalog.fromCache)
        assertEquals(b1, load.catalog.buildId)
        assertEquals(listOf("foo", "bar"), load.catalog.modules.map { it.id })
    }

    // 不认识的 schemaVersion 或类型错误必须抛出 UnsupportedSchemaException，避免误读未来格式。
    @Test
    fun rejectsUnknownSchemaVersion() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1, schema = 2))
        f.set(listUrl, modules(b1, summary("foo")))
        val e = assertThrows(UnsupportedSchemaException::class.java) { client(f).loadCatalog() }
        assertEquals(2, e.schemaVersion)

        f.set(indexUrl, index(b1))
        // schemaVersion 以字符串给出属于类型错误，同样按不支持处理。
        f.set(listUrl, """{"schemaVersion":"1","buildId":"$b1","modules":[]}""")
        assertThrows(UnsupportedSchemaException::class.java) { client(f).loadCatalog() }
    }

    // 单条摘要非法时只丢弃该条，不影响同批次其他模块。
    @Test
    fun dropsInvalidEntries() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(
            listUrl,
            modules(
                b1,
                summary("ok"),
                summary("overflow", versionCode = 3_000_000_000L), // not an int32
                summary("zero", versionCode = 0),
                summary("1bad-id"),
                summary("evilpath", extra = ""),
            ).replace("api/v1/modules/evilpath.json", "../../etc/passwd")
        )
        val ids = client(f).loadCatalog().catalog.modules.map { it.id }
        assertEquals(listOf("ok"), ids)
    }

    // index 与 modules 的 buildId 不一致时，重新读取一次 index；仍不一致则接受新 build。
    @Test
    fun listFromAnotherBuildIsRereadOnce() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b2))
        f.set(listUrl, modules(b2, summary("foo")))
        val catalog = client(f).loadCatalog().catalog
        assertEquals(b2, catalog.buildId)
        assertEquals(2, f.count(indexUrl))
    }

    // 重复读取后 buildId 仍不一致时必须失败，不能把不同 build 的数据混在一起。
    @Test
    fun persistentListMismatchFails() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b2, summary("foo")))
        assertThrows(BuildMismatchException::class.java) { client(f).loadCatalog() }
        assertEquals(2, f.count(indexUrl))
    }

    // 详情来自更新的 build 时，刷新 index+list 一次即可接受该详情，不再重复请求详情。
    @Test
    fun detailFromNewerBuildRefetchesIndexAndListOnce() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b2))
        f.set(listUrl, modules(b1, summary("foo")), modules(b2, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b2))
        val c = client(f)
        assertEquals(b1, c.loadCatalog().catalog.buildId)

        val load = c.loadDetail("foo")
        assertEquals(b2, load.catalog.buildId)
        assertEquals(b2, load.detail.buildId)
        assertEquals(b2, c.currentCatalog()?.buildId)
        assertEquals(2, f.count(indexUrl))
        // The detail already matched the refreshed list, no second detail request is needed.
        assertEquals(1, f.count(detailUrl("foo")))
    }

    // 刷新后详情仍属于旧 build 时，必须按新 build 重新拉取详情。
    @Test
    fun detailIsRefetchedForTheRefreshedBuild() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b3))
        f.set(listUrl, modules(b1, summary("foo")), modules(b3, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b2), detail("foo", b3))
        val c = client(f)
        c.loadCatalog()
        val load = c.loadDetail("foo")
        assertEquals(b3, load.catalog.buildId)
        assertEquals(b3, load.detail.buildId)
        assertEquals(2, f.count(detailUrl("foo")))
    }

    // 详情 build 与刷新后的目录 build 永远无法对齐时，抛出 BuildMismatchException 而不是接受旧数据。
    @Test
    fun neverMixesBuilds() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b3))
        f.set(listUrl, modules(b1, summary("foo")), modules(b3, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b2))
        val c = client(f)
        c.loadCatalog()
        assertThrows(BuildMismatchException::class.java) { c.loadDetail("foo") }
        // Index + list were re-read exactly once.
        assertEquals(2, f.count(indexUrl))
    }

    // 刷新前缓存的详情属于旧 build，不能继续提供；必须重新请求并校验当前 build。
    @Test
    fun cachedDetailOfTheCatalogBeforeARefreshIsNotUsed() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b3))
        f.set(listUrl, modules(b1, summary("foo")), modules(b3, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b1), detail("foo", b2))
        val c = client(f)
        c.loadCatalog()
        assertEquals(b1, c.loadDetail("foo").detail.buildId) // cached as b1

        // Redeployed: the detail is b2, the refreshed catalog b3. The cached b1 detail no longer
        // matches the catalog the client is on, so it must not be offered.
        assertThrows(BuildMismatchException::class.java) { c.loadDetail("foo") }
        assertEquals(b3, c.currentCatalog()?.buildId)
    }

    // 模块不在索引中时直接报 ModuleNotInIndexException，不浪费一次详情请求。
    @Test
    fun moduleMissingFromIndexIsNotLoaded() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("gone"), detail("gone", b1))
        val c = client(f)
        assertThrows(ModuleNotInIndexException::class.java) { c.loadDetail("gone") }
        assertEquals(0, f.count(detailUrl("gone")))
    }

    // 模块从新 build 中移除（详情 404）时报告 NotInIndex，并保留刷新后的目录状态。
    @Test
    fun removedModuleIsReportedAsNotInIndex() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo"), summary("bar")))
        f.set(detailUrl("foo"), detail("foo", b1))
        val c = client(f)
        c.loadCatalog()
        assertEquals(b1, c.loadDetail("foo").detail.buildId) // cached as b1

        // Removed in b2: its detail is gone (404) and the client still holds b1.
        f.set(indexUrl, index(b2))
        f.set(listUrl, modules(b2, summary("bar")))
        f.responses.remove(detailUrl("foo"))
        assertThrows(ModuleNotInIndexException::class.java) { c.loadDetail("foo") }
        assertEquals(b2, c.currentCatalog()?.buildId)
    }

    // 详情首次解析失败后，若刷新到新 build，应重新拉取而不是永久缓存失败结果。
    @Test
    fun failedDetailIsReloadedForANewerBuild() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1), index(b2))
        f.set(listUrl, modules(b1, summary("foo")), modules(b2, summary("foo")))
        f.set(detailUrl("foo"), "{broken", detail("foo", b2))
        val c = client(f)
        c.loadCatalog()
        val load = c.loadDetail("foo")
        assertFalse(load.fromCache)
        assertEquals(b2, load.catalog.buildId)
        assertEquals(b2, load.detail.buildId)
        assertEquals(2, f.count(detailUrl("foo")))
    }

    // 刷新失败但本地有缓存时，详情回退到缓存并附带错误信息；只尝试刷新一次。
    @Test
    fun offlineDetailFallsBackToCacheAfterAFailedRefresh() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b1))
        val c = client(f)
        c.loadCatalog()
        c.loadDetail("foo")

        f.offline = true
        val load = c.loadDetail("foo")
        assertTrue(load.fromCache)
        assertNotNull(load.error)
        assertEquals(b1, load.detail.buildId)
        // One refresh of index + list was attempted.
        assertEquals(2, f.count(indexUrl))
    }

    // 详情响应的 id 必须与请求一致，防止服务端/代理返回错模块内容。
    @Test
    fun detailIdMustMatch() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("foo"), detail("bar", b1))
        assertThrows(RegistryFormatException::class.java) { client(f).loadDetail("foo") }
    }

    // 非白名单域名的 release 被丢弃，但同仓库改名/转移后的旧 owner/repo 域名仍保留。
    @Test
    fun releasesWithDisallowedUrlsAreDropped() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(
            detailUrl("foo"),
            detail(
                "foo", b1, listOf(
                    release("v3", 300, "https://evil.example/owner/repo/releases/download/v3/m.zip"),
                    release("v2", 200, "https://github.com/other/repo/releases/download/v2/m.zip"),
                    release("v1", 100, "https://github.com/owner/repo/releases/download/v1/m.zip"),
                    release("v0", 50, "https://github.com/owner/repo/releases/download/v0/m.zip", prerelease = true),
                )
            )
        )
        val releases = client(f).loadDetail("foo").detail.releases
        // A release of another owner/repo is kept: its URL predates a rename or transfer.
        assertEquals(listOf("v2", "v1", "v0"), releases.map { it.tag })
        assertTrue(releases.last().prerelease)
    }

    // 联网失败时目录、详情和 readme 都应回退到最后一份完整缓存，并标记 fromCache。
    @Test
    fun fallsBackToLastCompleteCopy() {
        val cache = MemoryCache()
        val online = FakeFetcher()
        online.set(indexUrl, index(b1))
        online.set(listUrl, modules(b1, summary("foo")))
        online.set(detailUrl("foo"), detail("foo", b1))
        online.set(readmeUrl("foo"), "<p>hello</p>")
        client(online, cache).also {
            it.loadCatalog()
            val d = it.loadDetail("foo").detail
            assertEquals("<p>hello</p>", it.loadReadme(d)?.html)
        }

        // 同一 cache 换到离线 fetcher，模拟重启后无网络。
        val offline = FakeFetcher().apply { this.offline = true }
        val c = client(offline, cache)
        val load = c.loadCatalog()
        assertTrue(load.catalog.fromCache)
        assertNotNull(load.error)
        assertEquals(b1, load.catalog.buildId)
        val detail = c.loadDetail("foo")
        assertTrue(detail.fromCache)
        assertEquals(b1, detail.detail.buildId)
        assertEquals("<p>hello</p>", c.loadReadme(detail.detail)?.html)
    }

    // 网络返回不完整 JSON 时回退到缓存，且不能污染已有的好缓存。
    @Test
    fun brokenNetworkCopyFallsBackToCache() {
        val cache = MemoryCache()
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        client(f, cache).loadCatalog()

        val broken = FakeFetcher()
        broken.set(indexUrl, index(b2))
        // 故意截断 JSON，触发 RegistryFormatException。
        broken.set(listUrl, """{"schemaVersion":1,"buildId":"$b2","modules":[""")
        val load = client(broken, cache).loadCatalog()
        assertEquals(b1, load.catalog.buildId)
        assertTrue(load.error is RegistryFormatException)
        // The broken copy did not replace the good one.
        assertEquals(b1, client(FakeFetcher().apply { offline = true }, cache).loadCatalog().catalog.buildId)
    }

    // 缓存中的详情属于另一个 build 时不可复用；无网络时应抛 IOException 而不是返回错版本。
    @Test
    fun cachedDetailOfAnotherBuildIsNotUsed() {
        val cache = MemoryCache()
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b1))
        client(f, cache).also { it.loadCatalog(); it.loadDetail("foo") }

        // 仓库重建为 b2 后，b1 的详情缓存失效。
        val f2 = FakeFetcher()
        f2.set(indexUrl, index(b2))
        f2.set(listUrl, modules(b2, summary("foo")))
        val c = client(f2, cache)
        c.loadCatalog()
        f2.offline = true
        assertThrows(IOException::class.java) { c.loadDetail("foo") }
    }

    // 既无网络也无缓存时，加载目录必须让 IOException 冒泡，由 UI 显示失败。
    @Test
    fun noNetworkAndNoCacheFails() {
        val f = FakeFetcher().apply { offline = true }
        assertThrows(IOException::class.java) { client(f).loadCatalog() }
    }

    // 单语言字段（非多语言 map）应直接解析为字符串，详情中的 readme 路径同样保留。
    @Test
    fun singleLanguageFieldsAreParsed() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b1))
        val c = client(f)
        val m = c.loadCatalog().catalog.find("foo")!!
        assertEquals("Name foo", m.name)
        assertEquals("Sum", m.summary)
        val d = c.loadDetail("foo").detail
        assertEquals("api/v1/modules/foo/readme", d.readme)
    }

    // readme 缺失与路径不安全都归一化为 null，调用方无需区分“没有”与“被拒绝”。
    @Test
    fun missingReadmeIsNullAndUnsafeReadmePathIsDropped() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo"), summary("bar")))
        f.set(detailUrl("foo"), detail("foo", b1, readme = null))
        f.set(detailUrl("bar"), detail("bar", b1, readme = "../secret.html"))
        val c = client(f)
        c.loadCatalog()
        val foo = c.loadDetail("foo").detail
        assertNull(foo.readme)
        assertNull(c.loadReadme(foo))
        assertNull(c.loadDetail("bar").detail.readme)
    }

    // readme 拉取失败属于 IO 错误，不能伪装成“模块没有 readme”，否则 UI 会静默隐藏内容。
    @Test
    fun readmeFailureIsAnErrorNotAMissingReadme() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(detailUrl("foo"), detail("foo", b1))
        val c = client(f)
        c.loadCatalog()
        val d = c.loadDetail("foo").detail
        // No body registered for the readme URL -> the fetch fails and no cached copy exists.
        assertThrows(IOException::class.java) { c.loadReadme(d) }
    }

    // 单条 release 解析失败只丢弃该条，不影响其他 release；非法 attestation 同样被清空。
    @Test
    fun badReleasesAreDroppedIndividually() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        val ok = "https://github.com/owner/repo/releases/download/v1/m.zip"
        f.set(
            detailUrl("foo"),
            detail(
                "foo", b1, listOf(
                    // Wrong type for versionCode: undecodable on its own.
                    release("v5", 500, ok).replace("\"versionCode\":500", "\"versionCode\":\"x\""),
                    // Missing required field.
                    release("v4", 400, ok).replace("\"tagCommit\":\"$commit\",", ""),
                    // Non-https release page.
                    release("v3", 300, ok, releaseUrl = "http://github.com/owner/repo/releases/tag/v3"),
                    release("v2", 200, ok, attestationUrl = "javascript:alert(1)"),
                    "42",
                    release("v1", 100, ok, attestationUrl = "https://github.com/owner/repo/attestations/1"),
                )
            )
        )
        val releases = client(f).loadDetail("foo").detail.releases
        assertEquals(listOf("v2", "v1"), releases.map { it.tag })
        // v2 的 attestation 是 javascript:，应被置空；v1 的 https 链接保留。
        assertNull(releases[0].attestationUrl)
        assertEquals("https://github.com/owner/repo/attestations/1", releases[1].attestationUrl)
    }

    // 同一 tag 的重复 release 只保留首个，避免 UI 出现重复版本项。
    @Test
    fun releasesAreDeduplicatedByTag() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo")))
        f.set(
            detailUrl("foo"),
            detail(
                "foo", b1, listOf(
                    release("v2", 200, "https://github.com/owner/repo/releases/download/v2/m.zip"),
                    release("v2", 150, "https://github.com/owner/repo/releases/download/v2/other.zip"),
                    release("v1", 100, "https://github.com/owner/repo/releases/download/v1/m.zip"),
                )
            )
        )
        val releases = client(f).loadDetail("foo").detail.releases
        assertEquals(listOf("v2", "v1"), releases.map { it.tag })
        assertEquals(200, releases[0].versionCode)
    }

    // 非 https 的源码地址回退为 GitHub 仓库地址，防止 WebView 打开 javascript:/http 链接。
    @Test
    fun nonHttpsSourceUrlIsReplacedWithGithubUrl() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo"), summary("bar")))
        f.set(detailUrl("foo"), detail("foo", b1, sourceUrl = "javascript:alert(1)"))
        f.set(detailUrl("bar"), detail("bar", b1, sourceUrl = "http://github.com/owner/repo"))
        val c = client(f)
        c.loadCatalog()
        assertEquals("https://github.com/owner/repo", c.loadDetail("foo").detail.source.url)
        assertEquals("https://github.com/owner/repo", c.loadDetail("bar").detail.source.url)
    }

    // stars 字段为可空：显式 0 与 null/缺失语义不同，解析后要保留这一区别。
    @Test
    fun starsAreParsedWhenPresentNullOrMissing() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(
            listUrl,
            modules(
                b1,
                summary("present", extra = ""","stars":1234,"starsRepository":"owner/module-dist""""),
                summary("zero", extra = ""","stars":0,"starsRepository":"owner/repo""""),
                summary("nulls", extra = ""","stars":null,"starsRepository":null"""),
                summary("missing"),
            )
        )
        val catalog = client(f).loadCatalog().catalog
        assertEquals(listOf("present", "zero", "nulls", "missing"), catalog.modules.map { it.id })
        catalog.find("present")!!.let {
            assertEquals(1234, it.stars)
            assertEquals("owner/module-dist", it.starsRepository)
        }
        catalog.find("zero")!!.let {
            assertEquals(0, it.stars)
            assertEquals("owner/repo", it.starsRepository)
        }
        for (id in listOf("nulls", "missing")) {
            assertNull(catalog.find(id)!!.stars)
            assertNull(catalog.find(id)!!.starsRepository)
        }
    }

    // stars 类型/范围异常时只丢弃星标字段，模块本身仍保留（避免整批目录不可用）。
    @Test
    fun malformedStarsAreDroppedButTheModuleIsKept() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(
            listUrl,
            modules(
                b1,
                summary("str", extra = ""","stars":"12","starsRepository":"owner/repo""""),
                summary("neg", extra = ""","stars":-1,"starsRepository":"not a repo""""),
                summary("frac", extra = ""","stars":1.5,"starsRepository":"owner/..""""),
                summary("huge", extra = ""","stars":3000000000,"starsRepository":{"x":1}"""),
                summary("obj", extra = ""","stars":{"n":1},"starsRepository":"https://github.com/owner/repo""""),
            )
        )
        val modules = client(f).loadCatalog().catalog.modules
        assertEquals(listOf("str", "neg", "frac", "huge", "obj"), modules.map { it.id })
        assertTrue(modules.all { it.stars == null })
        assertEquals(listOf("owner/repo", null, null, null, null), modules.map { it.starsRepository })
    }

    // 详情中的 stars 与 source 仓库互相独立：星标可指向分发仓库而不是源码仓库。
    @Test
    fun detailStarsAreParsed() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(listUrl, modules(b1, summary("foo"), summary("bar"), summary("baz")))
        f.set(detailUrl("foo"), detail("foo", b1, extra = ""","stars":7,"starsRepository":"owner/dist""""))
        f.set(detailUrl("bar"), detail("bar", b1, extra = ""","stars":null,"starsRepository":null"""))
        f.set(detailUrl("baz"), detail("baz", b1, extra = ""","stars":"7","starsRepository":"../x""""))
        val c = client(f)
        c.loadCatalog()
        c.loadDetail("foo").detail.let {
            assertEquals(7, it.stars)
            assertEquals("owner/dist", it.starsRepository)
            // The source repository is unrelated to the repository the stars belong to.
            assertEquals("owner/repo", it.source.fullName)
        }
        c.loadDetail("bar").detail.let {
            assertNull(it.stars)
            assertNull(it.starsRepository)
        }
        c.loadDetail("baz").detail.let {
            assertNull(it.stars)
            assertNull(it.starsRepository)
        }
    }

    // 用线上真实样本回归解析器，确认真实字段形状（含 stars=0）也能解析。
    @Test
    fun liveSampleShapeIsParsed() {
        val text = """
            {"schemaVersion":1,"buildId":"9445ddaac7e3757d","modules":[{"id":"example_module_demo",
             "name":"Example Module Demo","summary":"A minimal module","authors":[{"name":"KernelSU-Modules contributors",
             "url":"https://github.com/KernelSU-Modules"}],"tags":["example","tools"],"metamodule":false,
             "icon":"assets/2ebaa6f72c2ac3a7.png","stable":{"version":"v1.0.0","versionCode":100,
             "publishedAt":"2026-10-11T01:26:58Z"},"stars":0,"starsRepository":"KernelSU-Modules/example-module",
             "detail":"api/v1/modules/example_module_demo.json","updatedAt":"2026-10-11T01:38:10.702Z"}]}
        """.trimIndent()
        val m = RegistryParser().parseModules(text).modules.single()
        assertEquals(0, m.stars)
        assertEquals("KernelSU-Modules/example-module", m.starsRepository)
    }

    // kind 缺省为 module；未知 kind 或数字 kind 的条目应被隐藏，避免 UI 渲染未支持类型。
    @Test
    fun kindDefaultsToModuleAndUnknownKindsAreHidden() {
        val f = FakeFetcher()
        f.set(indexUrl, index(b1))
        f.set(
            listUrl,
            modules(
                b1,
                summary("plain"),
                summary("mod", extra = ",\"kind\":\"module\""),
                summary("tool", extra = ",\"kind\":\"script\""),
                summary("future", extra = ",\"kind\":\"theme\""),
                summary("numeric", extra = ",\"kind\":1"),
                summary("metascript", extra = ",\"kind\":\"script\"").replace("\"metamodule\":false", "\"metamodule\":true"),
            )
        )
        val modules = client(f).loadCatalog().catalog.modules
        assertEquals(listOf("plain", "mod", "tool"), modules.map { it.id })
        assertEquals(listOf("module", "module", "script"), modules.map { it.kind })
    }

    // 详情必须明确 kind；未知 kind 以及“metamodule=true 却声明 script”都属于格式错误。
    @Test
    fun detailKindIsParsedAndUnknownKindRejected() {
        val parser = RegistryParser()
        assertEquals(RegistryKind.MODULE, parser.parseDetail(detail("foo", b1)).kind)
        // 把默认的 metamodule 改为 false，才能构造合法的 script 详情。
        val script = detail("foo", b1, extra = ",\"kind\":\"script\"").replace("\"metamodule\":true", "\"metamodule\":false")
        assertEquals(RegistryKind.SCRIPT, parser.parseDetail(script).kind)
        assertThrows(RegistryFormatException::class.java) {
            parser.parseDetail(detail("foo", b1, extra = ",\"kind\":\"theme\""))
        }
        // detail() is a metamodule: a metamodule script is a manifest error.
        assertThrows(RegistryFormatException::class.java) {
            parser.parseDetail(detail("foo", b1, extra = ",\"kind\":\"script\""))
        }
    }
}
