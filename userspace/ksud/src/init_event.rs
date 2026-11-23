//! ksud 参与的 init 阶段处理器。
//!
//! `init`（或 LKM 延迟加载钩子）会调用 `ksud post-fs-data`、`ksud services`、
//! `ksud boot-completed`。每个处理器先把事件上报内核，再按 Magisk 兼容顺序
//! 执行对应阶段的模块脚本。其中 post-fs-data 最复杂：它要提交暂存的模块更新、
//! 重建 preinit 状态、加载 sepolicy/特性，并执行 metamodule 钩子。

use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::{assets, defs, ksucalls, metamodule, restorecon, utils};
use anyhow::{Context, Result};
use log::{error, info, warn};
use std::{path::Path, time::Instant};

/// 处理 `post-fs-data` 启动阶段。
///
/// 该阶段在 /data 挂载之后、大多数系统服务启动之前运行。暂存的模块更新会在此
/// 提交，从而保证模块覆盖系统分区之前新文件已经可见。
pub fn on_post_fs_data() -> Result<()> {
    // 版本不匹配说明内核与本二进制协议不同，直接拒绝执行，
    // 避免下发大小不一致的 ioctl。
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_post_fs_data");
        return Ok(());
    }

    ksucalls::report_post_fs_data();

    utils::umask(0);

    // 临时模块配置只在本次启动内有效；在任何模块脚本看到旧值之前先清空。
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    // 在模块脚本运行前启动后台启动日志捕获，以便记录早期失败。
    // 这里的失败不影响主流程。
    let _ = catch_bootlog("logcat", &["logcat", "-b", "all"]);
    let _ = catch_bootlog("dmesg", &["dmesg", "-w", "-r"]);

    // Magisk 与 KernelSU 共用 /data/adb/modules，同时运行会破坏模块状态，
    // 因此检测到 Magisk 时 KernelSU 主动让出。
    if utils::has_magisk() {
        warn!("Magisk detected, skip post-fs-data!");
        return Ok(());
    }

    // 安全模式标记在本次启动内不变，后续多个分支都依赖它决定是否跳过脚本。
    let safe_mode = crate::utils::is_safe_mode();
    // 每个阶段的所有脚本共享同一个截止时间，避免脚本串行时无限拖慢启动。
    let stage_wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);

    if safe_mode {
        // 安全模式下仍保留模块目录：用户需要靠它们禁用/删除导致 boot loop
        // 的模块。
        warn!("safe mode, skip common post-fs-data.d scripts");
    } else {
        // 公共脚本（/data/adb/post-fs-data.d）先于各模块脚本执行。
        if let Err(e) = crate::module::exec_common_scripts("post-fs-data.d", stage_wait) {
            warn!("exec common post-fs-data scripts failed: {e}");
        }
    }

    let module_dir = defs::MODULE_DIR;

    // 即使 APK 已更新，也确保 busybox/ksud 可用。
    assets::ensure_binaries(true).with_context(|| "Failed to extract bin assets")?;

    // 热安装替换下来的旧模块目录会暂存到开机完成后再清理。
    crate::hot_install::clean_trash();

    // 安全模式是摆脱 boot loop 的兜底手段：禁用所有模块并跳过本阶段剩余流程，
    // 让下次启动保持干净。
    if safe_mode {
        warn!("safe mode, skip post-fs-data scripts and disable all modules!");
        if let Err(e) = crate::module::disable_all_modules() {
            warn!("disable all modules failed: {e}");
        }
        return Ok(());
    }

    // 把 modules_update/ 中暂存的模块提交到 modules/，
    // 同时保留旧副本的禁用/删除标记。
    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    // 刷新 /metadata/watchdog/ksu/{modules.rc,preinit/}，使下一次开机的内核钩子
    // 能看到当前模块集合。当状态在 ksud 常规变更命令之外被修改时，
    // 它可作为兜底手段。
    if let Err(e) = crate::module::regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    // 在策略使用方读取之前，恢复我们创建的文件的 SELinux 标签。
    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // 在特性/模块运行之前，先应用模块提供的 sepolicy.rule 和已保存的
    // 各应用配置档案规则。
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // 即使在安全模式下，也要应用启动配置强制指定的特性。
    crate::feature::init_forced_features();

    // 加载特性配置
    if safe_mode {
        warn!("safe mode, skip load feature config");
    } else if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // 优先执行元模块的 post-fs-data 脚本
    if let Err(e) = metamodule::exec_stage_script("post-fs-data", stage_wait) {
        warn!("exec metamodule post-fs-data script failed: {e}");
    }

    // 执行各模块的 post-fs-data 脚本
    if let Err(e) = crate::module::exec_stage_script("post-fs-data", stage_wait) {
        warn!("exec post-fs-data scripts failed: {e}");
    }

    // 加载 system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // 执行元模块挂载脚本
    if let Err(e) = metamodule::exec_mount_script(module_dir) {
        warn!("execute metamodule mount failed: {e}");
    }

    run_stage("post-mount", stage_wait);

    std::env::set_current_dir("/").with_context(|| "failed to chdir to /")?;

    Ok(())
}

