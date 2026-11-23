package me.weishu.kernelsu.ui.screen.executemoduleaction

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.KeyEventBlocker
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors

/**
 * 模块 action 执行页的 Material 实现。
 *
 * 输出使用等宽字体并随内容自动滚到底部；FAB 只有在脚本完成后才出现，
 * 且根据滚动方向自动收起/展开，避免遮挡长日志。执行期间拦截系统返回，
 * 防止脚本仍在运行时页面被销毁。
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ExecuteModuleActionScreenMaterial(
    state: ExecuteModuleActionUiState,
    actions: ExecuteModuleActionScreenActions,
    snackBarHost: SnackbarHostState,
) {
    val scrollState = rememberScrollState()
    // 用 100dp 作为滚动方向判定阈值，过滤手指抖动产生的微小位移。
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val fabExpanded by remember {
        var previousScroll = 0
        var scrollDelta = 0f
        var expanded = true
        derivedStateOf {
            val currentScroll = scrollState.value
            val delta = (currentScroll - previousScroll).toFloat()
            scrollDelta = (scrollDelta + delta).coerceIn(-threshold, threshold)
            previousScroll = currentScroll
            if (currentScroll == 0) {
                expanded = scrollState.maxValue <= 0
                scrollDelta = 0f
            } else if (!expanded && scrollDelta >= threshold) {
                expanded = true
                scrollDelta = 0f
            } else if (expanded && scrollDelta <= -threshold) {
                expanded = false
                scrollDelta = 0f
            }
            expanded
        }
    }

    // 脚本未完成时吞掉返回事件，避免误触退出导致输出中断。
    BackHandler(enabled = !state.isComplete) { }

    ExpressiveScaffold(
        snackbarHost = {
            SnackBarHost(
                hostState = snackBarHost,
                modifier = Modifier.let { if (state.isComplete) it else it.safeDrawingPadding() })
        },
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: stringResource(R.string.action)) },
                colors = expressiveTopAppBarColors(),
                navigationIcon = {
                    TopBarBackButton(onClick = actions.onBack)
                },
                actions = {
                    IconButton(onClick = actions.onSaveLog) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = stringResource(R.string.save_log)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.isComplete) {
                SmallExtendedFloatingActionButton(
                    onClick = actions.onClose,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Filled.Close, null) },
                    text = { Text(stringResource(R.string.close)) },
                    modifier = Modifier.padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    ),
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val navBars = WindowInsets.navigationBars.asPaddingValues()
        val captionBar = WindowInsets.captionBar.asPaddingValues()
        // action 执行期间锁住音量键，防止脚本输出被系统音量条遮挡。
        KeyEventBlocker {
            it.key == Key.VolumeDown || it.key == Key.VolumeUp
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                .verticalScroll(scrollState)
        ) {
            // 每有新输出就滚到底部，模拟终端“跟随最新行”的行为。
            LaunchedEffect(state.text) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))
            Text(
                modifier = Modifier.padding(8.dp),
                text = state.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            state.resultNotice?.let { ResultNoticeMaterial(it) }
            Spacer(
                Modifier.height(
                    16.dp + navBars.calculateBottomPadding() + captionBar.calculateBottomPadding()
                )
            )
        }
    }
}

/**
 * 结果提示卡片：按成功/不适用/失败选择容器与文字颜色。
 * 颜色全部取自主题 token，保证深色/AMOLED 下对比度仍然可读。
 */
@Composable
private fun ResultNoticeMaterial(notice: ActionResultNotice) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (notice.kind) {
        ActionResultKind.SUCCESS -> colors.secondaryContainer to colors.onSecondaryContainer
        ActionResultKind.NOT_APPLICABLE -> colors.tertiaryContainer to colors.onTertiaryContainer
        ActionResultKind.FAILURE -> colors.errorContainer to colors.onErrorContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Text(
            text = notice.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
