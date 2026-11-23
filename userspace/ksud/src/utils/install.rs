//! ksud 自身的安装与卸载流程。

use anyhow::{Context, Result};
use std::{
    path::{Path, PathBuf},
    process::Command,
};

use super::ensure_dir_exists;
use crate::boot_patch::BootRestoreArgs;
use crate::{assets, boot_patch, defs, module, restorecon};

/// 在 `/data/adb/ksu/bin` 中放置指向 ksud 的软链接，方便模块和 `su` 直接调用。
fn link_ksud_to_bin() -> Result<()> {
    let daemon_path = Path::new(defs::DAEMON_PATH);
    let daemon_link_path = Path::new(defs::DAEMON_LINK_PATH);
    if daemon_path.exists() && !daemon_link_path.exists() {
        std::os::unix::fs::symlink(daemon_path, daemon_link_path)?;
    }
    Ok(())
}

/// 把 ksud 及其内嵌资源安装到 /data/adb。
///
/// `adb_root_lib` 和 `installer_data_dir` 由 APK 安装器传入：前者是解压出的
/// adb root 辅助库，后者是安装器暂存启动镜像备份的位置。
pub fn install(adb_root_lib: Option<PathBuf>, installer_data_dir: Option<PathBuf>) -> Result<()> {
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

    if let Some(adb_root_lib) = adb_root_lib {
        ensure_dir_exists(defs::LIBRARY_DIR)?;
        let _ = std::fs::remove_file(defs::LIBADBROOT_PATH);
        let _ = std::fs::copy(adb_root_lib, defs::LIBADBROOT_PATH);
    }

    if let Some(installer_data_dir) = installer_data_dir {
        // 安装器（APK）把 boot 备份暂存在自己的数据目录，这里搬进 KernelSU 的
        // 私有备份目录；先尝试 rename，跨文件系统失败时回退到 copy。
        let staged_backup_dir = installer_data_dir.join(defs::KSU_TEMP_BACKUP_DIR_NAME);
        if staged_backup_dir.is_dir() {
            for entry in staged_backup_dir.read_dir()? {
                let entry = entry?;
                if entry.file_type().is_ok_and(|file_type| file_type.is_file()) {
                    let name = entry.file_name().to_string_lossy().to_string();
                    let target = format!("{}{name}", defs::KSU_BACKUP_DIR);
                    if name.starts_with(defs::KSU_BACKUP_FILE_PREFIX)
                        && std::fs::rename(entry.path(), &target).is_err()
                    {
                        std::fs::copy(entry.path(), &target).with_context(|| {
                            format!("failed to move {} -> {target}", entry.path().display())
                        })?;
                        log::info!("move boot backup {name}");
                    }
                }
            }
            std::fs::remove_dir_all(&staged_backup_dir)?;
        }
    }

    Ok(())
}

/// 从设备上移除 KernelSU：模块、私有状态、启动镜像备份，最后卸载 Manager
/// APK。成功后重启。
pub fn uninstall(package_name: &str) -> Result<()> {
    if Path::new(defs::MODULE_DIR).exists() {
        println!("- Uninstalling modules");
        module::uninstall_all_modules()?;
        module::prune_modules()?;
    }
    println!("- Removing directories");
    std::fs::remove_dir_all(defs::WORKING_DIR).ok();
    std::fs::remove_file(defs::DAEMON_PATH).ok();
    std::fs::remove_dir_all(defs::MODULE_DIR).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_VOLD).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_WATCHDOG).ok();
    std::fs::remove_dir_all(defs::PREINIT_DIR_DEFAULT).ok();
    println!("- Restoring boot image");
    boot_patch::restore(BootRestoreArgs {
        boot: None,
        flash: true,
        out: None,
        out_name: None,
    })?;
    println!("- Uninstalling manager package");
    Command::new("pm")
        .args(["uninstall", package_name])
        .spawn()?;
    println!("- Rebooting in 5 seconds");
    std::thread::sleep(std::time::Duration::from_secs(5));
    Command::new("reboot").spawn()?;
    Ok(())
}
