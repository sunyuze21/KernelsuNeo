package me.weishu.kernelsu.ui.screen.modulerepo

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 详情页展示所需的作者信息；Parcelable 以便通过导航参数传递。 */
@Parcelize
data class AuthorArg(
    val name: String,
    val link: String,
) : Parcelable

/** Navigation argument: the list entry a detail page was opened from. */
@Parcelize
data class RepoModuleArg(
    val moduleId: String,
    val moduleName: String,
    val authors: String,
    val authorsList: List<AuthorArg>,
    val latestVersion: String,
    val latestVersionCode: Int,
    val latestReleaseTime: String,
    val metamodule: Boolean,
    val script: Boolean = false,
) : Parcelable
