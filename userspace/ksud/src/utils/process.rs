//! 进程相关辅助：挂载命名空间、cgroup、守护进程化和进程组脱离。

use anyhow::{Result, bail};
use rustix::fd::AsFd;
use rustix::process::setpgid;
use rustix::stdio::{dup2_stderr, dup2_stdin, dup2_stdout};
use rustix::thread::{LinkNameSpaceType, move_into_link_name_space};
use rustix::{fs::Mode, fs::OFlags, fs::open};
use std::{fs::OpenOptions, io::Write, path::Path, process};

use crate::utils::getprop;

/// 加入另一个进程的挂载命名空间（pid 1 即 init/全局命名空间）。
///
/// setns 会重置进程的根目录/当前目录视图，因此若原当前目录仍可达则重新设置。
pub fn switch_mnt_ns(pid: i32) -> Result<()> {
    let path = format!("/proc/{pid}/ns/mnt");
    let fd = open(path, OFlags::RDONLY, Mode::from_raw_mode(0))?;
    let current_dir = std::env::current_dir();
    move_into_link_name_space(fd.as_fd(), Some(LinkNameSpaceType::Mount))?;
    if let std::result::Result::Ok(current_dir) = current_dir {
        let _ = std::env::set_current_dir(current_dir);
    }
    Ok(())
}

/// 通过写入 `cgroup.procs` 把 pid 移入 `cgroup`；不同 Android 版本缺失某些
/// cgroup 层级很常见，这里静默忽略。
fn switch_cgroup(cgroup: &str, pid: u32) {
    let path = Path::new(cgroup).join("cgroup.procs");
    if !path.exists() {
        return;
    }

    let procs_file = OpenOptions::new().append(true).open(path);
    if let std::result::Result::Ok(mut procs_file) = procs_file {
        let _ = write!(procs_file, "{pid}");
    }
}

/// 让进程脱离调用者的 cgroup，避免 root 进程随启动它的应用一起被限流或杀死。
pub fn switch_cgroups() {
    // 逐层尝试把守护进程移出调用者的 cgroup；不同 Android 版本提供的层级不同，
    // switch_cgroup 对不存在的路径会静默跳过。
    let pid = process::id();
    switch_cgroup("/acct", pid);
    switch_cgroup("/dev/cg2_bpf", pid);
    switch_cgroup("/sys/fs/cgroup", pid);

    // 大多数设备存在按应用划分的 memcg；该属性可将其禁用。
    if getprop("ro.config.per_app_memcg")
        .as_ref()
        .is_none_or(|prop| prop != "false")
    {
        switch_cgroup("/dev/memcg/apps", pid);
    }
}

/// 设置进程 umask。
///
/// 模块脚本以固定 0644/0755 权限创建文件，继承来的严格 umask 会悄悄去掉权限位，
/// 因此 late-load 等流程在执行脚本前显式将其清零。
pub fn umask(mask: u32) {
    rustix::process::umask(Mode::from_raw_mode(mask));
}

/// 把标准输入输出重定向到 /dev/null，标准的守护进程清理步骤。
pub fn reset_std() -> Result<()> {
    let null_fd = open("/dev/null", OFlags::RDWR, Mode::empty())?;
    dup2_stdin(&null_fd)?;
    dup2_stdout(&null_fd)?;
    dup2_stderr(&null_fd)?;
    Ok(())
}

/// 守护进程化：父进程在此调用后退出，仅保留脱离会话的守护进程。
///
/// `join_init_pgrp` 为真时尝试通过内核 supercall 加入 init 进程组；
/// `configure` 在两次 fork 之间、完全脱离之前执行，用于切换命名空间等。
pub fn daemonize_with<F: FnOnce() -> Result<()>>(join_init_pgrp: bool, configure: F) -> Result<()> {
    if !fork_daemon(join_init_pgrp, configure)? {
        // 原父进程已完成回收，直接退出，不运行析构
        unsafe { libc::_exit(0) }
    }
    Ok(())
}

/// `daemonize_with` 的简化版本，不做额外配置。
pub fn daemonize(join_init_pgrp: bool) -> Result<()> {
    daemonize_with(join_init_pgrp, || Ok(()))
}

/// 仅做双 fork，不要求父进程退出。
///
/// 返回 `true` 表示当前是最终守护进程，`false` 表示原父进程（此时应继续原来的
/// 逻辑而非退出）。
pub fn create_daemon(join_init_pgrp: bool) -> Result<bool> {
    create_daemon_with(join_init_pgrp, || Ok(()))
}

/// `create_daemon` 的可配置版本，在两次 fork 之间执行 `configure`。
pub fn create_daemon_with<F: FnOnce() -> Result<()>>(
    join_init_pgrp: bool,
    configure: F,
) -> Result<bool> {
    fork_daemon(join_init_pgrp, configure)
}

/// 双 fork 守护进程化。
///
/// 原父进程中返回 `false`（此时已回收第一个子进程），最终守护进程中返回
/// `true`。`configure` 在两次 fork 之间执行，以便在完全脱离前加入目标
/// cgroup/会话。
fn fork_daemon<F: FnOnce() -> Result<()>>(join_init_pgrp: bool, configure: F) -> Result<bool> {
    unsafe {
        let pid = libc::fork();
        if pid < 0 {
            bail!("fork error {}", std::io::Error::last_os_error());
        } else if pid > 0 {
            // 父进程：等待第一个子进程退出，以便把配置失败的状态传递给调用方
            let mut wait_status: i32 = -1;
            loop {
                if libc::waitpid(pid, &raw mut wait_status, 0) < 0 {
                    if *libc::__errno() != libc::EINTR {
                        libc::_exit(1);
                    }
                } else {
                    break;
                }
            }
            if !libc::WIFEXITED(wait_status) || libc::WEXITSTATUS(wait_status) != 0 {
                bail!("child exited with unexpected status {wait_status}")
            }
            return Ok(false);
        }
    }

    let finish_fork_setup = || -> Result<()> {
        detach_process_group(join_init_pgrp);
        switch_cgroups();
        configure()?;
        reset_std()?;

        unsafe {
            let pid = libc::fork();
            if pid < 0 {
                bail!("fork error {}", std::io::Error::last_os_error());
            } else if pid > 0 {
                libc::_exit(0);
            }
        }
        Ok(())
    };

    if let Err(e) = finish_fork_setup() {
        log::error!("failed to configure daemon: {e:?}");
        unsafe {
            libc::_exit(1);
        }
    }

    Ok(true)
}

/// 优先使用内核的 `set_init_pgrp` supercall（加入 init 进程组，使守护进程
/// 不随启动者退出）；失败时回退到普通 setpgid。
pub fn detach_process_group(join_init_pgrp: bool) {
    if join_init_pgrp {
        if let Err(e) = crate::ksucalls::set_init_pgrp() {
            log::error!("failed to switch to init group: {e:?}");
        } else {
            return;
        }
    }
    if let Err(e) = setpgid(None, None) {
        log::error!("failed to set process group: {e:?}");
    }
}
