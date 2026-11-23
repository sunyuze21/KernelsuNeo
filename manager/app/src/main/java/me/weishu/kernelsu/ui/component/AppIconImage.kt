package me.weishu.kernelsu.ui.component

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.util.AppIconCache
import top.yukonga.miuix.kmp.theme.MiuixTheme


// 图标缓存键：uid + 包名 + 来源目录，任一变化说明可能是升级/重装，需要重新取图
private data class IconKey(val uid: Int, val packageName: String, val sourceDir: String?)

/**
 * 应用图标展示组件。优先走内存缓存避免主线程解码；
 * 未命中时异步加载并淡入，先占位再替换，减少列表滚动时的卡顿与闪烁。
 */
@Composable
fun AppIconImage(
    modifier: Modifier = Modifier,
    applicationInfo: ApplicationInfo,
    label: String,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    // 固定按 48dp 目标尺寸取图，避免按控件实际大小反复解码
    val targetSizePx = with(density) { 48.dp.roundToPx() }

    val iconKey = IconKey(applicationInfo.uid, applicationInfo.packageName, applicationInfo.sourceDir)

    Box(modifier = modifier) {
        // 首次组合时先判断缓存命中，命中则同步出图，避免多余的一次 Crossfade
        val initiallyCached = remember(iconKey) {
            AppIconCache.getCached(applicationInfo, targetSizePx) != null
        }

        // 图标位图状态；缓存键变化时重置，防止展示上一个包的图标
        var appBitmap by remember(iconKey) {
            mutableStateOf(AppIconCache.getCached(applicationInfo, targetSizePx))
        }

        // 仅在未命中缓存时触发异步加载，命中时不再发起 IO
        if (!initiallyCached) {
            LaunchedEffect(iconKey) {
                appBitmap = AppIconCache.loadIcon(context, applicationInfo, targetSizePx)
            }
        }

        val icon = appBitmap
        if (initiallyCached && icon != null) {
            // 缓存命中走同步直出
            val imageBitmap = remember(icon) { icon.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 异步加载路径用 Crossfade 平滑替换占位图
            Crossfade(
                targetState = icon,
                animationSpec = tween(durationMillis = 150),
                label = "IconFade"
            ) { bitmap ->
                if (bitmap != null) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlaceHolderBox(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * 以 [PackageInfo] 为入参的重载。applicationInfo 可能为 null（已卸载或异常包），
 * 此时直接展示占位块，避免空指针。
 */
@Composable
fun AppIconImage(
    modifier: Modifier = Modifier,
    packageInfo: PackageInfo,
    label: String,
) {
    val appInfo = packageInfo.applicationInfo
    if (appInfo == null) {
        PlaceHolderBox(modifier)
        return
    }
    AppIconImage(modifier, appInfo, label)
}

/**
 * 图标占位块：按当前 UI 模式取对应的次级容器色，保证 Material/Miuix 下观感一致。
 */
@Composable
private fun PlaceHolderBox(modifier: Modifier = Modifier) {
    // 占位色需跟随当前主题，避免切换 UI 模式后出现突兀的色块
    val containerColor = when (LocalUiMode.current) {
        UiMode.Material -> MaterialTheme.colorScheme.secondaryContainer
        UiMode.Miuix -> MiuixTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
    )
}
