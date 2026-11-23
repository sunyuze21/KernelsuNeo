package me.weishu.kernelsu

import android.system.Os

/**
 * @author weishu
 * @date 2022/12/10.
 */

/** 内核版本号（主版本.次版本.补丁号），由 `uname -r` 的 release 字符串解析而来。 */
data class KernelVersion(val major: Int, val patchLevel: Int, val subLevel: Int) {
    /** 拼成 `major.patchLevel.subLevel`，用于展示原始内核版本。 */
    override fun toString(): String {
        return "$major.$patchLevel.$subLevel"
    }

    /**
     * 判断是否为 GKI（Generic Kernel Image）内核。
     * 约束：6.x 及以上一律视为 GKI；5.x 仅 5.10 及以后（含 5.15 等）才是。
     */
    fun isGKI(): Boolean {

        // kernel 6.x
        if (major > 5) {
            return true
        }

        // kernel 5.10.x
        if (major == 5) {
            return patchLevel >= 10
        }

        return false
    }
}

/**
 * 从任意版本字符串中提取前三段数字作为内核版本。
 * 解析失败时返回 (-1, -1, -1)，调用方据此判断“未知版本”，不要当作真实版本使用。
 */
fun parseKernelVersion(version: String): KernelVersion {
    // 第一个形如 x.y.z 的片段；release 里通常还带 -android13-xxx 等后缀。
    val find = "(\\d+)\\.(\\d+)\\.(\\d+)".toRegex().find(version)
    if (find != null) {
        val major = find.groupValues[1].toIntOrNull()
        val patchLevel = find.groupValues[2].toIntOrNull()
        val subLevel = find.groupValues[3].toIntOrNull()
        if (major != null && patchLevel != null && subLevel != null) {
            return KernelVersion(major, patchLevel, subLevel)
        }
    }
    return KernelVersion(-1, -1, -1)
}

/** 读取当前运行内核的版本；依赖 `Os.uname`，仅在 Android 进程内可用。 */
fun getKernelVersion(): KernelVersion {
    Os.uname().release.let {
        return parseKernelVersion(it)
    }
}