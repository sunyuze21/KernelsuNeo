//! Magica：通过 ADB root 引导一次晚加载。
//!
//! 当 ksud 只拿到 Magica 应用授予的受限 root 时，无法自行加载内核模块。该模块会临时打开
//! adb root 和 TCP 端口，连接本机 adbd，再通过 ADB shell 重新执行
//! `ksud late-load --post-magica`，使子进程获得完整 root 上下文。子进程加载 kernelsu.ko 后
//! 会恢复 adb 属性。

use adb_client::ADBDeviceExt;
use adb_client::tcp::ADBTcpDevice;
use anyhow::{Context, Result, bail};
use log::{error, info};
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::process::Command;

/// 本模块所有属性写入共用的 ResetProp 配置。
///
/// 改动只留在内存中（不写入持久化文件），并通过 `skip_svc` 直接写入属性区，
/// 而不是经过 property_service；adbd 会在后面显式重启。
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

/// 依次执行外部命令，任一命令失败就终止。
///
/// chmod/setprop 是独立可执行文件，必须显式检查退出状态，不能依赖 ioctl 或库调用的错误。
fn exec_shell_commands(commands: &[(&str, &[&str])], log_prefix: &str) -> Result<()> {
    for (cmd, args) in commands {
        info!("{log_prefix}: {cmd} {}", args.join(" "));
        let status = Command::new(cmd)
            .args(*args)
            .status()
            .with_context(|| format!("Failed to execute {cmd}"))?;
        if !status.success() {
            bail!("{cmd} {} exited with {status}", args.join(" "));
        }
    }
    Ok(())
}

/// 打开 adb root 和 adbd 的 TCP 监听，以便通过 adbd 执行完整 root 的命令。
///
/// 此时只有 Magica 授予的受限 root，足以 chmod 属性文件，但用不了内核侧能力。
/// /dev/__properties__ 下的属性文件默认是 0444 并受 SELinux 保护，必须先改成可写，
/// resetprop 才能修改它们。
fn enable_adb_root(port: u16) -> Result<()> {
    // 此时由 magica 授予的只是受限 root
    anyhow::ensure!(
        rustix::process::getuid().as_raw() == 0,
        "must be run as root"
    );

    sys_prop::init().context("Failed to initialize system property API")?;
    let rp = resetprop();

    // 属性上下文名同时也是 /dev/__properties__ 下对应文件的文件名。
    let debuggable_context = sys_prop::get_context("ro.debuggable")
        .context("Failed to get context for ro.debuggable")?;
    info!("ro.debuggable context: {debuggable_context}");

    let adb_secure_context = sys_prop::get_context("ro.adb.secure")
        .context("Failed to get context for ro.adb.secure")?;
    info!("ro.adb.secure context: {adb_secure_context}");

    let props_serial = "/dev/__properties__/properties_serial";
    let debuggable_context = format!("/dev/__properties__/{debuggable_context}");
    let adb_secure_context = format!("/dev/__properties__/{adb_secure_context}");
    // adb TCP 端口既写进 service.adb.tcp.port，也用于随后连接本机 adbd。
    let port_str = port.to_string();

    // 把属性文件 chmod 为可写
    // 也要改 properties_serial：写入属性时会更新这个序号计数器。
    exec_shell_commands(
        &[
            ("chmod", &["0644", props_serial]),
            ("chmod", &["0644", &debuggable_context]),
            ("chmod", &["0644", &adb_secure_context]),
        ],
        "Executing",
    )?;

    // 通过内部 API 设置属性
    rp.set("ro.debuggable", "1")
        .context("Failed to set ro.debuggable")?;
    info!("Executing: resetprop -n ro.debuggable 1");
    rp.set("ro.adb.secure", "0")
        .context("Failed to set ro.adb.secure")?;
    info!("Executing: resetprop -n ro.adb.secure 0");

    // 恢复权限并重启 adbd
    // 当 ro.adb.secure 关闭且设置了 service.adb.root 时，adbd 会以 root 重新执行；
    // ctl.restart 必须最后写，才能同时带上这两个属性。
    exec_shell_commands(
        &[
            ("chmod", &["0444", props_serial]),
            ("chmod", &["0444", &debuggable_context]),
            ("chmod", &["0444", &adb_secure_context]),
            ("setprop", &["service.adb.root", "1"]),
            ("setprop", &["service.adb.tcp.port", &port_str]),
            ("setprop", &["ctl.restart", "adbd"]),
        ],
        "Executing",
    )?;

    Ok(())
}

