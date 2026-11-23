package me.weishu.kernelsu.ui.screen.install

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.R

/**
 * 安装方式：选择本地 boot 镜像、下载远程镜像、直接安装到当前分区，
 * 或（A/B 设备）安装到非活动槽位。实现 Parcelable 以便放入导航路由参数。
 */
@Parcelize
internal sealed class InstallMethod : Parcelable {
    /**
     * 从用户选中的文件安装；[uri] 为空时仍需由 UI 先触发文件选择。
     * [summary] 展示在选项下方的说明文案，不参与 Parcel 序列化。
     */
    data class SelectFile(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.select_file,
        override val summary: String?
    ) : InstallMethod()

    /**
     * 从远程地址下载 boot 镜像后安装；[partition] 在下载探测完成后回填，
     * 因此确认下载前可能为 null。
     */
    data class DownloadFile(
        val url: String? = null,
        val partition: String? = null,
        @get:StringRes override val label: Int = R.string.download_file,
        override val summary: String?
    ) : InstallMethod()

    /** 直接安装到当前活动分区，需要已有 root 且设备为 GKI。 */
    data object DirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.direct_install
    }

    /** OTA 式安装：写入当前系统的非活动槽位，重启切换后才生效。 */
    data object DirectInstallToInactiveSlot : InstallMethod() {
        override val label: Int
            get() = R.string.install_inactive_slot
    }

    /** 各安装方式的标题字符串资源，供 UI 统一渲染。 */
    abstract val label: Int

    /** 可选说明文案；用 @IgnoredOnParcel 避免把临时 UI 文案写入 Parcel。 */
    @IgnoredOnParcel
    open val summary: String? = null
}

/**
 * 判断选中的 Uri 是否为内核模块文件（.ko）。
 * 部分文件提供方不暴露扩展名，因此先看路径后缀，再回退查询 DISPLAY_NAME；
 * 查询失败时按“非 .ko”处理，避免误选镜像导致刷入失败。
 */
fun isKoFile(context: Context, uri: Uri): Boolean {
    val seg = uri.lastPathSegment ?: ""
    if (seg.endsWith(".ko", ignoreCase = true)) return true

    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            // 按列名而非列下标取值，避免不同 ContentProvider 返回列顺序不一致
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                name?.endsWith(".ko", ignoreCase = true) == true
            } else {
                false
            }
        } ?: false
    } catch (_: Throwable) {
        false
    }
}
