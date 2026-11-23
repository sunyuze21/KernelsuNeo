package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖 registry URL 白名单：release 下载、重定向跳转、同源资源与页面内锚点。 */
class RegistryUrlsTest {

    // 合法的 GitHub release 下载地址，作为几乎所有正向断言的基础样本。
    private val good = "https://github.com/owner/repo/releases/download/v1.0.0/module.zip"

    // 只允许 https 的 GitHub release 下载路径，大小写、合法 owner/repo 字符与转义文件名可接受。
    @Test
    fun acceptsGithubReleaseDownloads() {
        assertTrue(RegistryUrls.isAllowedReleaseDownloadUrl(good))
        assertTrue(RegistryUrls.isAllowedReleaseDownloadUrl("https://GitHub.com/o-1/r.e_p/releases/download/v2/a%20b.zip"))
    }

    // 非 https、仿冒域名、对象存储、archive 路径、路径穿越、端口/凭据/query/fragment 等全部拒绝。
    @Test
    fun rejectsEverythingElse() {
        val bad = listOf(
            "http://github.com/owner/repo/releases/download/v1/module.zip",
            "https://github.com.evil.com/owner/repo/releases/download/v1/module.zip",
            "https://evil.com/owner/repo/releases/download/v1/module.zip",
            "https://objects.githubusercontent.com/owner/repo/releases/download/v1/module.zip",
            "https://github.com/owner/repo/archive/refs/tags/v1.zip",
            "https://github.com/owner/repo/releases/download/module.zip",
            "https://github.com/owner/repo/releases/download/v1/../../../x/module.zip",
            "https://github.com/owner/repo/releases/download/v1/%2e%2e/module.zip",
            "https://github.com/owner/repo/releases/download/v1/a%2Fb.zip",
            "https://user@github.com/owner/repo/releases/download/v1/module.zip",
            "https://github.com:8443/owner/repo/releases/download/v1/module.zip",
            "https://github.com/owner/repo/releases/download/v1/module.zip?x=1",
            "https://github.com/owner/repo/releases/download/v1/module.zip#frag",
            "https://github.com//repo/releases/download/v1/module.zip",
            "javascript:alert(1)",
            "not a url",
            "",
        )
        for (url in bad) assertFalse(url, RegistryUrls.isAllowedReleaseDownloadUrl(url))
    }

    // 下载重定向只允许 GitHub 资产域名；raw.githubusercontent 等不在白名单内。
    @Test
    fun redirectHops() {
        assertTrue(RegistryUrls.isAllowedDownloadHop(good))
        assertTrue(RegistryUrls.isAllowedDownloadHop("https://objects.githubusercontent.com/github-production-release-asset/1?sig=x"))
        assertTrue(RegistryUrls.isAllowedDownloadHop("https://release-assets.githubusercontent.com/github-production-release-asset/1?sp=r"))
        assertFalse(RegistryUrls.isAllowedDownloadHop("http://objects.githubusercontent.com/x"))
        assertFalse(RegistryUrls.isAllowedDownloadHop("https://raw.githubusercontent.com/x"))
        assertFalse(RegistryUrls.isAllowedDownloadHop("https://evil.com/x"))
    }

    // API 相对路径只能拼接到站点根，绝对/跨域/带 query 或空路径一律拒绝。
    @Test
    fun resolvesApiPathsAgainstSiteRoot() {
        assertEquals(
            "https://modules.kernelsu.org/api/v1/modules.json",
            RegistryUrls.resolve(RegistryUrls.DEFAULT_BASE_URL, "api/v1/modules.json")
        )
        // base 带子路径时应保留该前缀，而不是回到域名根。
        assertEquals(
            "https://example.org/mirror/api/v1/index.json",
            RegistryUrls.resolve("https://example.org/mirror", "api/v1/index.json")
        )
        for (bad in listOf("/api/v1/x.json", "../x.json", "api/../x.json", "https://evil.com/x", "a?b", "")) {
            assertThrows(bad, RegistryFormatException::class.java) { RegistryUrls.resolve(RegistryUrls.DEFAULT_BASE_URL, bad) }
        }
        // base 必须是 https，http 配置应尽早失败。
        assertThrows(IllegalArgumentException::class.java) { RegistryUrls.normalizeBaseUrl("http://modules.kernelsu.org/") }
    }

