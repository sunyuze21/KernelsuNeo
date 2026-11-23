package me.weishu.kernelsu.ui.screen.modulerepo

import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.RepoModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.Locale

/** 覆盖模块仓库排序枚举的持久化迁移、排序规则、星标格式化与空状态提示。 */
class RepoSortTest {

    // 新格式优先按枚举名读取，序号只在不同版本间作为兜底。
    @Test
    fun storedNameWins() {
        assertEquals(RepoSort.NAME, RepoSort.fromStored("NAME", 0))
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored("UPDATED", 2))
        assertEquals(RepoSort.STARS, RepoSort.fromStored("STARS", null))
        assertEquals(RepoSort.STARS, RepoSort.fromStored("STARS", 0))
    }

    // 旧版本只存序号，升级后必须按旧枚举顺序迁移，否则用户排序偏好会错位。
    @Test
    fun legacyOrdinalsAreMigrated() {
        // Old enum: UPDATED(0), CREATED(1), NAME(2), STARS(3).
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored(null, 0))
        // CREATED 已移除，按最接近的 UPDATED 处理。
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored(null, 1))
        assertEquals(RepoSort.NAME, RepoSort.fromStored(null, 2))
        assertEquals(RepoSort.STARS, RepoSort.fromStored(null, 3))
        // 越界序号不能导致崩溃，统一回退到默认排序。
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored(null, 42))
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored(null, -1))
    }

    // 名称未知时继续尝试序号；两者都不可用时回退默认 UPDATED。
    @Test
    fun unknownOrMissingValuesFallBack() {
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored(null, null))
        assertEquals(RepoSort.UPDATED, RepoSort.fromStored("CREATED", null))
        assertEquals(RepoSort.NAME, RepoSort.fromStored("bogus", 2))
        assertEquals(RepoSort.STARS, RepoSort.fromStored("bogus", 3))
    }

    // 构造排序用 RepoModule；只关心 id/星标/发布时间/名称，其余字段填最小合法值。
    private fun module(id: String, stars: Int?, epoch: Long, name: String = id) = RepoModule(
        moduleId = id,
        moduleName = name,
        authors = "",
        authorList = emptyList(),
        summary = "",
        tags = emptyList(),
        metamodule = false,
        iconUrl = null,
        latestVersion = "1.0",
        latestVersionCode = 1,
        latestReleaseTime = "",
        latestReleaseEpoch = epoch,
        stars = stars,
        starsRepository = null,
    )

    // 固定样本：星标有 null/0/并列，名称含大小写混排，用于验证稳定排序。
    private val modules = listOf(
        module("a", stars = 5, epoch = 100, name = "Charlie"),
        module("b", stars = null, epoch = 500, name = "alpha"),
        module("c", stars = 50, epoch = 10, name = "Bravo"),
        module("d", stars = 5, epoch = 300, name = "delta"),
        module("e", stars = 0, epoch = 50, name = "Echo"),
        module("f", stars = null, epoch = 20, name = "foxtrot"),
    )

    // 星标降序，null 排在 0 之后；同星标按最新 release 时间降序。
    @Test
    fun starsSortByCountThenNewestRelease() {
        // Unknown counts come after 0; ties are broken by the newest release.
        assertEquals(listOf("c", "d", "a", "e", "b", "f"), RepoSort.STARS.sort(modules).map { it.moduleId })
    }

    // 其他排序保持原有语义，避免新增 STARS 影响既有行为。
    @Test
    fun otherSortsAreUnchanged() {
        assertEquals(listOf("b", "d", "a", "e", "f", "c"), RepoSort.UPDATED.sort(modules).map { it.moduleId })
        // NAME 忽略大小写，并按 Locale 规则比较（示例用英文）。
        assertEquals(
            listOf("alpha", "Bravo", "Charlie", "delta", "Echo", "foxtrot"),
            RepoSort.NAME.sort(modules, Locale.ENGLISH).map { it.moduleName }
        )
        assertEquals(emptyList<RepoModule>(), RepoSort.STARS.sort(emptyList()))
    }

    // 星标展示为紧凑格式：千以下原样、千/M 后缀、最多一位小数、Int 上限不能溢出。
    @Test
    fun starCountIsCompact() {
        assertEquals("0", formatStarCount(0))
        assertEquals("999", formatStarCount(999))
        assertEquals("1k", formatStarCount(1_000))
        assertEquals("1k", formatStarCount(1_049))
        assertEquals("1.2k", formatStarCount(1_234))
        assertEquals("12.3k", formatStarCount(12_345))
        assertEquals("99.9k", formatStarCount(99_999))
        assertEquals("123k", formatStarCount(123_456))
        assertEquals("999k", formatStarCount(999_999))
        assertEquals("1M", formatStarCount(1_000_000))
        assertEquals("2.5M", formatStarCount(2_500_000))
        assertEquals("2147M", formatStarCount(Int.MAX_VALUE))
    }

    // 空状态只在“已加载完成且无数据”或“已加载且出错”时显示，加载中仍显示进度。
    @Test
    fun emptyStateMessageOnlyOnceSomethingWasLoaded() {
        // Not loaded yet, or (re)loading: keep the progress indicator.
        assertNull(ModuleRepoUiState().emptyStateMessage)
        assertNull(ModuleRepoUiState(isRefreshing = true).emptyStateMessage)
        assertNull(ModuleRepoUiState(isRefreshing = true, hasLoaded = true).emptyStateMessage)
        // Loaded, but the repository lists no modules.
        assertEquals(R.string.module_repo_empty, ModuleRepoUiState(hasLoaded = true).emptyStateMessage)
        // 有数据时不显示空状态。
        assertNull(ModuleRepoUiState(hasLoaded = true, modules = listOf(module("a", null, 0))).emptyStateMessage)
        assertEquals(R.string.module_repo_load_failed, ModuleRepoUiState(error = IOException("x")).emptyStateMessage)
    }

    // 星标链接指向星标仓库的 stargazers 页面；无仓库信息时不生成链接。
    @Test
    fun starsLinkPointsToTheStarsRepository() {
        assertEquals("https://github.com/owner/dist/stargazers", starsUrl("owner/dist"))
        assertNull(starsUrl(null))
    }
}
