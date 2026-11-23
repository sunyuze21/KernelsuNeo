package me.weishu.kernelsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.Natives.Profile.RootProfileFlag
import me.weishu.kernelsu.ui.util.rootAvailable

/**
 * KernelSU 的 JNI 门面：所有 native 方法都由 `libkernelsu.so` 实现，最终通过 supercall
 * IOCTL 与内核通信。这里只做类型映射，调用方需注意多数方法要求 root 或 Manager 身份。
 *
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {

    /** KernelSU 进程使用的 SELinux 域，作为 root profile 的默认 context。 */
    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"

    /** root 用户的 UID/GID，profile 未显式指定身份时使用。 */
    const val ROOT_UID = 0
    /** root 用户的主组 ID。 */
    const val ROOT_GID = 0

    // 加载 JNI 实现；若 so 缺失，后续任何 native 调用都会抛 UnsatisfiedLinkError。
    init {
        System.loadLibrary("kernelsu")
    }

    /** Manager 与内核协议版本号，两侧一致才认为功能完整。 */
    val version: Int
        external get

    /** 内核是否处于安全模式（通常因模块导致 bootloop 后自动进入）。 */
    val isSafeMode: Boolean
        external get

    /** 当前是否为 LKM（可加载内核模块）模式运行。 */
    val isLkmMode: Boolean
        external get

    /** LKM 是否随 Manager 一起打包（内置方式加载）。 */
    val isLkmBundled: Boolean
        external get

    /** 是否为晚加载（late load）模式；该模式下重启行为与普通模式不同。 */
    val isLateLoadMode: Boolean
        external get

    /** 当前调用者是否就是 Manager 自身；部分 supercall 仅对 Manager 开放。 */
    val isManager: Boolean
        external get

    /** 是否为 PR 构建版本，用于在 UI 上提示非正式发布渠道。 */
    val isPrBuild: Boolean
        external get

    /** 指定 UID 的应用是否应卸载 KernelSU 挂载的模块（由内核按 profile 策略判定）。 */
    external fun uidShouldUmount(uid: Int): Boolean

    /**
     * Get the profile of the given package.
     * @param key usually the package name
     * @return return null if failed.
     */
    external fun getAppProfile(key: String?, uid: Int): Profile
    /** 写入指定应用的 profile（含默认 profile）；返回是否成功，失败时内核状态不变。 */
    external fun setAppProfile(profile: Profile?): Boolean

    /**
     * `su` compat mode can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSuEnabled(): Boolean
    /** 临时开启/关闭 su 兼容；返回值表示内核是否接受该设置。 */
    external fun setSuEnabled(enabled: Boolean): Boolean

    /**
     * Kernel module umount can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isKernelUmountEnabled(): Boolean
    /** 临时开启/关闭模块 umount；关闭后模块挂载会对所有应用可见。 */
    external fun setKernelUmountEnabled(enabled: Boolean): Boolean

    /**
     * SELinux hide can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSelinuxHideEnabled(): Boolean
    /** 临时开启/关闭 SELinux 隐藏；返回非 0 表示内核侧错误码。 */
    external fun setSelinuxHideEnabled(enabled: Boolean): Int

    /**
     * Get the user name for the uid.
     */
    external fun getUserName(uid: Int): String?

    /** 超级用户（已被授权 root 的应用）数量，用于首页统计。 */
    external fun getSuperuserCount(): Int

    // 非 root 应用的全局默认 profile 使用特殊 key/UID，内核以此识别默认项。
    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    /** 默认 profile 使用 nobody 的 UID，避免与真实应用 UID 冲突。 */
    private const val NOBODY_UID = 9999

    /**
     * 修改非 root 应用的默认 profile 中“是否卸载模块”一项。
     * 注意：这里会整体写回默认 profile，其他字段使用构造时的默认值。
     */
    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        Profile(
            NON_ROOT_DEFAULT_PROFILE_KEY,
            NOBODY_UID,
            false,
            umountModules = umountModules
        ).let {
            return setAppProfile(it)
        }
    }

    /** 读取非 root 默认 profile 的“是否卸载模块”设置。 */
    fun isDefaultUmountModules(): Boolean {
        getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID).let {
            return it.umountModules
        }
    }

    /** 内核侧 UAPI 版本；与 [managerUAPIVersion] 比较可判断是否支持全部功能。 */
    val kernelUAPIVersion: Int
        external get

    /** Manager 侧编译时的 UAPI 版本。 */
    val managerUAPIVersion: Int
        external get

    /** 仅当是 Manager 身份、两侧 UAPI 版本一致且已获得 root 时，才启用完整功能。 */
    fun isFullFeatured(): Boolean {
        return isManager && kernelUAPIVersion == managerUAPIVersion && rootAvailable()
    }

    /**
     * 应用 profile：既是内核持久化的策略结构，也是 UI 编辑对象。
     * root 与非 root 各有一套字段；`UseDefault` 为 true 时对应字段不生效。
     */
    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        /** true 表示 root 身份沿用全局 root 默认模板，忽略下面的 root 字段。 */
        val rootUseDefault: Boolean = true,
        /** 引用的 root 模板 id；非空时模板内容会覆盖 root 字段（由 ksud/Manager 处理）。 */
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        /** 额外补充组 GID 列表，直接传给 setgroups。 */
        val groups: List<Int> = mutableListOf(),
        /** Linux capability 编号列表，由内核在授予 root 时应用。 */
        val capabilities: List<Int> = mutableListOf(),
        /** 进程 SELinux context，默认进入 KernelSU 域。 */
        val context: String = KERNEL_SU_DOMAIN,
        /** mount namespace 策略，取值对应 [Namespace] 的 ordinal。 */
        val namespace: Int = Namespace.INHERITED.ordinal,

        /** true 表示非 root 身份沿用全局非 root 默认 profile。 */
        val nonRootUseDefault: Boolean = true,
        /** 非 root 应用是否看不到 KernelSU 挂载的模块。 */
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!

        /** root profile 的位标志集合，见 [FLAG_KSU_NO_NEW_PRIVS]。 */
        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        /** root profile 可选的布尔标志；[desc] 为对应的说明文案资源。 */
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            /** 禁止通过 execve 提升权限（对应 PR_SET_NO_NEW_PRIVS）。 */
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        /** root 进程的 mount namespace 处理方式。 */
        enum class Namespace {
            /** 继承调用方的 namespace（默认）。 */
            INHERITED,
            /** 使用全局 namespace。 */
            GLOBAL,
            /** 为每个应用创建独立 namespace。 */
            INDIVIDUAL,
        }

        /** 供 Parcelable / 反序列化使用的空 profile。 */
        constructor() : this("")
    }

    /** flags 中“no new privileges”对应的位；新增标志时按 ordinal 左移，不能复用旧位。 */
    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

/** 将标志列表按 ordinal 折叠成内核使用的位掩码。 */
fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

/** 将标志列表转成 ordinal 列表，用于持久化到 JSON。 */
fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

/** 从位掩码还原标志列表，未知位会被忽略。 */
fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