/// 执行某一启动阶段的公共脚本（`/data/adb/<stage>.d`）、metamodule 脚本和
/// 各模块脚本。metamodule 先执行，以便为普通模块准备好挂载基础。
pub fn run_stage(stage: &str, stage_wait: ScriptWait) {
    utils::umask(0);

    if utils::has_magisk() {
        warn!("Magisk detected, skip {stage}");
        return;
    }

    if crate::utils::is_safe_mode() {
        warn!("safe mode, skip {stage} scripts");
        return;
    }

    if let Err(e) = crate::module::exec_common_scripts(&format!("{stage}.d"), stage_wait) {
        warn!("Failed to exec common {stage} scripts: {e}");
    }

    // 优先执行元模块的阶段脚本
    if let Err(e) = metamodule::exec_stage_script(stage, stage_wait) {
        warn!("Failed to exec metamodule {stage} script: {e}");
    }

    // 执行普通模块的阶段脚本
    if let Err(e) = crate::module::exec_stage_script(stage, stage_wait) {
        warn!("Failed to exec {stage} scripts: {e}");
    }
}

/// 处理 `service` 阶段（启动后期，大多数系统服务已开始运行）。
pub fn on_services() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_services");
        return;
    }

    // 内核若已收到过该事件会返回 false；避免 init 重复触发时再次执行
    // service 脚本。
    match ksucalls::report_services() {
        Ok(true) => {}
        Ok(false) => {
            info!("services already started, skipping");
            return;
        }
        Err(e) => {
            error!("Failed to report services: {e:#}");
            return;
        }
    }

    info!("on_services triggered!");
    run_stage("service", ScriptWait::NoWait);
}

/// 处理 `sys.boot_completed`：这是模块执行依赖完整 Android（包管理器、网络等）
/// 的动作的最后阶段。
pub fn on_boot_completed() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_boot_completed");
        return;
    }

    ksucalls::report_boot_complete();
    info!("on_boot_completed triggered!");

    // 为成功启动的模块重置 preinit 启动失败计数。
    crate::preinit::on_boot_completed();

    run_stage("boot-completed", ScriptWait::NoWait);
}

/// 启动日志捕获进程，并把上一次的日志轮转为 `.old.log`。
///
/// 子进程被放入独立的进程组并归入 ksud 的 cgroup，避免拖住启动阶段；
/// 同时由 `timeout` 限制运行时长。
fn catch_bootlog(log_name: &str, command: &[&str]) -> Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::Stdio;

    let log_dir = Path::new(defs::LOG_DIR);
    utils::ensure_dir_exists(log_dir)?;
    // 本轮日志与上一轮分开保存，便于对比“本次启动”与“上次启动”。
    let bootlog_path = log_dir.join(format!("{log_name}.log"));
    let previous_bootlog_path = log_dir.join(format!("{log_name}.old.log"));

    if bootlog_path.exists() {
        std::fs::rename(&bootlog_path, previous_bootlog_path)?;
    }

    let bootlog = std::fs::File::create(bootlog_path)?;

    let mut args = vec!["-s", "9", defs::BOOTLOG_TIMEOUT];
    args.extend_from_slice(command);
    // 由 timeout 在超时后向整个进程组发送 SIGKILL，避免日志进程常驻。
    let result = unsafe {
        std::process::Command::new("timeout")
            .process_group(0)
            .pre_exec(|| {
                utils::switch_cgroups();
                Ok(())
            })
            .args(args)
            .stdout(Stdio::from(bootlog))
            .spawn()
    };

    if let Err(e) = result {
        warn!("Failed to start logcat: {e:#}");
    }

    Ok(())
}
