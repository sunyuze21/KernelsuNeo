package me.weishu.kernelsu.ui.screen.flash

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
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
 * Material 3 主题下的刷写页：等宽字体实时日志、保存日志按钮，
 * 成功后右下角按钮在重启/关闭之间切换。
 */
@Composable
fun FlashScreenMaterial(
    state: FlashUiState,
    actions: FlashScreenActions,
    snackBarHost: SnackbarHostState,
) {
    // 日志持续追加，滚动位置由下方 LaunchedEffect 自动跟随到底部
    val scrollState = rememberScrollState()
    if (state.showJailbreakWarning) {
        JailbreakFlashWarningDialog(
            onConfirm = actions.onConfirmJailbreakWarning,
            onDismiss = actions.onDismissJailbreakWarning,
        )
    }

    ExpressiveScaffold(
        snackbarHost = {
            // 成功时已有 FAB，避免 SafeDrawing 额外留白；失败/进行中需要避开系统栏
            SnackBarHost(
                hostState = snackBarHost,
                modifier = Modifier.let { if (state.flashingStatus == FlashingStatus.SUCCESS) it else it.safeDrawingPadding() })
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (state.flashingStatus) {
                                FlashingStatus.FLASHING -> R.string.flashing
                                FlashingStatus.SUCCESS -> R.string.flash_success
                                FlashingStatus.FAILED -> R.string.flash_failed
                            }
                        )
                    )
                },
                colors = expressiveTopAppBarColors(),
                navigationIcon = {
                    TopBarBackButton(onClick = actions.onBack)
                },
                actions = {
                    IconButton(onClick = actions.onSaveLog) {
                        Icon(Icons.Filled.Save, stringResource(R.string.save_log))
                    }
                }
            )
        },
        floatingActionButton = {
            // 仅成功时展示操作按钮；是否需要重启由刷写结果决定
            if (state.flashingStatus == FlashingStatus.SUCCESS) {
                val isRebootAction = state.showRebootAction
                SmallExtendedFloatingActionButton(
                    onClick = { if (!state.actionInProgress) { if (isRebootAction) actions.onReboot() else actions.onClose() } },
                    // 执行中收起文字只留进度圈，防止布局跳动
                    expanded = !state.actionInProgress,
                    icon = {
                        if (state.actionInProgress) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else if (isRebootAction) {
                            Icon(Icons.Filled.Refresh, null)
                        } else {
                            Icon(Icons.Filled.Close, null)
                        }
                    },
                    text = {
                        Text(
                            stringResource(if (isRebootAction) state.rebootLabelRes else R.string.close)
                        )
                    },
                    modifier = Modifier.padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    )
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val navBars = WindowInsets.navigationBars.asPaddingValues()
        val captionBar = WindowInsets.captionBar.asPaddingValues()
        // 刷写过程中屏蔽音量键，避免误触进入 recovery/fastboot
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
            // 每次日志更新后滚到底部，让用户看到最新输出
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
            Spacer(
                Modifier.height(
                    16.dp + 54.dp + navBars.calculateBottomPadding() + captionBar.calculateBottomPadding()
                )
            )
        }
    }
}
