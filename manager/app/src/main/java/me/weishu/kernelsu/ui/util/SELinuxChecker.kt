package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.Shell

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended).
 *
 * 说明：这里刻意使用普通 sh 而非 root shell，因为无 root 时也能通过
 * `getenforce` 或权限拒绝的报错推断状态。
 */
fun getSELinuxStatusRaw(): String {
    // 普通 shell 即可，避免无 root 设备上创建 root shell 失败。
    val shell = Shell.Builder.create().build("sh")

    val stdoutList = ArrayList<String>()
    val stderrList = ArrayList<String>()
    val result = shell.use {
        it.newJob().add("getenforce").to(stdoutList, stderrList).exec()
    }
    val stdout = stdoutList.joinToString("\n").trim()
    val stderr = stderrList.joinToString("\n").trim()

    if (result.isSuccess) {
        return when (stdout) {
            "Enforcing", "Permissive", "Disabled" -> stdout
            else -> "Unknown"
        }
    }

    // 部分 ROM 禁止普通应用执行 getenforce；权限被拒通常意味着处于 Enforcing。
    return if (stderr.endsWith("Permission denied")) {
        "Enforcing"
    } else {
        "Unknown"
    }
}
