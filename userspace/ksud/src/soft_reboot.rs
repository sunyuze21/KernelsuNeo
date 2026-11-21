//! 模拟软重启：不重启内核，按顺序重放 Android 启动阶段。
//!
//! 先把 `sys.boot_completed` 复位，执行 `emulated-soft-reboot` 阶段脚本，
//! 停止再启动 init 服务，然后重新触发 post-fs-data、services 和
//! boot-completed 处理。内置的 "waitsys" 辅助程序通过管道汇报进度，让流程
//! 能等到服务真正停止。由 Android 的 `soft-reboot` 子命令触发。

use std::{
    fs::File,
    io::Write,
    os::fd::{AsRawFd, OwnedFd},
    process::{Child, Command},
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail, ensure};
use libc::_exit;
use log::{error, info, warn};
use prop_rs_android::{resetprop::ResetProp, sys_prop};
use rustix::{
    event::{PollFd, PollFlags, poll},
    fs::{MemfdFlags, Timespec, memfd_create},
    io::{Errno, FdFlags, fcntl_getfd, fcntl_setfd, read},
    pipe::{PipeFlags, pipe_with},
    process::chdir,
};

use crate::{
    assets, defs,
    init_event::{on_boot_completed, on_post_fs_data, on_services, run_stage},
    ksucalls,
    module::ScriptWait,
    utils::{self, switch_mnt_ns},
};

const WAITSYS_FD_ENV: &str = "KSU_WAITSYS_FD";

/// 内置 `waitsys` 辅助进程的句柄。
///
/// 该辅助程序监视当前运行的 Android 服务集合，并在每个检查点向管道写入
/// 一个字节。父进程持有读端；子进程只继承写端，写端 fd 号通过
/// `KSU_WAITSYS_FD` 环境变量传递。
struct Waitsys {
    child: Child,
    read_fd: OwnedFd,
}

impl Waitsys {
    /// 从内置资源启动辅助程序，不落盘。
    ///
    /// 二进制先写入匿名 memfd，再通过 `/proc/self/fd/<n>` 执行；该 fd 保持
    /// close-on-exec，辅助程序不会继承自己的镜像，只有管道的写端为它清除了
    /// CLOEXEC。父进程随后关闭这两份拷贝，于是辅助程序退出时读端会看到 EOF。
    fn spawn() -> Result<Self> {
        let waitsys = assets::get_asset_data("waitsys").context("waitsys is not embedded")?;
        let executable_fd = memfd_create("waitsys", MemfdFlags::CLOEXEC)
            .context("failed to create waitsys memfd")?;
        let mut executable = File::from(executable_fd);
        executable
            .write_all(&waitsys)
            .context("failed to write waitsys to memfd")?;

        let (read_fd, write_fd) =
            pipe_with(PipeFlags::CLOEXEC).context("failed to create waitsys pipe")?;
        // 管道两端初始都带 close-on-exec；这里只为写端清除，使辅助程序恰好
        // 继承一条回传给我们的通道。
        fcntl_setfd(
            &write_fd,
            fcntl_getfd(&write_fd).context("get write_fd flags")? & !FdFlags::CLOEXEC,
        )
        .context("set write_fd flags")?;

        // 通过路径执行 memfd，避免把辅助程序解压到可能只读或 noexec 的
        // 文件系统上。
        let mut cmd = Command::new(format!("/proc/self/fd/{}", executable.as_raw_fd()));
        cmd.env(WAITSYS_FD_ENV, format!("{}", write_fd.as_raw_fd()));
        let child = cmd.spawn()?;

        // 关闭父进程的拷贝：子进程持有写端，而我们的读端必须在子进程退出
        // 后看到 EOF。
        drop(write_fd);
        drop(executable);
        Ok(Self { child, read_fd })
    }

    /// 阻塞等待辅助程序向管道写入 `expected`。
    ///
    /// 信号 1 表示辅助程序已经采集完当前服务（在 `stop` 之前发送）；信号 2
    /// 表示这些服务已经消失（在 `stop` 之后发送）。超时或值不匹配会返回错误，
    /// 调用方把它视为“继续但不做同步”，而不是致命失败。`poll` 和 `read` 遇到
    /// EINTR 会重试，因为运行期间可能收到 init 发来的信号。
    fn wait_for_signal(&self, expected: u8, timeout: Duration) -> Result<()> {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                bail!("timed out waiting for signal {expected}");
            }
            let remaining = Timespec::try_from(remaining)?;
            let mut poll_fd = [PollFd::new(&self.read_fd, PollFlags::IN)];
            let ready = match poll(&mut poll_fd, Some(&remaining)) {
                Err(Errno::INTR) => continue,
                result => result,
            }?;
            if ready == 0 {
                bail!("timed out waiting for signal {expected}");
            }

            let mut signal = [0_u8; 1];
            let bytes_read = match read(&self.read_fd, &mut signal) {
                Err(Errno::INTR) => continue,
                result => result,
            }?;
            // 读到 0 字节说明辅助程序在到达该检查点前关闭了管道（或已退出）。
            ensure!(
                bytes_read == 1,
                "waitsys pipe closed before signal {expected}"
            );
            let signal = signal[0];
            ensure!(
                signal == expected,
                "unexpected waitsys signal {signal}, expected {expected}"
            );
            return Ok(());
        }
    }

    /// 终止并回收辅助程序。
    ///
    /// `kill` 可能因辅助程序已退出而失败，这没关系；但 `wait` 仍然必须调用，
    /// 否则会留下僵尸进程。
    fn terminate(&mut self) -> Result<()> {
        self.child.kill().ok();
        self.child.wait().context("wait for waitsys")?;
        Ok(())
    }
}

