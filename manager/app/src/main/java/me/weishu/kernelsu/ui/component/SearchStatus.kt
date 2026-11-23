package me.weishu.kernelsu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 搜索栏的动画状态机。搜索栏的展开/收起以 [current] 为唯一来源，
 * 动画结束由外部回调 [onAnimationComplete] 推动状态收敛，避免动画中重复触发。
 */
@Stable
data class SearchStatus(
    val label: String,
    val searchText: String = "",
    // 当前动画阶段，默认收起
    val current: Status = Status.COLLAPSED,
    // 搜索栏纵向偏移，用于跟手动画
    val offsetY: Dp = 0.dp,
    // 搜索结果状态，决定展示空态/加载/列表
    val resultStatus: ResultStatus = ResultStatus.DEFAULT
) {
    /** 是否已完全展开 */
    fun isExpand() = current == Status.EXPANDED
    /** 是否已完全收起 */
    fun isCollapsed() = current == Status.COLLAPSED
    /** 是否处于展开态（含展开动画中），用于抑制收起相关副作用 */
    fun shouldExpand() = current == Status.EXPANDED || current == Status.EXPANDING
    /** 是否处于收起态（含收起动画中），用于决定顶栏是否可见 */
    fun shouldCollapsed() = current == Status.COLLAPSED || current == Status.COLLAPSING
    /** 是否正在播放展开动画 */
    fun isAnimatingExpand() = current == Status.EXPANDING

    /**
     * 动画结束后的状态收敛：展开结束落到 EXPANDED，收起结束清空关键字并落到 COLLAPSED。
     * 只处理中间态，其它状态原样返回，防止重复回调造成状态错乱。
     */
    fun onAnimationComplete(): SearchStatus {
        return when (current) {
            Status.EXPANDING -> copy(current = Status.EXPANDED)
            // 收起后清空搜索词，避免下次展开残留旧过滤条件
            Status.COLLAPSING -> copy(searchText = "", current = Status.COLLAPSED)
            else -> this
        }
    }

    /**
     * 顶栏淡入淡出容器：背景层常驻铺满，内容层只改 alpha。
     * 用 graphicsLayer 而非条件渲染，保证高度测量稳定、避免顶栏高度跳动影响列表。
     */
    @Composable
    fun TopAppBarAnim(
        modifier: Modifier = Modifier,
        visible: Boolean = shouldCollapsed(),
        backgroundColor: Color = colorScheme.surface,
        content: @Composable () -> Unit
    ) {
        Box(modifier = modifier) {
            // 背景独立铺满，内容淡出时也不会露出下层
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(backgroundColor)
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = if (visible) 1f else 0f }
            ) { content() }
        }
    }

    /** 搜索栏动画阶段 */
    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
    /** 搜索结果展示状态：默认 / 无结果 / 加载中 / 展示列表 */
    enum class ResultStatus { DEFAULT, EMPTY, LOAD, SHOW }
}
