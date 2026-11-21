//! 通用小工具：文件/目录初始化、Android 属性读取、命名空间与 cgroup 切换、
//! 守护进程化，以及安装/卸载入口。

use anyhow::{Context, Error, Ok, Result, bail};
use rustix::fs::{Mode, OFlags, open};
use rustix::process::setpgid;
use rustix::stdio::{dup2_stderr, dup2_stdin, dup2_stdout};
use std::{
    ffi::{CStr, CString, c_char, c_void},
    fs::{File, OpenOptions, create_dir_all, remove_file, write},
    io::{
        ErrorKind::{AlreadyExists, NotFound},
        Write,
    },
    path::Path,
    process::Command,
};

use crate::defs::KSU_TEMP_BACKUP_DIR_NAME;
use crate::{assets, boot_patch, defs, ksucalls, module, restorecon};
#[allow(unused_imports)]
use std::fs::{Permissions, set_permissions};
use std::os::unix::prelude::PermissionsExt;

use std::path::PathBuf;

use crate::boot_patch::BootRestoreArgs;

use rustix::{
    process,
    thread::{LinkNameSpaceType, move_into_link_name_space},
};

// 直接绑定 bionic 的属性 API。若每次读取都调用 getprop(1)，启动早期会频繁
// fork 进程，代价过高。
type PropertyReadCallback = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char, u32);

unsafe extern "C" {
    fn __system_property_find(name: *const c_char) -> *const c_void;
    fn __system_property_read_callback(
        property_info: *const c_void,
        callback: PropertyReadCallback,
        cookie: *mut c_void,
    );
}

/// 在编译期选择调试版或发布版取值。
#[macro_export]
macro_rules! debug_select {
    ($debug:expr, $release:expr) => {{
        #[cfg(debug_assertions)]
        {
            $debug
        }
        #[cfg(not(debug_assertions))]
        {
            $release
        }
    }};
}

pub fn ensure_clean_dir(dir: impl AsRef<Path>) -> Result<()> {
    let path = dir.as_ref();
    log::debug!("ensure_clean_dir: {}", path.display());
    if path.exists() {
        log::debug!("ensure_clean_dir: {} exists, remove it", path.display());
        std::fs::remove_dir_all(path)?;
    }
    Ok(std::fs::create_dir_all(path)?)
}

pub fn ensure_file_exists<T: AsRef<Path>>(file: T) -> Result<()> {
    match File::options().write(true).create_new(true).open(&file) {
        std::result::Result::Ok(_) => Ok(()),
        Err(err) => {
            if err.kind() == AlreadyExists && file.as_ref().is_file() {
                Ok(())
            } else {
                Err(Error::from(err))
                    .with_context(|| format!("{} is not a regular file", file.as_ref().display()))
            }
        }
    }
}

pub fn ensure_dir_exists<T: AsRef<Path>>(dir: T) -> Result<()> {
    let result = create_dir_all(&dir);
    if dir.as_ref().is_dir() && result.is_ok() {
        Ok(())
    } else {
        bail!("{} is not a regular directory", dir.as_ref().display())
    }
}

/// 把 `contents` 写成可执行文件 `path`。
///
/// `ignore_if_exist` 为真时保留已有文件；否则先 unlink 再写入，从调用方视角
/// 看相当于替换。
pub fn ensure_binary<T: AsRef<Path>>(
    path: T,
    contents: &[u8],
    ignore_if_exist: bool,
) -> Result<()> {
    if ignore_if_exist && path.as_ref().exists() {
        return Ok(());
    }

    ensure_dir_exists(path.as_ref().parent().ok_or_else(|| {
        anyhow::anyhow!(
            "{} does not have parent directory",
            path.as_ref().to_string_lossy()
        )
    })?)?;

    if let Err(e) = remove_file(path.as_ref())
        && e.kind() != NotFound
    {
        return Err(Error::from(e))
            .with_context(|| format!("failed to unlink {}", path.as_ref().display()));
    }

    write(&path, contents)?;
    set_permissions(&path, Permissions::from_mode(0o755))?;
    Ok(())
}

/// 由 `__system_property_read_callback` 同步回调；cookie 是 `getprop` 持有的
/// `*mut Option<String>`。
unsafe extern "C" fn property_read_callback(
    cookie: *mut c_void,
    _name: *const c_char,
    value: *const c_char,
    _serial: u32,
) {
    if cookie.is_null() || value.is_null() {
        return;
    }

    let result = unsafe { &mut *cookie.cast::<Option<String>>() };
    let value = unsafe { CStr::from_ptr(value) };
    *result = Some(value.to_string_lossy().into_owned());
}