/// 内核模块加载完成后，撤销 `enable_adb_root` 所做的改动。
///
/// 此时已是完整 root，属性文件不再需要 chmod；删除 adb 属性并重启 adbd 就能让 adbd 回到
/// 正常模式。
pub fn disable_adb_root() -> Result<()> {
    // 现在已是完整 root，无需再 chmod
    sys_prop::init().context("Failed to initialize system property API")?;
    let rp = resetprop();

    info!("Restoring: resetprop -n ro.debuggable 0");
    rp.set("ro.debuggable", "0")
        .context("Failed to set ro.debuggable")?;

    info!("Restoring: resetprop -n ro.adb.secure 1");
    rp.set("ro.adb.secure", "1")
        .context("Failed to set ro.adb.secure")?;

    for prop in &[
        "service.adb.root",
        "service.adb.tcp.port",
        "ro.boot.selinux",
    ] {
        info!("Restoring: resetprop --delete {prop}");
        // 这些属性可能不存在（也可能由漏洞利用设置过）；忽略删除失败，并重建属性区，
        // 让新读者能看到删除结果。
        let _ = rp.delete(prop);
        if let Ok(ctx) = sys_prop::get_context(prop) {
            let _ = rp.rebuild(&ctx);
        }
    }

    exec_shell_commands(&[("setprop", &["ctl.restart", "adbd"])], "Restoring")?;

    Ok(())
}

/// 连接刚重启的 adbd（本机回环地址）。
///
/// adbd 退出再以 root 身份启动需要一点时间，因此这里最多重试约 30 秒再放弃 Magica 路径。
fn connect_to_device(port: u16) -> Result<ADBTcpDevice> {
    /// adbd 重启的轮询次数，配合 1s 间隔约为 30 秒上限。
    const MAX_RETRIES: u32 = 30;
    for attempt in 1..=MAX_RETRIES {
        info!("Waiting for adbd to restart... (attempt {attempt}/{MAX_RETRIES})");
        std::thread::sleep(std::time::Duration::from_secs(1));

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
        info!("Connecting to ADB device at {addr}");
        match ADBTcpDevice::new(addr).context("Failed to create ADBTcpDevice") {
            Ok(device) => return Ok(device),
            Err(e) => {
                error!("Failed to connect to ADB device: {e:?}, retry after 1s");
            }
        }
    }
    bail!("Failed to connect to ADB device after {MAX_RETRIES} attempts")
}

/// `ksud late-load --magica <port>` 的入口。
///
/// 打开 adb root 后通过 ADB shell 驱动真正的加载；shell 失败只作为提示信息，因为派生的
/// late-load 守护进程会脱离会话，即使加载成功，shell 连接也常常提前断开。
pub fn run_via_adb_root(port: u16, package_name: &str, allow_shell: bool) -> Result<()> {
    enable_adb_root(port)?;

    let mut device = connect_to_device(port)?;

    // 使用自身二进制，保证子进程运行同一版本的 ksud 和相同参数。
    let self_path = std::env::current_exe().context("Failed to get self exe path")?;

    // 通过 adb shell 执行带 --post-magica 的 late-load。
    // late-load 进程拥有完整 root 和 su 域，将会：
    // 1. 加载 kernelsu.ko、强制实施 SELinux、运行阶段脚本
    // 2. 恢复 adb 属性（关闭 adb root/tcp 模式）
    let allow_shell_arg = if allow_shell { " --allow-shell" } else { "" };
    let cmd = format!(
        "{} late-load --post-magica --package-name {}{}",
        self_path.display(),
        package_name,
        allow_shell_arg
    );
    info!("Executing '{cmd}' via adb shell...");
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    // 这里故意不检查 shell 的退出状态，原因见函数文档。
    if let Err(e) = device.shell_command(&cmd, Some(&mut stdout), Some(&mut stderr)) {
        info!("adb shell finished with error (may be expected): {e}");
    }
    if !stdout.is_empty() {
        info!("stdout: {}", String::from_utf8_lossy(&stdout));
    }
    if !stderr.is_empty() {
        info!("stderr: {}", String::from_utf8_lossy(&stderr));
    }

    Ok(())
}
