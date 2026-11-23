package me.weishu.kernelsu.ui.component.bottombar

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.util.shouldShowSplitPane
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/**
 * 主界面 Pager 的导航状态包装。
 *
 * 把“逻辑选中页”与 Pager 实际滚动位置解耦：[selectedPage] 在导航开始时立即更新，
 * 让导航栏高亮即时响应；[isNavigating] 用于抑制滚动动画期间被外部回调反向覆盖。
 */
class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
    private val animatePageChanges: Boolean,
) {
    // 供导航栏使用的选中索引，点击瞬间即更新
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    // 程序化滚动进行中标记，避免滚动中途的 currentPage 变更把选中态拉回去
    var isNavigating by mutableStateOf(false)
        private set

    // 当前导航任务，新的导航会取消旧任务，避免动画相互抢占
    private var navJob: Job? = null

    /**
     * 滚动到目标页。动画/瞬时由 [animatePageChanges] 决定；
     * 无论成功与否都会在 finally 中校正选中页，保证与 Pager 实际位置一致。
     */
    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()

        // 先乐观更新 UI，再启动滚动
        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                if (animatePageChanges) {
                    pagerState.springAnimateToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                // 只有当自己仍是最新任务时才清标记，防止被取消的旧任务覆盖新状态
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    /**
     * 用 Pager 的实际位置同步选中页，供用户手动滑动后调用。
     * 程序化滚动期间跳过，避免动画中途抖动。
     */
    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

/**
 * 创建并记住 [MainPagerState]。key 中包含 scope 与动画开关，
 * 配置变化时重建，避免持有失效的 Pager 引用。
 */
@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    animatePageChanges: Boolean = true,
): MainPagerState {
    return remember(pagerState, coroutineScope, animatePageChanges) {
        MainPagerState(pagerState, coroutineScope, animatePageChanges)
    }
}

/**
 * 导航栏角标数据源。三个计数彼此独立，具体优先级由 [badgeFor] 决定。
 */
@Immutable
data class NavigationBadgeState(
    val superuserCount: Int = 0,
    val moduleEnabledCount: Int = 0,
    val moduleUpdatableCount: Int = 0,
)

// 角标色调：Alert 用于需要处理的更新，Accent 用于提示数量
internal enum class BadgeTone { Alert, Accent }

/**
 * 已解析出的角标内容。[tone] 决定配色，[count] 为展示数字。
 */
@Immutable
internal data class NavBadge(val count: Int, val tone: BadgeTone)

/**
 * 按目标页索引计算角标。
 * 模块页优先展示“可更新数”（更紧急），其次才是已启用数；无内容时返回 null 表示不显示。
 */
internal fun badgeFor(index: Int, state: NavigationBadgeState): NavBadge? = when (index) {
    BottomBarDestination.SuperUser.ordinal ->
        state.superuserCount.takeIf { it > 0 }?.let { NavBadge(it, BadgeTone.Accent) }

    BottomBarDestination.Module.ordinal -> when {
        state.moduleUpdatableCount > 0 -> NavBadge(state.moduleUpdatableCount, BadgeTone.Alert)
        state.moduleEnabledCount > 0 -> NavBadge(state.moduleEnabledCount, BadgeTone.Accent)
        else -> null
    }

    else -> null
}

/**
 * 是否使用侧边导航栏：仅在分屏/大屏下启用，
 * 但 Miuix 模式开启悬浮底栏时仍保持底栏，以贴合该风格的交互习惯。
 */
@Composable
fun useNavigationRail(enableFloatingBottomBar: Boolean): Boolean {
    return shouldShowSplitPane() && !(LocalUiMode.current == UiMode.Miuix && enableFloatingBottomBar)
}

/**
 * 底部导航入口：按当前 UI 模式分派到 Miuix 或 Material 实现。
 * [blurBackdrop]/[backdrop] 仅在 Miuix 液态玻璃模式下使用。
 */
@Composable
fun BottomBar(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> BottomBarMiuix(blurBackdrop, backdrop, navigationBadge, modifier)
        UiMode.Material -> BottomBarMaterial(navigationBadge)
    }
}

/**
 * 侧边导航入口：分派到对应 UI 模式的导航栏实现。
 */
@Composable
fun SideRail(
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> NavigationRailMiuix(navigationBadge, modifier)
        UiMode.Material -> NavigationRailMaterial(navigationBadge, modifier)
    }
}