/// 读取 Android 系统属性；属性不存在时返回 `None`。
pub fn getprop(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let property_info = unsafe { __system_property_find(name.as_ptr()) };
    if property_info.is_null() {
        return None;
    }

    let mut value = None;
    unsafe {
        __system_property_read_callback(
            property_info,
            property_read_callback,
            std::ptr::addr_of_mut!(value).cast(),
        );
    }
    value
}

/// 安全模式可能来自 Android 属性（用户开机时按键设置），也可能来自 LKM
/// 延迟加载机制持久化的内核标志。
pub fn is_safe_mode() -> bool {
    let safemode = getprop("persist.sys.safemode")
        .as_ref()
        .is_some_and(|prop| prop == "1")
        || getprop("ro.sys.safemode")
            .as_ref()
            .is_some_and(|prop| prop == "1");
    log::info!("safemode: {safemode}");
    if safemode {
        return true;
    }
    let safemode = ksucalls::check_kernel_safemode();
    log::info!("kernel_safemode: {safemode}");
    safemode
}

pub fn get_zip_uncompressed_size(zip_path: &str) -> Result<u64> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(zip_path)?)?;
    let mut total: u64 = 0;
    for i in 0..zip.len() {
        total += zip.by_index(i)?.size();
    }
    Ok(total)
}

/// 加入另一个进程的挂载命名空间（pid 1 即 init/全局命名空间）。
///
/// setns 会重置进程的根目录/当前目录视图，因此若原当前目录仍可达则重新设置。
pub fn switch_mnt_ns(pid: i32) -> Result<()> {
    use rustix::{
        fd::AsFd,
        fs::{Mode, OFlags, open},
    };
    let path = format!("/proc/{pid}/ns/mnt");
    let fd = open(path, OFlags::RDONLY, Mode::from_raw_mode(0))?;
    let current_dir = std::env::current_dir();
    move_into_link_name_space(fd.as_fd(), Some(LinkNameSpaceType::Mount))?;
    if let std::result::Result::Ok(current_dir) = current_dir {
        let _ = std::env::set_current_dir(current_dir);
    }
    Ok(())
}

/// 通过写入 `cgroup.procs` 把 pid 移入 `grp`；不同 Android 版本缺失某些
/// cgroup 层级很常见，这里静默忽略。
fn switch_cgroup(grp: &str, pid: u32) {
    let path = Path::new(grp).join("cgroup.procs");
    if !path.exists() {
        return;
    }

    let fp = OpenOptions::new().append(true).open(path);
    if let std::result::Result::Ok(mut fp) = fp {
        let _ = write!(fp, "{pid}");
    }
}

/// 让进程脱离调用者的 cgroup，避免 root 进程随启动它的应用一起被限流或杀死。
pub fn switch_cgroups() {
    let pid = std::process::id();
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

pub fn umask(mask: u32) {
    process::umask(rustix::fs::Mode::from_raw_mode(mask));
}

pub fn has_magisk() -> bool {
    which::which("magisk").is_ok()
}

fn link_ksud_to_bin() -> Result<()> {
    let ksu_bin = PathBuf::from(defs::DAEMON_PATH);
    let ksu_bin_link = PathBuf::from(defs::DAEMON_LINK_PATH);
    if ksu_bin.exists() && !ksu_bin_link.exists() {
        std::os::unix::fs::symlink(&ksu_bin, &ksu_bin_link)?;
    }
    Ok(())
}

/// 把 ksud 及其内嵌资源安装到 /data/adb。
///
/// `libadbroot` 和 `data_path` 由 APK 安装器传入：前者是解压出的 adb root
/// 辅助库，后者是安装器暂存启动镜像备份的位置。
pub fn install(libadbroot: Option<PathBuf>, data_path: Option<PathBuf>) -> Result<()> {
    ensure_dir_exists(defs::ADB_DIR)?;
    let _ = std::fs::remove_file(defs::DAEMON_PATH);
    std::fs::copy(
        // 应使用 /proc/self/exe，切勿解析出真实路径，
        // 这样即使用户执行 /data/adb/ksud install，ksud 也不会被意外删除
        "/proc/self/exe",
        defs::DAEMON_PATH,
    )?;
    restorecon::lsetfilecon(defs::DAEMON_PATH, restorecon::KSU_CON)?;
    // 安装二进制资源
    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    link_ksud_to_bin()?;

    if let Some(libadbroot) = libadbroot {
        ensure_dir_exists(defs::LIBRARY_DIR)?;
        let _ = std::fs::remove_file(defs::LIBADBROOT_PATH);
        let _ = std::fs::copy(libadbroot, defs::LIBADBROOT_PATH);
    }

    if let Some(data_path) = data_path {
        let backup_path = data_path.join(KSU_TEMP_BACKUP_DIR_NAME);
        if backup_path.is_dir() {
            for ent in backup_path.read_dir()? {
                let ent = ent?;
                if ent.file_type().is_ok_and(|v| v.is_file()) {
                    let name = ent.file_name().to_string_lossy().to_string();
                    let target = format!("{}{name}", defs::KSU_BACKUP_DIR);
                    if name.starts_with(defs::KSU_BACKUP_FILE_PREFIX)
                        && std::fs::rename(ent.path(), &target).is_err()
                    {
                        std::fs::copy(ent.path(), &target).with_context(|| {
                            format!("failed to move {} -> {target}", ent.path().display())
                        })?;
                        log::info!("move boot backup {name}");
                    }
                }
            }
            std::fs::remove_dir_all(&backup_path)?;
        }
    }

    Ok(())
}

/// 从设备上移除 KernelSU：模块、私有状态、启动镜像备份，最后卸载 Manager
/// APK。成功后重启。
pub fn uninstall(package_name: &str) -> Result<()> {
    if Path::new(defs::MODULE_DIR).exists() {
        println!("- Uninstall modules..");
        module::uninstall_all_modules()?;
        module::prune_modules()?;
    }
    println!("- Removing directories..");
    std::fs::remove_dir_all(defs::WORKING_DIR).ok();
    std::fs::remove_file(defs::DAEMON_PATH).ok();
    std::fs::remove_dir_all(defs::MODULE_DIR).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_VOLD).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_WATCHDOG).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_DEFAULT).ok();
    println!("- Restore boot image..");
    boot_patch::restore(BootRestoreArgs {
        boot: None,
        flash: true,
        out: None,
        out_name: None,
    })?;
    println!("- Uninstall KernelSU manager..");
    Command::new("pm")
        .args(["uninstall", package_name])
        .spawn()?;
    println!("- Rebooting in 5 seconds..");
    std::thread::sleep(std::time::Duration::from_secs(5));
    Command::new("reboot").spawn()?;
    Ok(())
}

