//! 模块生命周期管理：安装、启用/禁用、卸载、脚本执行、列表输出，以及内核在
//! 下次启动时读取的 preinit 状态（`modules.rc` / preinit 目录）。
//!
//! 模块位于 `/data/adb/modules/<id>`。新安装先暂存到 `modules_update/<id>`，
//! 在 post-fs-data 阶段才移动到正式目录，从而保证当前系统在重启（或热安装）
//! 之前保持一致。

#[allow(clippy::wildcard_imports)]
use crate::utils::*;
use crate::{
    assets, defs, hot_install, ksucalls, metamodule,
    restorecon::{restore_syscon, setsyscon},
    sepolicy,
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use const_format::concatcp;
use is_executable::is_executable;
use java_properties::PropertiesIter;
use log::{debug, error, info, warn};
use regex_lite::Regex;

use std::{
    collections::{BTreeMap, HashMap},
    env::var as env_var,
    fs::{File, Permissions, canonicalize, remove_dir_all, set_permissions},
    io::Cursor,
    path::{Path, PathBuf},
    process::Command,
    str::FromStr,
    time::Instant,
};
use std::{
    fs::{copy, rename},
    io::Write,
};
use zip_extensions::inflate::zip_extract::zip_extract_file_to_memory;

use crate::defs::{MODULE_DIR, MODULE_UPDATE_DIR, UPDATE_FILE_NAME};
use crate::module::ModuleType::{Active, All};
use crate::module_integrity_check::{INTEGRITY_CHECK_PATH, module_integrity_check};
use std::os::unix::{prelude::PermissionsExt, process::CommandExt};

// 可刷写 ZIP 的安装脚本被内嵌进 ksud，因此无需 Manager 也能安装模块
// （例如在 recovery 或 root shell 中）。
const INSTALLER_CONTENT: &str = include_str!("./installer.sh");
const INSTALL_MODULE_SCRIPT: &str = concatcp!(
    INSTALLER_CONTENT,
    "\n",
    "install_module",
    "\n",
    "exit 0",
    "\n"
);

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
pub fn get_common_script_envs(module_id: Option<&str>) -> Vec<(&'static str, String)> {
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

/// 执行 ZIP 中的 installer.sh；`is_metamodule` 为真时允许 metamodule
/// 替换成自己的安装器。
fn exec_install_script(module_file: &str, is_metamodule: bool, module_id: &str) -> Result<()> {
    let realpath = std::fs::canonicalize(module_file)
        .with_context(|| format!("realpath: {module_file} failed"))?;

    // 从 metamodule 模块中获取安装脚本
    let install_script =
        metamodule::get_install_script(is_metamodule, INSTALLER_CONTENT, INSTALL_MODULE_SCRIPT)?;

    let result = Command::new(assets::BUSYBOX_PATH)
        .args(["sh", "-c", &install_script])
        .envs(get_common_script_envs(Some(module_id)))
        .env("OUTFD", "1")
        .env("ZIPFILE", realpath)
        .status()?;
    ensure!(result.success(), "Failed to install module script");
    Ok(())
}

// 安装模块前检查 Android 是否已完成启动
fn ensure_boot_completed() -> Result<()> {
    // 确保 getprop sys.boot_completed == 1
    if getprop("sys.boot_completed").as_deref() != Some("1") {
        bail!("Android is Booting!");
    }
    Ok(())
}

#[derive(PartialEq, Eq)]
pub enum ModuleType {
    All,
    Active,
    Updated,
}

/// 遍历指定类型的模块目录。
///
/// `Active` 会跳过已禁用和已标记删除的模块，因为这两类模块本次启动都不应
/// 执行脚本。
#[allow(clippy::needless_pass_by_value)]
pub fn foreach_module(
    module_type: ModuleType,
    mut f: impl FnMut(&Path) -> Result<()>,
) -> Result<()> {
    let modules_dir = Path::new(match module_type {
        ModuleType::Updated => MODULE_UPDATE_DIR,
        _ => defs::MODULE_DIR,
    });
    let dir = std::fs::read_dir(modules_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            warn!("{} is not a directory, skip", path.display());
            continue;
        }

        if module_type == Active && path.join(defs::DISABLE_FILE_NAME).exists() {
            info!("{} is disabled, skip", path.display());
            continue;
        }
        if module_type == Active && path.join(defs::REMOVE_FILE_NAME).exists() {
            warn!("{} is removed, skip", path.display());
            continue;
        }

        f(&path)?;
    }

    Ok(())
}

fn foreach_active_module(f: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    foreach_module(Active, f)
}

pub fn load_sepolicy_rule() -> Result<()> {
    foreach_active_module(|path| {
        let rule_file = path.join("sepolicy.rule");
        if !rule_file.exists() {
            return Ok(());
        }
        info!("load policy: {}", rule_file.display());

        if sepolicy::apply_file(&rule_file).is_err() {
            warn!("Failed to load sepolicy.rule for {}", rule_file.display());
        }
        Ok(())
    })?;

    Ok(())
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
struct SigchldBlock {
    set: libc::sigset_t,
    previous: libc::sigset_t,
}

impl SigchldBlock {
    fn block() -> std::io::Result<Self> {
        // 在传给 libc 之前先初始化这两个信号集。
        let mut set = unsafe { std::mem::zeroed() };
        let mut previous = unsafe { std::mem::zeroed() };
        unsafe {
            libc::sigemptyset(&raw mut set);
            libc::sigaddset(&raw mut set, libc::SIGCHLD);
            if libc::sigprocmask(libc::SIG_BLOCK, &raw const set, &raw mut previous) != 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        Ok(Self { set, previous })
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

impl Drop for SigchldBlock {
    fn drop(&mut self) {
        // 无论成功、超时还是任何错误路径，都要恢复父进程的信号掩码。
        if unsafe {
            libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const self.previous,
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
    configure: impl FnOnce(&mut Command),
) -> Result<()> {
    info!("exec {}", path.as_ref().display());

    let is_module_script = path.as_ref().starts_with(defs::MODULE_DIR);
    // 若路径匹配 /data/adb/modules/{id}/...，则从中提取 module_id
    let module_id = if is_module_script {
        path.as_ref()
            .strip_prefix(defs::MODULE_DIR)
            .ok()
            .and_then(|p| p.components().next())
            .and_then(|c| c.as_os_str().to_str())
            .map(ToString::to_string)
    } else {
        None
    };

    // 校验并记录 module_id 的提取结果
    let validated_module_id = module_id
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

    let sigchld = SigchldBlock::block().context("Failed to block SIGCHLD")?;
    let original_sigmask = sigchld.previous;
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
        .envs(get_common_script_envs(validated_module_id));
    configure(&mut command);

    let result = match wait {
        ScriptWait::NoWait => command.spawn().map(|_| ()),
        ScriptWait::Forever => command.status().map(|_| ()),
        ScriptWait::Until(deadline) => command.spawn().and_then(|child| {
            // 共享截止时间已过，后续脚本直接启动而不再等待。
            if deadline > Instant::now()
                && !sigchld.wait_until(child.id() as libc::pid_t, deadline)?
            {
                warn!("Timed out waiting for script: {}", path.as_ref().display());
            }
            Ok(())
        }),
    };
    result.map_err(|e| anyhow!("Failed to exec {}: {e}", path.as_ref().display()))
}

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

pub fn load_system_prop() -> Result<()> {
    foreach_active_module(|module| {
        let system_prop = module.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load {} system.prop", module.display());

        crate::resetprop::load_system_prop_file(&system_prop)?;

        Ok(())
    })?;

    Ok(())
}

/// 删除带 `remove` 标记的模块。
///
/// 先执行 metamodule 的卸载钩子，再执行模块自身的 `uninstall.sh`，最后清理
/// 配置并删除目录。该流程在 post-fs-data 执行，使模块有完整一次启动的时间做
/// 清理。
pub fn prune_modules() -> Result<()> {
    foreach_module(All, |module| {
        if !module.join(defs::REMOVE_FILE_NAME).exists() {
            return Ok(());
        }

        info!("remove module: {}", module.display());

        // 先执行 metamodule 的 metauninstall.sh
        let module_id = module.file_name().and_then(|n| n.to_str()).unwrap_or("");

        // 检查它是否为 metamodule
        let is_metamodule =
            read_module_prop(module).is_ok_and(|props| metamodule::is_metamodule(&props));

        if is_metamodule {
            info!("Removing metamodule symlink");
            if let Err(e) = metamodule::remove_symlink() {
                warn!("Failed to remove metamodule symlink: {e}");
            }
        } else if let Err(e) = metamodule::exec_metauninstall_script(module_id) {
            warn!("Failed to exec metamodule uninstall for {module_id}: {e}");
        }

        // 再执行模块自身的 uninstall.sh
        let uninstaller = module.join("uninstall.sh");
        if uninstaller.exists()
            && let Err(e) = exec_script(uninstaller, ScriptWait::Forever)
        {
            warn!("Failed to exec uninstaller: {e}");
        }

        // 删除模块目录前先清理模块配置
        if let Err(e) = crate::module_config::clear_module_configs(module_id) {
            warn!("Failed to clear configs for {module_id}: {e}");
        }

        // 最后删除模块目录
        if let Err(e) = remove_dir_all(module) {
            warn!("Failed to remove {}: {e}", module.display());
        }

        Ok(())
    })?;

    // 收集剩余的模块；若一个不剩，则清理 metamodule 记录
    let remaining_modules: Vec<_> = std::fs::read_dir(defs::MODULE_DIR)?
        .filter_map(std::result::Result::ok)
        .filter(|entry| entry.path().join("module.prop").exists())
        .collect();

    if remaining_modules.is_empty() {
        info!("no remaining modules.");
    }

    Ok(())
}

// 低于该 UAPI 版本的内核只会在 /metadata/watchdog 和 /metadata 下查找 modules.rc。
const PREINIT_VOLD_UAPI_VERSION: u32 = 6;

/// 选择当前内核会读取的 preinit 目录。
///
/// 较新的内核优先 `/metadata/vold/ksu`（对大多数扫描器隐藏）；旧内核只查找
/// `/metadata/watchdog` 或 `/metadata`。
// 内核会读取时优先用 /metadata/vold/，否则用 /metadata/watchdog/，最后用 /metadata。
pub fn preinit_ksu_dir() -> &'static str {
    if ksucalls::get_info().uapi_version >= PREINIT_VOLD_UAPI_VERSION
        && Path::new("/metadata/vold").is_dir()
    {
        defs::PREINIT_DIR_VOLD
    } else if Path::new("/metadata/watchdog").is_dir() {
        defs::PREINIT_DIR_WATCHDOG
    } else {
        defs::PREINIT_DIR_DEFAULT
    }
}

/// preinit 文件使用的 SELinux context，与宿主目录保持一致，
/// 以免 init 的 restorecon 与我们的设置冲突。
pub fn preinit_con(dir: &str) -> &'static str {
    if dir == defs::PREINIT_DIR_VOLD {
        defs::VOLD_METADATA_FILE_CON
    } else {
        defs::METADATA_FILE_CON
    }
}

/// 把 `dir` 下所有 `.rc` 文件按文件名排序后追加到 `out`。
///
/// `mod_id` 用于区分模块的 initrc 目录（全部包含并标注来源）与公共的
/// `/data/adb/initrc.d`（后者用可执行位作为启用开关）。
fn collect_rc_files<P: AsRef<Path>>(
    dir: P,
    mod_id: Option<&str>,
    out: &mut dyn Write,
) -> Result<()> {
    let dir = dir.as_ref();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Ok(());
    };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_file() && path.extension().and_then(|s| s.to_str()) == Some("rc") {
            if let Some(mod_id) = mod_id {
                writeln!(out, "# === from {mod_id}:{} ===", path.display())?;
            } else {
                // rc 文件本身不可执行，但我们仍用它的可执行位作为启用开关。
                if !is_executable(&path) {
                    continue;
                }
                writeln!(out, "# === from {} ===", path.display())?;
            }
            let content = std::fs::read(&path)
                .with_context(|| format!("Failed to read rc {}", path.display()))?;
            out.write_all(&content)?;
            writeln!(out)?;
        }
    }
    Ok(())
}

/// 下次开机时启用的模块（按 id）。modules_update/ 优先于 modules/；
/// 处于禁用或待删除状态的模块会被跳过，即使另一目录中存在启用副本。
fn next_boot_modules() -> BTreeMap<String, PathBuf> {
    let mut modules: BTreeMap<String, Option<PathBuf>> = BTreeMap::new();
    for src_dir in [defs::MODULE_UPDATE_DIR, defs::MODULE_DIR] {
        let Ok(entries) = std::fs::read_dir(src_dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let module_path = entry.path();
            if !module_path.is_dir() {
                continue;
            }
            let Some(id) = module_path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            let id = id.to_string();
            if module_path.join(defs::DISABLE_FILE_NAME).exists()
                || module_path.join(defs::REMOVE_FILE_NAME).exists()
            {
                modules.insert(id, None);
                continue;
            }
            modules.entry(id).or_insert(Some(module_path));
        }
    }
    modules
        .into_iter()
        .filter_map(|(id, path)| path.map(|path| (id, path)))
        .collect()
}

/// 通过拼接每个已启用模块的 *.rc 重建 PREINITDIR/modules.rc。
/// 内核侧的读取钩子会在下次开机时
/// 把该文件拼接进 init.rc。
fn regenerate_preinit_rc(
    preinit_str: &str,
    modules: &BTreeMap<String, PathBuf>,
    con: &str,
) -> Result<()> {
    let preinit_dir = Path::new(preinit_str);
    std::fs::create_dir_all(preinit_dir)
        .with_context(|| format!("Failed to create {}", preinit_dir.display()))?;

    let tmp_path_buf = preinit_dir.join(defs::MODULES_RC_TMP_FILE);
    let out_path_buf = preinit_dir.join(defs::MODULES_RC_FILE);
    let tmp_path = tmp_path_buf.as_path();
    let out_path = out_path_buf.as_path();

    {
        let mut tmp = File::create(tmp_path)
            .with_context(|| format!("Failed to create {}", tmp_path.display()))?;

        // 先收集公共的 initrc
        collect_rc_files(Path::new(defs::ADB_DIR).join("initrc.d"), None, &mut tmp)?;
        // 再按字母顺序收集模块，并使用它们下次开机时的实际模块路径
        for (id, path) in modules {
            collect_rc_files(path.join(defs::MODULE_INIT_RC_DIR), Some(id), &mut tmp)?;
        }
        tmp.sync_all()?;
    }

    std::fs::rename(tmp_path, out_path).with_context(|| {
        format!(
            "Failed to rename {} -> {}",
            tmp_path.display(),
            out_path.display()
        )
    })?;

    // 设置 SELinux 标签，使内核在 init 上下文中调用 filp_open 时能读取它。
    if let Err(e) = crate::restorecon::lsetfilecon(out_path, con) {
        debug!("set context on {} failed: {e}", out_path.display());
    }

    Ok(())
}

/// 重建内核下次启动会从 preinit 分区读取的内容：modules.rc 和各模块的
/// preinit 目录。
///
/// 每次模块集合变化（安装、启用/禁用、卸载）后都会调用，并在 post-fs-data
/// 作为兜底再执行一次。当前启动内核未读取的候选目录也会清理，避免 OTA 改变
/// 内核偏好后仍读到过期的 modules.rc。
pub fn regenerate_preinit() -> Result<()> {
    let preinit_str = preinit_ksu_dir();
    let con = preinit_con(preinit_str);
    // 本次启动不会使用的候选目录；清理其中遗留的内容。
    let stale_strs: Vec<&str> = [
        defs::PREINIT_DIR_VOLD,
        defs::PREINIT_DIR_WATCHDOG,
        defs::PREINIT_DIR_DEFAULT,
    ]
    .into_iter()
    .filter(|d| *d != preinit_str)
    .collect();
    let modules = next_boot_modules();

    let rc = regenerate_preinit_rc(preinit_str, &modules, con);
    // 清理其他候选路径下过期的 modules.rc。
    for stale in &stale_strs {
        std::fs::remove_file(Path::new(stale).join(defs::MODULES_RC_FILE)).ok();
    }

    let stale_dirs: Vec<&Path> = stale_strs.iter().map(Path::new).collect();
    let preinit = crate::preinit::sync(
        Path::new(preinit_str),
        &stale_dirs,
        &modules,
        con,
        crate::ksucalls::is_preinit_supported(),
    );
    rc.and(preinit)
}

/// 把 `modules_update/` 中暂存的模块提升到 `modules/`。
///
/// 覆盖已有模块时会保留其禁用/删除标记，避免更新悄悄重新启用用户关掉的模块。
pub fn handle_updated_modules() -> Result<()> {
    let modules_root = Path::new(MODULE_DIR);
    foreach_module(ModuleType::Updated, |updated_module| {
        if !updated_module.is_dir() {
            return Ok(());
        }

        if let Some(name) = updated_module.file_name() {
            let module_dir = modules_root.join(name);
            let mut disabled = false;
            let mut removed = false;
            if module_dir.exists() {
                // 若旧模块已禁用，新模块也必须保持禁用
                disabled = module_dir.join(defs::DISABLE_FILE_NAME).exists();
                removed = module_dir.join(defs::REMOVE_FILE_NAME).exists();
                remove_dir_all(&module_dir)?;
            }
            rename(updated_module, &module_dir)?;
            if removed {
                let path = module_dir.join(defs::REMOVE_FILE_NAME);
                if let Err(e) = ensure_file_exists(&path) {
                    warn!("Failed to create {}: {e}", path.display());
                }
            } else if disabled {
                let path = module_dir.join(defs::DISABLE_FILE_NAME);
                if let Err(e) = ensure_file_exists(&path) {
                    warn!("Failed to create {}: {e}", path.display());
                }
            }
        }
        Ok(())
    })?;
    Ok(())
}

/// 把模块 ZIP 安装到 `modules_update/` 并执行其安装器。
///
/// 若安装器请求热安装，则跳过正常的“暂存到下次启动”流程，立即应用模块。
fn install_module_to_system(zip: &str) -> Result<String> {
    // 在 Android 尚未启动完成时安装会与 post-fs-data 的模块提升流程竞争，
    // 因此要求 boot_completed。
    ensure_boot_completed()?;

    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    // 先检查工作目录是否可用
    ensure_dir_exists(defs::WORKING_DIR).with_context(|| "Failed to create working dir")?;
    ensure_dir_exists(defs::BINARY_DIR).with_context(|| "Failed to create bin dir")?;

    // 从 zip 中读取 module_id，失败则提前返回。
    let mut buffer: Vec<u8> = Vec::new();
    let entry_path = PathBuf::from_str("module.prop")?;
    let zip_path = PathBuf::from_str(zip)?;
    let zip_path = zip_path.canonicalize()?;
    zip_extract_file_to_memory(&zip_path, &entry_path, &mut buffer)?;

    let mut module_prop = HashMap::new();
    PropertiesIter::new_with_encoding(Cursor::new(buffer), encoding_rs::UTF_8).read_into(
        |k, v| {
            module_prop.insert(k, v);
        },
    )?;
    info!("module prop: {module_prop:?}");

    let Some(module_id) = module_prop.get("id") else {
        bail!("module id not found in module.prop!");
    };
    let module_id = module_id.trim();

    // 校验 module_id 格式
    validate_module_id(module_id)
        .with_context(|| format!("Invalid module ID in module.prop: '{module_id}'"))?;

    // 检查该模块是否为 metamodule
    let is_metamodule = metamodule::is_metamodule(&module_prop);

    // 检查安装普通模块是否安全
    if !is_metamodule && let Err(is_disabled) = metamodule::check_install_safety() {
        println!("! Installation blocked");
        println!("! A metamodule with a custom installer is active");
        if is_disabled {
            println!("! Current state: disabled");
            println!("! Re-enable or uninstall it, then reboot");
        } else {
            println!("! Current state: pending changes");
            println!("! Reboot to apply changes first");
        }
        bail!("Cannot install a regular module while a metamodule is active");
    }

    // 所有模块（包括 metamodule）都安装到 MODULE_UPDATE_DIR
    let updated_dir = Path::new(defs::MODULE_UPDATE_DIR).join(module_id);

    if is_metamodule {
        info!("Installing metamodule: {module_id}");

        // 检查是否已安装 metamodule
        if metamodule::has_metamodule()
            && let Some(existing_path) = metamodule::get_metamodule_path()
        {
            let existing_id = read_module_prop(&existing_path)
                .ok()
                .and_then(|m| m.get("id").cloned())
                .unwrap_or_else(|| "unknown".to_string());

            if existing_id != module_id {
                println!("! Installation failed");
                println!("! A metamodule is already installed: {existing_id}");
                println!("! Only one metamodule can be active at a time");
                println!("! Uninstall the current metamodule and reboot before installing another");
                bail!("Cannot install multiple metamodules");
            }
        }
    }

    let zip_uncompressed_size = get_zip_uncompressed_size(zip)?;
    info!(
        "zip uncompressed size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );
    println!(
        "- Module size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );

    // 确保模块目录存在，并设置 SELinux context
    ensure_dir_exists(defs::MODULE_UPDATE_DIR)?;
    setsyscon(defs::MODULE_UPDATE_DIR)?;

    // 准备目标目录
    println!("- Installing to {}", updated_dir.display());
    ensure_clean_dir(&updated_dir)?;
    info!("target dir: {}", updated_dir.display());

    // 将 zip 解压到目标目录
    println!("- Extracting module files");
    let file = File::open(zip)?;
    let mut archive = zip::ZipArchive::new(file)?;
    archive.extract(&updated_dir)?;
    // 只有 installer.sh 可以请求热安装，zip 中自带的文件不行
    std::fs::remove_file(updated_dir.join(defs::HOT_INSTALL_MARKER)).ok();

    // 为 $MOD/system 设置权限和 selinux context
    let module_system_dir = updated_dir.join("system");
    if module_system_dir.exists() {
        set_permissions(&module_system_dir, Permissions::from_mode(0o755))?;
        restore_syscon(&module_system_dir)?;
    }

    // 执行安装脚本
    println!("- Running module installer");
    exec_install_script(zip, is_metamodule, module_id)?;

    if let Some(request) = hot_install::take_request(&updated_dir)
        && hot_install::apply(module_id, is_metamodule, &request)
    {
        println!("- Module installed successfully (no reboot required)");
        info!("Module {module_id} installed successfully!");
        return Ok(module_id.to_owned());
    }

    let module_dir = Path::new(MODULE_DIR).join(module_id);
    // 自行热安装的 metamodule 可能已把标记文件移动到实际模块目录
    std::fs::remove_file(module_dir.join(defs::HOT_INSTALL_MARKER)).ok();
    ensure_dir_exists(&module_dir)?;
    copy(
        updated_dir.join("module.prop"),
        module_dir.join("module.prop"),
    )?;
    ensure_file_exists(module_dir.join(UPDATE_FILE_NAME))?;

    // 为 metamodule 创建符号链接
    if is_metamodule {
        println!("- Creating metamodule symlink");
        metamodule::ensure_symlink(&module_dir)?;
    }

    println!("- Module installed successfully");
    info!("Module {module_id} installed successfully!");

    Ok(module_id.to_owned())
}

/// `ksud module install` 的 CLI 入口：先检查 UAPI 兼容性，再操作磁盘状态，
/// 最后刷新 preinit。
pub fn install_module(zip: &str) -> Result<()> {
    ksucalls::ensure_uapi_version_matched()?;

    println!(include_str!("banner"));

    // 模块签名校验需要显式开启；未开启时保持原来的安装流程。
    if Path::new(INTEGRITY_CHECK_PATH).exists() {
        println!("- Verifying module signature");
        module_integrity_check(zip)
            .map_err(|e| anyhow!("Module signature verification failed: {e}"))?;
        println!("- Module signature verified");
    }
    let module_id = install_module_to_system(zip)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    if let Some((state, message)) = crate::preinit::status_of(&module_id)
        && state != "ok"
    {
        println!("! preinit will not take effect: {state} {message}");
    }
    Ok(())
}

pub fn undo_uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // 移除 remove 标记
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    if remove_file.exists() {
        std::fs::remove_file(&remove_file)
            .with_context(|| format!("Failed to delete remove file for module '{id}'"))?;
        info!("Removed the remove mark for module {id}");
    }

    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    Ok(())
}

/// 把模块标记为待删除；`prune_modules` 会在下次 post-fs-data 删除它，
/// 因此不影响当前这次启动。
pub fn uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // 标记为待删除
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    File::create(remove_file).with_context(|| "Failed to create remove file")?;

    info!("Module {id} marked for removal");

    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    Ok(())
}

pub fn run_action(id: &str) -> Result<()> {
    validate_module_id(id)?;
    ksucalls::ensure_uapi_version_matched()?;

    let action_script_path = format!("/data/adb/modules/{id}/action.sh");
    exec_script(&action_script_path, ScriptWait::Forever)
}

pub fn enable_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    if disable_path.exists() {
        std::fs::remove_file(&disable_path).with_context(|| {
            format!("Failed to remove disable file: {}", disable_path.display())
        })?;
        info!("Module {id} enabled");
    }

    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    Ok(())
}

pub fn disable_module(id: &str) -> Result<()> {
    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    ensure_file_exists(disable_path)?;

    info!("Module {id} disabled");

    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    Ok(())
}

pub fn disable_all_modules() -> Result<()> {
    mark_all_modules(defs::DISABLE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    Ok(())
}

pub fn uninstall_all_modules() -> Result<()> {
    info!("Uninstalling all modules");
    mark_all_modules(defs::REMOVE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    Ok(())
}

fn mark_all_modules(flag_file: &str) -> Result<()> {
    // 假定模块目录已经挂载
    let dir = std::fs::read_dir(defs::MODULE_DIR)?;
    for entry in dir.flatten() {
        let path = entry.path();
        let flag = path.join(flag_file);
        if let Err(e) = ensure_file_exists(flag) {
            warn!("Failed to mark module: {}: {e}", path.display());
        }
    }

    Ok(())
}

/// 从给定模块路径读取 module.prop，并以 HashMap 形式返回
pub fn read_module_prop(module_path: &Path) -> Result<HashMap<String, String>> {
    let module_prop = module_path.join("module.prop");
    ensure!(
        module_prop.exists(),
        "module.prop not found in {}",
        module_path.display()
    );

    let content = std::fs::read(&module_prop)
        .with_context(|| format!("Failed to read module.prop: {}", module_prop.display()))?;

    let mut prop_map: HashMap<String, String> = HashMap::new();
    PropertiesIter::new_with_encoding(Cursor::new(content), encoding_rs::UTF_8)
        .read_into(|k, v| {
            prop_map.insert(k, v);
        })
        .with_context(|| format!("Failed to parse module.prop: {}", module_prop.display()))?;

    Ok(prop_map)
}

/// 把模块图标路径解析为磁盘上的绝对路径
fn resolve_module_icon_path(
    module_prop_map: &mut HashMap<String, String>,
    key: &str,
    module_path: &Path,
) {
    if let Some(icon_value) = module_prop_map.get(key) {
        let icon_value = icon_value.trim();
        if icon_value.is_empty() {
            return;
        }
        let path = std::path::Path::new(icon_value);
        if path.is_absolute() {
            log::warn!(
                "Rejected {} with absolute path for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let has_parent = path
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir));
        if has_parent {
            log::warn!(
                "Rejected {} with parent traversal for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let candidate = module_path.join(path);
        if candidate.exists() && candidate.is_file() {
            if let Some(s) = candidate.to_str() {
                module_prop_map.insert(key.to_owned(), s.to_string());
            }
        } else {
            log::debug!(
                "{} not found for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                candidate.display()
            );
        }
    }
}

/// 构造供 Manager 使用的 JSON 模块列表。
///
/// 除 module.prop 字段外，还会附加派生标志（enabled、update、remove、web、
/// action、mount）、配置覆盖项以及模块的 preinit 状态。
fn list_module(path: &str) -> Vec<HashMap<String, String>> {
    // 一次性加载所有模块配置，尽量减少 I/O 开销
    let all_configs = match crate::module_config::get_all_module_configs() {
        Ok(configs) => configs,
        Err(e) => {
            warn!("Failed to load module configs: {e}");
            HashMap::new()
        }
    };

    let preinit_states = crate::preinit::module_states();

    // 先检查已启用的模块
    let dir = std::fs::read_dir(path);
    let Ok(dir) = dir else {
        return Vec::new();
    };

    let mut modules: Vec<HashMap<String, String>> = Vec::new();

    for entry in dir.flatten() {
        let path = entry.path();
        info!("path: {}", path.display());

        if !path.join("module.prop").exists() {
            continue;
        }

        let mut module_prop_map = match read_module_prop(&path) {
            Ok(prop) => prop,
            Err(e) => {
                warn!("Failed to read module.prop for {}: {e}", path.display());
                continue;
            }
        };

        // 若 id 缺失或为空，则回退为目录名
        if !module_prop_map.contains_key("id") || module_prop_map["id"].is_empty() {
            if let Some(id) = entry.file_name().to_str() {
                info!("Use dir name as module id: {id}");
                module_prop_map.insert("id".to_owned(), id.to_owned());
            } else {
                info!("Failed to get module id from dir name");
                continue;
            }
        }

        // Manager 渲染模块卡片所用的派生标志。`mount` 沿用 Magisk 的
        // skip_mount 约定：存在 system/ 目录即表示模块希望覆盖分区，
        // 除非存在 skip_mount。
        let enabled = !path.join(defs::DISABLE_FILE_NAME).exists();
        let update = path.join(defs::UPDATE_FILE_NAME).exists();
        let remove = path.join(defs::REMOVE_FILE_NAME).exists();
        let web = path.join(defs::MODULE_WEB_DIR).exists();
        let action = path.join(defs::MODULE_ACTION_SH).exists();
        let need_mount = path.join("system").exists() && !path.join("skip_mount").exists();

        module_prop_map.insert("enabled".to_owned(), enabled.to_string());
        module_prop_map.insert("update".to_owned(), update.to_string());
        module_prop_map.insert("remove".to_owned(), remove.to_string());
        module_prop_map.insert("web".to_owned(), web.to_string());
        module_prop_map.insert("action".to_owned(), action.to_string());
        module_prop_map.insert("mount".to_owned(), need_mount.to_string());
        if let Some(state) = module_prop_map
            .get("id")
            .and_then(|id| preinit_states.get(id.as_str()))
        {
            module_prop_map.insert("preinit".to_owned(), state.clone());
        }

        resolve_module_icon_path(&mut module_prop_map, "actionIcon", &path);
        resolve_module_icon_path(&mut module_prop_map, "webuiIcon", &path);

        // 应用模块配置覆盖项，并提取受管理的功能
        if let Some(module_id) = module_prop_map.get("id")
            && let Some(config) = all_configs.get(module_id.as_str())
        {
            // 应用 override.description
            if let Some(desc) = config.get("override.description") {
                module_prop_map.insert("description".to_owned(), desc.clone());
            }

            // 从 manage.* 配置项中提取受管理的功能
            let managed_features: Vec<String> = config
                .iter()
                .filter_map(|(k, v)| {
                    if k.starts_with("manage.") && crate::module_config::parse_bool_config(v) {
                        k.strip_prefix("manage.")
                            .map(std::string::ToString::to_string)
                    } else {
                        None
                    }
                })
                .collect();

            if !managed_features.is_empty() {
                module_prop_map.insert("managedFeatures".to_owned(), managed_features.join(","));
            }
        }

        modules.push(module_prop_map);
    }

    modules
}

pub fn list_modules() -> Result<()> {
    let modules = list_module(defs::MODULE_DIR);
    // Manager 会把多行输出重新拼接成 JSON；直接输出单行可避免逐行处理带来的
    // 兼容问题，也减少传输量。
    println!("{}", serde_json::to_string(&modules)?);
    Ok(())
}

/// 获取所有活动模块的受管理功能
/// 模块通过配置系统声明受管理的功能（manage.<feature>=true）
/// 返回：HashMap<ModuleId, Vec<ManagedFeature>>
pub fn get_managed_features() -> Result<HashMap<String, Vec<String>>> {
    let mut managed_features_map: HashMap<String, Vec<String>> = HashMap::new();

    foreach_active_module(|module_path| {
        // 获取模块 ID
        let Some(module_id) = module_path.file_name().and_then(|n| n.to_str()) else {
            warn!(
                "Failed to get module id from path: {}",
                module_path.display()
            );
            return Ok(());
        };

        // 读取模块配置
        let config = match crate::module_config::merge_configs(module_id) {
            Ok(c) => c,
            Err(e) => {
                warn!("Failed to merge configs for module '{module_id}': {e}");
                return Ok(()); // 跳过该模块
            }
        };

        // 提取 manage.* 配置项
        let mut feature_list = Vec::new();
        for (key, value) in &config {
            if key.starts_with("manage.") {
                // 解析功能名
                if let Some(feature_name) = key.strip_prefix("manage.")
                    && crate::module_config::parse_bool_config(value)
                {
                    feature_list.push(feature_name.to_string());
                }
            }
        }

        if !feature_list.is_empty() {
            managed_features_map.insert(module_id.to_string(), feature_list);
        }

        Ok(())
    })?;

    Ok(managed_features_map)
}
