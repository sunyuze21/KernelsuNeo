//! 模块生命周期管理。
//!
//! 模块位于 `/data/adb/modules/<id>`。新安装先暂存到 `modules_update/<id>`，
//! 在 post-fs-data 阶段才移动到正式目录，从而保证当前系统在重启（或热安装）
//! 之前保持一致。
//!
//! 本模块按职责拆分为几个子模块，并由这里统一导出，外部调用方仍然使用
//! `crate::module::<name>`：
//!
//! - [`install`]：安装 ZIP、提交暂存更新；
//! - [`lifecycle`]：启用/禁用/卸载、脚本与属性加载；
//! - [`list`]：读取 `module.prop` 并生成 Manager 使用的列表；
//! - [`preinit_config`]：生成下次开机读取的 `modules.rc` 与 preinit 目录。

mod install;
mod lifecycle;
mod list;
mod preinit_config;

pub use install::{handle_updated_modules, install_module};
pub use lifecycle::{
    disable_all_modules, disable_module, enable_module, load_sepolicy_rule, load_system_prop,
    prune_modules, run_action, undo_uninstall_module, uninstall_all_modules, uninstall_module,
};
pub use list::{get_managed_features, list_modules, read_module_prop};
pub use preinit_config::{preinit_ksu_dir, regenerate_preinit};

use anyhow::{Context, Result, anyhow};
use is_executable::is_executable;
use log::{debug, error, info, warn};
use regex_lite::Regex;

use std::{env::var as env_var, fs::canonicalize, path::Path, process::Command, time::Instant};

use crate::defs::{MODULE_DIR, MODULE_UPDATE_DIR};
use crate::utils::{detach_process_group, switch_cgroups};
use crate::{assets, defs, ksucalls, metamodule};
use std::os::unix::process::CommandExt;

/// 校验 module_id 的格式与安全性
/// module_id 必须匹配：^[a-zA-Z][a-zA-Z0-9._-]+$
/// - 必须以字母（a-zA-Z）开头
/// - 后跟一个或多个字母、数字、点、下划线或连字符
/// - 长度至少为 2 个字符
pub fn validate_module_id(module_id: &str) -> Result<()> {
    let re = Regex::new(r"^[a-zA-Z][a-zA-Z0-9._-]+$")?;
    if re.is_match(module_id) {
        Ok(())
    } else {
        Err(anyhow!(
            "Invalid module ID: '{module_id}'. Must match /^[a-zA-Z][a-zA-Z0-9._-]+$/"
        ))
    }
}

/// 所有模块脚本共享的环境变量（KSU/Magisk 风格）。
///
/// 只有校验通过的 id 才会导出 `KSU_MODULE`，防止脚本向标识符注入 shell
/// 元字符。
pub fn common_script_env(module_id: Option<&str>) -> Vec<(&'static str, String)> {
    let mut envs = vec![
        ("ASH_STANDALONE", "1".to_string()),
        ("KSU", "true".to_string()),
        ("KSU_KERNEL_VER_CODE", ksucalls::get_version().to_string()),
        ("KSU_VER_CODE", defs::VERSION_CODE.to_string()),
        ("KSU_VER", defs::VERSION_NAME.to_string()),
        ("KSU_UAPI_VER", ksucalls::uapi_version().to_string()),
        ("KSU_RUNTIME_MODE", ksucalls::runtime_mode().to_string()),
        (
            "PATH",
            format!(
                "{}:{}",
                env_var("PATH").unwrap_or_default(),
                defs::BINARY_DIR.trim_end_matches('/')
            ),
        ),
    ];

    if let Some(id) = module_id {
        if validate_module_id(id).is_ok() {
            envs.push(("KSU_MODULE", id.to_string()));
        } else {
            error!("Invalid module_id provided: {id}");
        }
    }

    if ksucalls::is_late_load() {
        envs.push(("KSU_LATE_LOAD", "1".to_string()));
    }

    envs
}

/// 遍历模块目录时的筛选范围。
#[derive(PartialEq, Eq)]
pub enum ModuleType {
    /// 所有模块（含已禁用和待删除），用于卸载/清理等需要看到全部目录的场景。
    All,
    /// 本次启动实际会执行的模块；跳过禁用与待删除标记。
    Active,
    /// `modules_update/` 中等待下次开机提交的暂存模块。
    Updated,
}

