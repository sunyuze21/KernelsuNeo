//! 不重启设备直接卸载 KernelSU 内核模块（热卸载）。
//!
//! 顺序上先让所有可能占用模块或继续调用模块的进程安静下来：停止 Android
//! 服务，杀掉 ksu 域内的进程和持有 ksu fd 的进程，再关闭自身的 ksu fd，
//! 然后调用 `delete_module`，最后重新启动服务。仅由 Android 的 `unload`
//! 子命令触发。

use anyhow::Result;
use log::{info, warn};
use std::fs;
use std::process::Command;

use crate::utils;

/// 查找运行在 KernelSU su 域（u:r:ksu:s0）中的进程 PID。
/// 返回 PID 列表，不包含本进程。
///
/// 只匹配精确的 SELinux 标签；仅继承了 ksu fd 的进程由
/// `find_ksu_fd_holders` 处理。标签读自 `/proc/<pid>/attr/current`，
/// 其中可能带结尾的 NUL 字节，所以需要 trim。
fn find_su_domain_pids() -> Vec<i32> {
    let my_pid = std::process::id() as i32;
    let mut pids = Vec::new();

    let Ok(entries) = fs::read_dir("/proc") else {
        return pids;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        if pid == my_pid {
            continue;
        }

        let attr_path = format!("/proc/{pid}/attr/current");
        if let Ok(context) = fs::read_to_string(&attr_path) {
            let context = context.trim().trim_end_matches('\0');
            if context == "u:r:ksu:s0" {
                pids.push(pid);
            }
        }
    }

    pids
}

/// 查找持有 ksu_driver 或 ksu_fdwrapper 文件描述符的进程 PID。
/// 返回 PID 列表，不包含本进程。
///
/// 匿名 inode 的名字是 `[ksu_driver]`（或 `[ksu_driver_su]`）以及
/// `[ksu_fdwrapper]`；这里用前缀匹配，故意同时覆盖两种 driver 变体。
/// 无法检查的进程（uid 不同或扫描中途退出）直接跳过，同一 pid 内的
/// 重复命中通过跳出 fd 循环去重。
fn find_ksu_fd_holders() -> Vec<i32> {
    let my_pid = std::process::id() as i32;
    let mut pids = Vec::new();

    let Ok(entries) = fs::read_dir("/proc") else {
        return pids;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name.to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        if pid == my_pid {
            continue;
        }

        let fd_dir = format!("/proc/{pid}/fd");
        let Ok(fds) = fs::read_dir(&fd_dir) else {
            continue;
        };

        for fd_entry in fds.flatten() {
            let link_path = fd_entry.path();
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if target_str.contains("[ksu_driver") || target_str.contains("[ksu_fdwrapper]") {
                    pids.push(pid);
                    break;
                }
            }
        }
    }

    pids
}

/// 向列表中的每个 pid 发送 `signal`，忽略单个 pid 的失败。
///
/// 列表来自 /proc 的一次快照，pid 可能已经退出；ESRCH（以及无权操作的
/// EPERM）不应中断卸载流程。
fn kill_pids(pids: &[i32], signal: i32) {
    for &pid in pids {
        unsafe {
            libc::kill(pid, signal);
        }
    }
}

/// 关闭当前进程持有的所有 ksu_driver 和 ksu_fdwrapper fd。
///
/// 这些 fd 会把模块引用计数保持在 1 以上，导致 `delete_module` 失败，所以
/// 必须由进程自己关闭。遍历 `/proc/self/fd` 时按 fd 序号关闭是安全的：
/// 目录自身的 fd 不是 ksu fd。
fn close_ksu_fds() {
    let Ok(entries) = fs::read_dir("/proc/self/fd") else {
        return;
    };

    for entry in entries.flatten() {
        let Ok(fd) = entry.file_name().to_string_lossy().parse::<i32>() else {
            continue;
        };
        if let Ok(target) = fs::read_link(entry.path()) {
            let target_str = target.to_string_lossy();
            if target_str.contains("[ksu_driver") || target_str.contains("[ksu_fdwrapper]") {
                info!("unload: closing fd {fd} -> {target_str}");
                unsafe {
                    libc::close(fd);
                }
            }
        }
    }
}

/// 执行原地卸载流程并退出进程。
///
/// 顺序很重要：先停止服务，避免有进程重新拉起 su；接着清理可杀死的进程，
/// 因为任何存活持有者都会占用模块；然后关闭自己的 fd 并尝试
/// `delete_module`。删除失败只记录日志而不返回错误，因为后续重启 Android
/// 服务的步骤无论如何都要执行。
pub fn unload() -> Result<()> {
    info!("unload: starting KernelSU unload sequence");

    // 0. 切换 cgroup，避免被随父 shell 一同杀死
    utils::switch_cgroups();

    // 1. stop（Android init 的 stop 命令，停止所有服务）
    info!("unload: stopping Android services...");
    let _ = Command::new("stop").status();

    // 2. 杀死所有 su 域进程以及持有 ksu fd 的进程（本进程除外）
    info!("unload: killing su domain processes...");
    let su_pids = find_su_domain_pids();
    if !su_pids.is_empty() {
        info!(
            "unload: found {} su domain processes, sending SIGKILL",
            su_pids.len()
        );
        // 用 SIGKILL 而不是 SIGTERM：root shell 不能在模块拆除期间捕获
        // 信号或继续滞留。
        kill_pids(&su_pids, libc::SIGKILL);
    }

    info!("unload: killing processes holding ksu fds...");
    let fd_pids = find_ksu_fd_holders();
    if !fd_pids.is_empty() {
        info!(
            "unload: found {} processes holding ksu fds, sending SIGKILL",
            fd_pids.len()
        );
        kill_pids(&fd_pids, libc::SIGKILL);
    }

    // 3. 关闭本进程自己持有的所有 ksu_driver 和 ksu_fdwrapper fd
    info!("unload: closing all ksu fds...");
    close_ksu_fds();

    // 4. delete_module("kernelsu")
    info!("unload: removing kernelsu module...");
    // flags 为 0 表示普通（非 O_NONBLOCK）删除：只要模块还有任何引用就会
    // 失败。这里只告警而不中断，以便继续执行下面的服务重启。
    if let Err(e) = rustix::system::delete_module(c"kernelsu", 0) {
        warn!("unload: delete_module kernelsu failed: {e}");
    }

    // 5. start（Android init 的 start 命令，重启所有服务）
    info!("unload: restarting Android services...");
    let _ = Command::new("start").status();

    // 6. 退出
    info!("unload: done, exiting ksud");
    // 不运行析构直接退出：`unload` 不会再返回 CLI 分发处，此时也没有
    // 调用方能够做清理。
    std::process::exit(0);
}