/// 把标准输入输出重定向到 /dev/null，标准的守护进程清理步骤。
pub fn reset_std() -> Result<()> {
    let null_fd = open("/dev/null", OFlags::RDWR, Mode::empty())?;
    dup2_stdin(&null_fd)?;
    dup2_stdout(&null_fd)?;
    dup2_stderr(&null_fd)?;
    Ok(())
}

pub fn daemonize_with<F: FnOnce() -> Result<()>>(use_init_pgrp: bool, configure: F) -> Result<()> {
    if !create_daemon_impl(use_init_pgrp, configure)? {
        unsafe { libc::_exit(0) }
    }
    Ok(())
}

pub fn daemonize(use_init_pgrp: bool) -> Result<()> {
    daemonize_with(use_init_pgrp, || Ok(()))
}

pub fn create_daemon(use_init_pgrp: bool) -> Result<bool> {
    create_daemon_with(use_init_pgrp, || Ok(()))
}

pub fn create_daemon_with<F: FnOnce() -> Result<()>>(
    use_init_pgrp: bool,
    configure: F,
) -> Result<bool> {
    create_daemon_impl(use_init_pgrp, configure)
}

/// 双 fork 守护进程化。
///
/// 原父进程中返回 `false`（此时已回收第一个子进程），最终守护进程中返回
/// `true`。`configure` 在两次 fork 之间执行，以便在完全脱离前加入目标
/// cgroup/会话。
fn create_daemon_impl<F: FnOnce() -> Result<()>>(
    use_init_pgrp: bool,
    configure: F,
) -> Result<bool> {
    unsafe {
        let pid = libc::fork();
        if pid < 0 {
            bail!("fork error {}", std::io::Error::last_os_error());
        } else if pid > 0 {
            let mut status: i32 = -1;
            loop {
                if libc::waitpid(pid, &raw mut status, 0) < 0 {
                    if *libc::__errno() != libc::EINTR {
                        libc::_exit(1);
                    }
                } else {
                    break;
                }
            }
            if !libc::WIFEXITED(status) || libc::WEXITSTATUS(status) != 0 {
                bail!("child exited with unexpected status {status}")
            }
            return Ok(false);
        }
    }

    let do_configure = || -> Result<()> {
        detach_process_group(use_init_pgrp);
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

    if let Err(e) = do_configure() {
        log::error!("failed to configure daemon: {e:?}");
        unsafe {
            libc::_exit(1);
        }
    }

    Ok(true)
}

/// 优先使用内核的 `set_init_pgrp` supercall（加入 init 进程组，使守护进程
/// 不随启动者退出）；失败时回退到普通 setpgid。
pub fn detach_process_group(use_init_pgrp: bool) {
    if use_init_pgrp {
        if let Err(e) = ksucalls::set_init_pgrp() {
            log::error!("failed to switch to init group: {e:?}");
        } else {
            return;
        }
    }
    if let Err(e2) = setpgid(None, None) {
        log::error!("failed to set process group: {e2:?}");
    }
}