/// 遍历指定类型的模块目录。
///
/// `Active` 会跳过已禁用和已标记删除的模块，因为这两类模块本次启动都不应
/// 执行脚本。
#[allow(clippy::needless_pass_by_value)]
pub fn foreach_module(
    module_type: ModuleType,
    mut callback: impl FnMut(&Path) -> Result<()>,
) -> Result<()> {
    let modules_dir = Path::new(match module_type {
        ModuleType::Updated => MODULE_UPDATE_DIR,
        _ => MODULE_DIR,
    });
    let dir = std::fs::read_dir(modules_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            warn!("{} is not a directory, skip", path.display());
            continue;
        }

        if module_type == ModuleType::Active && path.join(defs::DISABLE_FILE_NAME).exists() {
            info!("{} is disabled, skip", path.display());
            continue;
        }
        if module_type == ModuleType::Active && path.join(defs::REMOVE_FILE_NAME).exists() {
            warn!("{} is removed, skip", path.display());
            continue;
        }

        callback(&path)?;
    }

    Ok(())
}

/// `foreach_module` 的便捷包装：只遍历本次启动实际生效的模块。
fn foreach_active_module(callback: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    foreach_module(ModuleType::Active, callback)
}

/// `exec_script` 如何处理启动的脚本。
#[derive(Clone, Copy)]
pub enum ScriptWait {
    /// 启动后不等待（service/boot-completed 阶段）。
    NoWait,
    /// 阻塞直到脚本退出（安装器、卸载器、action）。
    Forever,
    /// 只等到本次启动阶段的共享截止时间（见 BOOT_STAGE_TIMEOUT）。
    Until(Instant),
}

/// 在脚本批次执行期间阻塞 SIGCHLD 的 RAII 守卫。
///
/// 阻塞 SIGCHLD 后，`wait_until` 可以用 `sigtimedwait` 精确等待某个子进程，
/// 而不会与其他子进程（或同进程组中无关进程）的信号竞争。
struct SigchldGuard {
    /// 只包含 SIGCHLD 的信号集，供 `sigtimedwait` 精确等待。
    set: libc::sigset_t,
    /// 进入守卫前的掩码，`drop` 时恢复，避免影响调用方后续的子进程处理。
    previous_mask: libc::sigset_t,
}

impl SigchldGuard {
    /// 阻塞 SIGCHLD 并记录旧掩码；失败时返回底层 errno。
    fn block() -> std::io::Result<Self> {
        // 在传给 libc 之前先初始化这两个信号集。
        let mut set = unsafe { std::mem::zeroed() };
        let mut previous_mask = unsafe { std::mem::zeroed() };
        unsafe {
            libc::sigemptyset(&raw mut set);
            libc::sigaddset(&raw mut set, libc::SIGCHLD);
            if libc::sigprocmask(libc::SIG_BLOCK, &raw const set, &raw mut previous_mask) != 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        Ok(Self { set, previous_mask })
    }

    /// 若 `pid` 已退出则回收；否则休眠到收到 SIGCHLD 或到达截止时间。
    /// 超时返回 `false`。
    fn wait_until(&self, pid: libc::pid_t, deadline: Instant) -> std::io::Result<bool> {
        loop {
            // 只回收正在等待的脚本。其他子进程也可能发送 SIGCHLD。
            let result = unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) };
            if result == pid {
                return Ok(true);
            }
            if result < 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                return Err(error);
            }

            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Ok(false);
            }
            let timeout = libc::timespec {
                tv_sec: remaining.as_secs() as libc::time_t,
                tv_nsec: remaining.subsec_nanos().into(),
            };
            // 在 waitpid 与 sigtimedwait 之间保持 SIGCHLD 阻塞，以免丢失唤醒。
            if unsafe {
                libc::sigtimedwait(
                    &raw const self.set,
                    std::ptr::null_mut(),
                    &raw const timeout,
                )
            } < 0
            {
                let error = std::io::Error::last_os_error();
                match error.raw_os_error() {
                    Some(libc::EAGAIN) => return Ok(false),
                    Some(libc::EINTR) => {}
                    _ => return Err(error),
                }
            }
        }
    }
}

impl Drop for SigchldGuard {
    /// 恢复之前记录的信号掩码；恢复失败只告警，避免在析构路径上 panic。
    fn drop(&mut self) {
        // 无论成功、超时还是任何错误路径，都要恢复父进程的信号掩码。
        if unsafe {
            libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const self.previous_mask,
                std::ptr::null_mut(),
            )
        } != 0
        {
            warn!(
                "Failed to restore signal mask: {}",
                std::io::Error::last_os_error()
            );
        }
    }
}

/// 用 busybox sh 启动 `path`，并带上标准模块环境变量。
pub fn exec_script<T: AsRef<Path>>(path: T, wait: ScriptWait) -> Result<()> {
    exec_script_with(path, wait, |_| {})
}