impl Drop for Waitsys {
    /// 尽力清理，确保提前返回的错误不会让辅助程序继续运行。
    fn drop(&mut self) {
        if let Err(error) = self.terminate() {
            warn!("failed to clean up waitsys: {error:#}");
        }
    }
}

/// 从 Option 中取出辅助程序、终止它，并留下 `None`。
///
/// `take` 让重复清理变成空操作，也允许调用方在辅助程序从未就绪等情况下
/// 提前丢弃它，而无需再次等待。
fn terminate_waitsys(waitsys: &mut Option<Waitsys>) {
    if let Some(mut waitsys) = waitsys.take()
        && let Err(error) = waitsys.terminate()
    {
        warn!("failed to clean up waitsys: {error:#}");
    }
}

/// 等价于 `resetprop -n` 的配置：绕过 property_service 直接写属性区，
/// 不触碰持久化存储。
const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

/// 在模拟启动前把 `sys.boot_completed` 强制复位为 "0"。
///
/// 已经启动完成的设备该属性仍是 "1"；不复位的话后面的等待会立即返回，
/// boot-completed 钩子会在服务恢复之前就执行。直接写入属性区也能让随后的
/// 等待观察到 init 自己把它改回 "1" 的这一次变化。
fn reset_boot_completed() -> Result<()> {
    sys_prop::init().context("Failed to initialize system property API")?;
    let rp = resetprop();
    // 提前把属性值设为 0，确保 resetprop -w 能正常工作
    info!("reset boot complete prop to 0");
    rp.set("sys.boot_completed", "0")
        .context("Failed to set sys.boot_completed to 0")?;
    Ok(())
}

/// 等待 `sys.boot_completed` 变为非 "0"，即 init 完成启动。
///
/// 这里不传超时：模拟重启刻意等待真实的属性变化，调用方只在该等待失败时
/// 告警。
fn wait_for_boot_completed() -> Result<()> {
    sys_prop::init().context("Failed to initialize system property API")?;
    let rp = resetprop();
    info!("waiting for boot complete");
    rp.wait("sys.boot_completed", Some("0"), None)
        .context("wait for sys.boot_completed failed")?;
    Ok(())
}

/// 按 init 的启动顺序重放各阶段来模拟一次重启。
///
/// 单个阶段失败（属性复位、waitsys、`stop`/`start`）只记录日志并继续：
/// 让服务停在停止状态比一次部分同步的模拟更糟。若内核与用户态 UAPI 版本
/// 不一致（例如 Manager 先于内核模块更新），则跳过请求，避免发出内核可能
/// 不认识的 ioctl。
///
/// 该函数不会正常返回；守护进程最终以 `_exit(0)` 结束。
pub fn soft_reboot() -> Result<()> {
    // 该检查可避免在版本不匹配时用户在管理器中点击 "soft_reboot"
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip soft_reboot");
        return Ok(());
    }

    // 以 `true` 脱离调用方并加入 init 的进程组，这样即使启动它的 shell/应用
    // 退出也能存活。挂载命名空间切换到 init 的，使服务控制与模块脚本看到真实
    // 的系统视图。
    utils::daemonize_with(true, || -> Result<()> {
        switch_mnt_ns(1)?;
        chdir("/")?;
        Ok(())
    })?;

    info!("emulating soft_reboot!");
    if let Err(e) = reset_boot_completed() {
        warn!("reset boot completed failed: {e}");
    }
    // 以共享的截止时间重放 emulated-soft-reboot 阶段，避免某个慢模块拖住
    // 整个模拟流程。
    run_stage(
        "emulated-soft-reboot",
        ScriptWait::Until(Instant::now() + defs::EMULATED_SOFT_REBOOT_TIMEOUT),
    );

    // 辅助程序是可选的：没有它就直接退化为不做同步的 stop/start 流程。
    let mut waitsys = match Waitsys::spawn() {
        Ok(waitsys) => Some(waitsys),
        Err(error) => {
            warn!("failed to start waitsys: {error:#}");
            None
        }
    };
    // 只有及时就绪（信号 1 = 已采集服务）才继续保留辅助程序。
    let wait_after_stop = waitsys.as_ref().is_some_and(|waitsys| {
        if let Err(error) = waitsys.wait_for_signal(1, defs::WAITSYS_READY_TIMEOUT) {
            warn!("waitsys failed to collect services: {error:#}");
            false
        } else {
            true
        }
    });
    if !wait_after_stop {
        terminate_waitsys(&mut waitsys);
    }

    info!("stop");
    // init 的 `stop` 命令终止所有服务；waitsys 在服务真正消失后发信号 2，
    // 避免 `start` 与它们的关闭过程竞争。
    let status = Command::new("stop").status().context("stop failed")?;
    if !status.success() {
        warn!("stop exited with status: {status}");
    }

    if let Some(waitsys) = waitsys.as_ref()
        && let Err(error) = waitsys.wait_for_signal(2, defs::WAITSYS_STOP_TIMEOUT)
    {
        warn!("waitsys failed while waiting for services to stop: {error:#}");
    }
    terminate_waitsys(&mut waitsys);

    // 在服务恢复之前重放模块的 post-fs-data 阶段，与真实启动顺序一致。
    info!("post-fs-data");
    on_post_fs_data()?;
    info!("start");
    let status = Command::new("start").status().context("start failed")?;
    if !status.success() {
        warn!("start exited with status: {status}");
    }
    info!("services");
    on_services();
    if let Err(e) = wait_for_boot_completed() {
        warn!("wait for boot completed failed: {e}");
    }
    on_boot_completed();

    unsafe {
        // 不做栈展开直接退出：服务已经重新启动，也没有父进程期待该守护进程
        // 正常返回。
        _exit(0);
    }
}