    // IDN/百分号编码/尾点等变体不能绕过域名比较；punycode 按字面比较。
    @Test
    fun idnAndPunycodeHostsNeverMatch() {
        // Non-ASCII hosts do not parse as a server authority; punycode is compared literally.
        for (url in listOf(
            "https://gíthub.com/owner/repo/releases/download/v1/module.zip",
            "https://xn--gthub-zqa.com/owner/repo/releases/download/v1/module.zip",
            "https://github.com./owner/repo/releases/download/v1/module.zip",
            "https://github.com%2eevil.com/owner/repo/releases/download/v1/module.zip",
        )) {
            assertFalse(url, RegistryUrls.isAllowedReleaseDownloadUrl(url))
            assertFalse(url, RegistryUrls.isAllowedDownloadHop(url))
        }
        val base = RegistryUrls.DEFAULT_BASE_URL
        assertFalse(RegistryUrls.isSameOrigin("https://modules.kérnelsu.org/x.png", base))
        assertFalse(RegistryUrls.isSameOrigin("https://xn--modules-kernelsu-xyz.org/x.png", base))
        // 双方都用 punycode 且主机一致时应视为同源。
        assertTrue(RegistryUrls.isSameOrigin("https://xn--mnchen-3ya.de/a", "https://XN--MNCHEN-3YA.de/"))
        // 一边 Unicode 一边 punycode 不能自动等价，避免同形异义攻击。
        assertFalse(RegistryUrls.isSameOrigin("https://xn--mnchen-3ya.de/a", "https://münchen.de/"))
    }

    // 同源比较需同时匹配 scheme、host 与端口，默认 https 端口 443 可省略。
    @Test
    fun sameOriginComparesSchemeHostAndPort() {
        val base = RegistryUrls.DEFAULT_BASE_URL
        assertTrue(RegistryUrls.isSameOrigin("https://modules.kernelsu.org/assets/a.png", base))
        assertTrue(RegistryUrls.isSameOrigin("HTTPS://Modules.KernelSU.org:443/assets/a.png", base))
        assertFalse(RegistryUrls.isSameOrigin("http://modules.kernelsu.org/assets/a.png", base))
        assertFalse(RegistryUrls.isSameOrigin("https://modules.kernelsu.org:8443/assets/a.png", base))
        assertFalse(RegistryUrls.isSameOrigin("https://evil.modules.kernelsu.org/a.png", base))
        // 后缀仿冒域名（base 作为前缀）也必须拒绝。
        assertFalse(RegistryUrls.isSameOrigin("https://modules.kernelsu.org.evil.com/a.png", base))
        assertFalse(RegistryUrls.isSameOrigin("ftp://modules.kernelsu.org/a.png", "ftp://modules.kernelsu.org/"))
        assertFalse(RegistryUrls.isSameOrigin("not a url", base))
    }

    // 受限资源（图标/图片）及其每一跳重定向都按同源规则校验，防止 data:/file:/javascript: 注入。
    @Test
    fun restrictedResourcesAndRedirectHops() {
        val base = RegistryUrls.DEFAULT_BASE_URL
        // Every hop of a redirect chain is checked with the same rule.
        val chain = listOf(
            "https://modules.kernelsu.org/assets/a.png" to true,
            "https://modules.kernelsu.org/assets/b.png?v=2" to true,
            "https://github.com/owner/repo/raw/main/a.png" to false,
            "https://objects.githubusercontent.com/x" to false,
            "http://modules.kernelsu.org/assets/a.png" to false,
            "https://modules.kernelsu.org:444/assets/a.png" to false,
            "https://user@modules.kernelsu.org/assets/a.png" to false,
            "data:image/png;base64,AAAA" to false,
            "file:///data/data/x" to false,
            "javascript:alert(1)" to false,
        )
        for ((url, allowed) in chain) {
            assertEquals(url, allowed, RegistryUrls.isAllowedRestrictedResource(url, base))
        }
    }

    // 仅同源页面的非空 fragment 视为页内锚点；空锚点、其他域名或带路径的 URL 不算。
    @Test
    fun inPageAnchors() {
        val base = RegistryUrls.DEFAULT_BASE_URL
        assertTrue(RegistryUrls.isInPageAnchor("https://modules.kernelsu.org/#install", base))
        assertFalse(RegistryUrls.isInPageAnchor("https://modules.kernelsu.org/#", base))
        assertFalse(RegistryUrls.isInPageAnchor("https://modules.kernelsu.org/", base))
        assertFalse(RegistryUrls.isInPageAnchor("https://modules.kernelsu.org/en/#install", base))
        // fragment 里带完整 URL 仍属于跨域内容，不能当作页内锚点。
        assertFalse(RegistryUrls.isInPageAnchor("https://evil.com/#https://modules.kernelsu.org/", base))
    }

    // 外链只允许无凭据的 https；WebView 内部导航可放开 http，但拒绝 ftp/mailto/intent。
    @Test
    fun linkSchemes() {
        assertTrue(RegistryUrls.isHttpsUrl("https://github.com/o/r"))
        assertFalse(RegistryUrls.isHttpsUrl("http://github.com/o/r"))
        assertFalse(RegistryUrls.isHttpsUrl("https://user:pw@github.com/o/r"))
        assertFalse(RegistryUrls.isHttpsUrl("javascript:alert(1)"))
        assertFalse(RegistryUrls.isHttpsUrl("intent://x#Intent;end"))
        assertTrue(RegistryUrls.isWebUrl("http://example.com/"))
        assertTrue(RegistryUrls.isWebUrl("https://example.com/"))
        assertFalse(RegistryUrls.isWebUrl("ftp://example.com/"))
        assertFalse(RegistryUrls.isWebUrl("mailto:a@b.c"))
    }
}