/// 与 `exec_script` 类似，但允许调用方在启动前调整命令。
///
/// 通过脚本路径判断是否属于模块，从而设置 `KSU_MODULE`；子进程在 exec 前还会
/// 恢复调用方的信号掩码、脱离进程组并切换 cgroup。
pub fn exec_script_with<T: AsRef<Path>>(
    path: T,
    wait: ScriptWait,
    configure_command: impl FnOnce(&mut Command),
) -> Result<()> {
    info!("exec {}", path.as_ref().display());

    let is_module_script = path.as_ref().starts_with(MODULE_DIR);
    // 若路径匹配 /data/adb/modules/{id}/...，则从中提取 module_id
    let module_id = if is_module_script {
        path.as_ref()
            .strip_prefix(MODULE_DIR)
            .ok()
            .and_then(|p| p.components().next())
            .and_then(|c| c.as_os_str().to_str())
            .map(ToString::to_string)
    } else {
        None
    };

    // 校验并记录 module_id 的提取结果
    let script_module_id = module_id
        .as_ref()
        .and_then(|id| match validate_module_id(id) {
            Ok(()) => {
                debug!("Module ID extracted from script path: '{id}'");
                Some(id.as_str())
            }
            Err(e) => {
                warn!(
                    "Invalid module ID '{id}' extracted from script path '{}': {e}",
                    path.as_ref().display(),
                );
                None
            }
        });

    if is_module_script && module_id.is_none() {
        debug!(
            "Failed to extract module_id from script path '{}'. Script will run without KSU_MODULE environment variable.",
            path.as_ref().display()
        );
    }

    let sigchld_guard = SigchldGuard::block().context("Failed to block SIGCHLD")?;
    let original_sigmask = sigchld_guard.previous_mask;
    let mut command = Command::new(assets::BUSYBOX_PATH);
    unsafe {
        command.pre_exec(move || {
            if libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const original_sigmask,
                std::ptr::null_mut(),
            ) != 0
            {
                return Err(std::io::Error::last_os_error());
            }
            detach_process_group(true);
            // 忽略这里的错误？
            switch_cgroups();
            Ok(())
        })
    };
    command
        .current_dir(path.as_ref().parent().unwrap())
        .arg("sh")
        .arg(path.as_ref())
        .envs(common_script_env(script_module_id));
    configure_command(&mut command);

    let result = match wait {
        ScriptWait::NoWait => command.spawn().map(|_| ()),
        ScriptWait::Forever => command.status().map(|_| ()),
        ScriptWait::Until(deadline) => command.spawn().and_then(|child| {
            // 共享截止时间已过，后续脚本直接启动而不再等待。
            if deadline > Instant::now()
                && !sigchld_guard.wait_until(child.id() as libc::pid_t, deadline)?
            {
                warn!("Timed out waiting for script: {}", path.as_ref().display());
            }
            Ok(())
        }),
    };
    result.map_err(|e| anyhow!("Failed to exec {}: {e}", path.as_ref().display()))
}

/// 依次执行所有活动模块的 `<stage>.sh`。
///
/// 会跳过元模块自身（由 `metamodule::exec_stage_script` 单独处理），确保元模块先建立
/// 普通模块脚本所依赖的挂载。
pub fn exec_stage_script(stage: &str, wait: ScriptWait) -> Result<()> {
    let metamodule_dir = metamodule::get_metamodule_path().and_then(|path| canonicalize(path).ok());

    foreach_active_module(|module| {
        if metamodule_dir.as_ref().is_some_and(|meta_dir| {
            canonicalize(module).is_ok_and(|resolved| resolved == *meta_dir)
        }) {
            return Ok(());
        }

        let script_path = module.join(format!("{stage}.sh"));
        if !script_path.exists() {
            return Ok(());
        }

        exec_script(&script_path, wait)
    })?;

    Ok(())
}

/// 执行 `/data/adb/<dir>` 下的公共脚本。
///
/// 只运行带可执行位的文件，`wait` 决定每个脚本是否阻塞及阻塞到何时；目录不存在时静默跳过。
pub fn exec_common_scripts(dir: &str, wait: ScriptWait) -> Result<()> {
    let script_dir = Path::new(defs::ADB_DIR).join(dir);
    if !script_dir.exists() {
        info!("{} not exists, skip", script_dir.display());
        return Ok(());
    }

    let dir = std::fs::read_dir(&script_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();

        if !is_executable(&path) {
            warn!("{} is not executable, skip", path.display());
            continue;
        }

        exec_script(path, wait)?;
    }

    Ok(())
}
