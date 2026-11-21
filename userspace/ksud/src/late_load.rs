//! Late load：不刷写 boot，直接从用户空间拉起 KernelSU。
//!
//! 用于越狱/晚加载流程，此时 init 没有执行 KernelSU 的常规开机流程。该模块会在内核部分尚未
//! 存在时加载 kernelsu.ko，然后手工重放各个开机阶段：模块更新、sepolicy、特性、阶段脚本、
//! system.prop 以及元模块挂载。

use anyhow::{Context, Result};
use log::{info, warn};
use rustix::cstr;
use std::{process::Command, time::Instant};

use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::{assets, defs, init_event, metamodule, restorecon, utils};

/// 记录当前进程的权限状态。
///
/// 加载内核模块和执行开机脚本对 uid、SELinux 域和 seccomp 状态有严格要求，把它们写进日志
/// 便于根据用户反馈定位失败原因。
fn dump_process_info(label: &str) {
    use rustix::process::{getgid, getgroups, getpid, getuid};

    let pid = getpid().as_raw_nonzero();
    let uid = getuid().as_raw();
    let gid = getgid().as_raw();
    let groups: Vec<String> = getgroups()
        .unwrap_or_default()
        .iter()
        .map(|g| g.as_raw().to_string())
        .collect();
    // 当前进程的 SELinux 标签（例如 u:r:su:s0 与 u:r:ksu:s0 的区别）。
    let selinux = std::fs::read_to_string("/proc/self/attr/current")
        .unwrap_or_else(|_| "unknown".to_string());
    // /proc/self/status 里的 Seccomp 状态；内核限制严格时模块加载会被拒绝。
    let seccomp = std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("Seccomp:"))
                .map(|l| l.trim().to_string())
        })
        .unwrap_or_else(|| "unknown".to_string());

    info!(
        "[{label}] pid={pid}, uid={uid}, gid={gid}, groups=[{}], selinux={}, {seccomp}",
        groups.join(","),
        selinux.trim(),
    );
}

/// `ksud late-load` 的入口。
///
/// `kmi` 在调用方已知内核 ABI 时覆盖自动检测结果；`allow_shell` 作为模块参数传入，最终成为
/// 内核侧的宽松 shell 设置。函数会 daemonize，因此调用方返回成功只代表守护进程已启动。
pub fn run(package_name: &String, kmi: Option<String>, allow_shell: bool) -> Result<()> {
    // 脱离 ADB/shell 会话；否则开机阶段脚本可能随调用方退出而被终止。
    utils::daemonize(false)?;
    info!("late-load command triggered!");
    dump_process_info("late-load start");

    // 1. 检查 KernelSU 是否已加载
    if ksuinit::has_kernelsu() {
        info!("KernelSU already loaded, skip loading ko");
    } else {
        // 2. 检测当前 KMI 版本
        // 检测读取当前运行内核的版本；KMI 不匹配会导致 .ko 被拒绝加载。
        let kmi = kmi.map_or_else(
            || crate::boot_patch::get_current_kmi().context("Failed to detect current KMI version"),
            Ok,
        )?;
        info!("Detected KMI: {kmi}");

        // 3. 从内嵌资源中取出 kernelsu.ko
        let ko_name = format!("{kmi}_kernelsu.ko");
        let ko_data = assets::get_asset_data(&ko_name)
            .with_context(|| format!("Failed to get {ko_name} from assets"))?;

        // 4. 从内存加载 kernelsu.ko 并手工重定位
        info!("Loading kernelsu.ko for KMI {kmi}...");
        // 在越狱模式下 bundled 标志没有意义，因为无法通过刷写 boot 来更新它。
        let params = if allow_shell {
            cstr!("allow_shell=1")
        } else {
            cstr!("")
        };
        ksuinit::load_module(&ko_data, params).context("Failed to load kernelsu.ko")?;
        info!("kernelsu.ko loaded successfully!");
        dump_process_info("after load_module");
    }

    // 必须重置 stdin/stdout/stderr；否则通过 cmd 事务传递文件描述符时，
    // 会因 SELinux 的 fsec->sid 仍是 u:r:su:s0 而非 u:r:ksu:s0 而被拦截。
    utils::reset_std()?;

    // 脚本会按固定权限（0644/0755）创建文件，继承来的严格 umask 会悄悄破坏这些权限。
    utils::umask(0);

    // 临时配置按约定只对本次开机有效，不能让上一次开机的值泄漏到下面要运行的脚本里。
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    // 从自身可执行文件安装 ksud 及其 bin 资源，保证后续阶段脚本调用的是当前运行的版本。
    utils::install(None, None).context("Failed to install ksud")?;

    crate::hot_install::clean_trash();

    // 5. 处理模块更新
    // 上次会话暂存的常规（非热安装）安装也会在这里生效。
    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    // 重新标记守护进程以及安装中断后仍未标注的模块目录。
    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // 6. 加载 SELinux 规则
    // 必须先于脚本执行，脚本才可以使用规则授予的策略。
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // 7. 初始化特性
    // 强制特性由内核决定；这里只重放它们的用户空间副作用，随后 init_features 再应用其余持久化配置。
    crate::feature::init_forced_features();
    if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // 8. 使用共享的开机截止时间执行 late-load 阶段脚本
    let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);
    init_event::run_stage("late-load", wait);

    // 9. 加载 system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // 10. 执行元模块挂载脚本（OverlayFS）
    if let Err(e) = metamodule::exec_mount_script(defs::MODULE_DIR) {
        warn!("execute metamodule mount failed: {e}");
    }

    // 11. 使用同一截止时间执行 post-mount 阶段脚本
    init_event::run_stage("post-mount", wait);

    // 12. 执行 service 阶段脚本（非阻塞）
    init_event::run_stage("service", ScriptWait::NoWait);

    // 13. 执行 boot-completed 阶段脚本（非阻塞）
    init_event::run_stage("boot-completed", ScriptWait::NoWait);

    // 14. 重启管理器，使其从新加载的内核模块获取全新的 ksu fd
    info!("Restarting KernelSU Manager {package_name}...");
    let _ = Command::new("am")
        .args(["force-stop", package_name])
        .status();
    let _ = Command::new("am")
        .args([
            "start",
            "-n",
            &format!("{package_name}/me.weishu.kernelsu.ui.MainActivity"),
        ])
        .status();

    Ok(())
}
