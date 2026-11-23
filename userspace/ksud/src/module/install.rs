//! 模块 ZIP 安装，以及把暂存更新提交到正式模块目录。

use anyhow::{Context, Result, anyhow, bail, ensure};
use const_format::concatcp;
use java_properties::PropertiesIter;
use log::{info, warn};

use std::{
    collections::HashMap,
    fs::{File, Permissions, copy, remove_dir_all, rename, set_permissions},
    io::Cursor,
    os::unix::prelude::PermissionsExt,
    path::{Path, PathBuf},
    str::FromStr,
};
use zip_extensions::inflate::zip_extract::zip_extract_file_to_memory;

use crate::defs::{MODULE_DIR, MODULE_UPDATE_DIR, UPDATE_FILE_NAME};
use crate::module::{
    ModuleType, common_script_env, foreach_module, regenerate_preinit, validate_module_id,
};
use crate::module_integrity_check::{INTEGRITY_CHECK_PATH, module_integrity_check};
use crate::restorecon::{restore_syscon, setsyscon};
use crate::utils::{
    ensure_clean_dir, ensure_dir_exists, ensure_file_exists, get_zip_uncompressed_size, getprop,
};
use crate::{assets, defs, hot_install, ksucalls, metamodule, preinit};

// 可刷写 ZIP 的安装脚本被内嵌进 ksud，因此无需 Manager 也能安装模块
// （例如在 recovery 或 root shell 中）。
const INSTALLER_CONTENT: &str = include_str!("../installer.sh");
/// 在通用 installer.sh 末尾追加 `install_module` 调用和 `exit 0`，形成可直接
/// 交给 busybox sh 执行的完整安装脚本。
const INSTALL_MODULE_SCRIPT: &str = concatcp!(
    INSTALLER_CONTENT,
    "\n",
    "install_module",
    "\n",
    "exit 0",
    "\n"
);

/// 执行 ZIP 中的 installer.sh；`is_metamodule` 为真时允许 metamodule
/// 替换成自己的安装器。
fn exec_install_script(module_zip: &str, is_metamodule: bool, module_id: &str) -> Result<()> {
    let module_zip_path = std::fs::canonicalize(module_zip)
        .with_context(|| format!("realpath: {module_zip} failed"))?;

    // 从 metamodule 模块中获取安装脚本
    let install_script =
        metamodule::get_install_script(is_metamodule, INSTALLER_CONTENT, INSTALL_MODULE_SCRIPT)?;

    let result = std::process::Command::new(assets::BUSYBOX_PATH)
        .args(["sh", "-c", &install_script])
        .envs(common_script_env(Some(module_id)))
        .env("OUTFD", "1")
        .env("ZIPFILE", module_zip_path)
        .status()?;
    ensure!(result.success(), "Failed to install module script");
    Ok(())
}

// 安装模块前检查 Android 是否已完成启动
/// 要求 `sys.boot_completed == 1`，否则安装会与 post-fs-data 的模块提升流程竞争。
///
/// # Errors
///
/// Android 尚未完成启动时返回错误。
fn ensure_boot_completed() -> Result<()> {
    // 确保 getprop sys.boot_completed == 1
    if getprop("sys.boot_completed").as_deref() != Some("1") {
        bail!("Android is Booting!");
    }
    Ok(())
}

/// 把模块 ZIP 安装到 `modules_update/` 并执行其安装器。
///
/// 若安装器请求热安装，则跳过正常的“暂存到下次启动”流程，立即应用模块。
fn install_module_from_zip(module_zip: &str) -> Result<String> {
    // 在 Android 尚未启动完成时安装会与 post-fs-data 的模块提升流程竞争，
    // 因此要求 boot_completed。
    ensure_boot_completed()?;

    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    // 先检查工作目录是否可用
    ensure_dir_exists(defs::WORKING_DIR).with_context(|| "Failed to create working dir")?;
    ensure_dir_exists(defs::BINARY_DIR).with_context(|| "Failed to create bin dir")?;

    // 从 zip 中读取 module_id，失败则提前返回。
    let mut prop_bytes: Vec<u8> = Vec::new();
    let entry_path = PathBuf::from_str("module.prop")?;
    let zip_path = PathBuf::from_str(module_zip)?;
    let zip_path = zip_path.canonicalize()?;
    zip_extract_file_to_memory(&zip_path, &entry_path, &mut prop_bytes)?;

    let mut module_prop = HashMap::new();
    PropertiesIter::new_with_encoding(Cursor::new(prop_bytes), encoding_rs::UTF_8).read_into(
        |key, value| {
            module_prop.insert(key, value);
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
    if !is_metamodule && let Err(metamodule_disabled) = metamodule::check_install_safety() {
        println!("! Installation blocked");
        println!("! A metamodule with a custom installer is active");
        if metamodule_disabled {
            println!("! Current state: disabled");
            println!("! Re-enable or uninstall it, then reboot");
        } else {
            println!("! Current state: pending changes");
            println!("! Reboot to apply changes first");
        }
        bail!("Cannot install a regular module while a metamodule is active");
    }

    // 所有模块（包括 metamodule）都安装到 MODULE_UPDATE_DIR
    let staged_dir = Path::new(MODULE_UPDATE_DIR).join(module_id);

    if is_metamodule {
        info!("Installing metamodule: {module_id}");

        // 检查是否已安装 metamodule
        if metamodule::has_metamodule()
            && let Some(existing_path) = metamodule::get_metamodule_path()
        {
            let existing_id = crate::module::read_module_prop(&existing_path)
                .ok()
                .and_then(|props| props.get("id").cloned())
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

    let uncompressed_size = get_zip_uncompressed_size(module_zip)?;
    info!(
        "zip uncompressed size: {}",
        humansize::format_size(uncompressed_size, humansize::DECIMAL)
    );
    println!(
        "- Module size: {}",
        humansize::format_size(uncompressed_size, humansize::DECIMAL)
    );

    // 确保模块目录存在，并设置 SELinux context
    ensure_dir_exists(MODULE_UPDATE_DIR)?;
    setsyscon(MODULE_UPDATE_DIR)?;

    // 准备目标目录
    println!("- Installing to {}", staged_dir.display());
    ensure_clean_dir(&staged_dir)?;
    info!("target dir: {}", staged_dir.display());

    // 将 zip 解压到目标目录
    println!("- Extracting module files");
    let zip_file = File::open(module_zip)?;
    let mut archive = zip::ZipArchive::new(zip_file)?;
    archive.extract(&staged_dir)?;
    // 只有 installer.sh 可以请求热安装，zip 中自带的文件不行
    std::fs::remove_file(staged_dir.join(defs::HOT_INSTALL_MARKER)).ok();

    // 为 $MOD/system 设置权限和 selinux context
    let system_dir = staged_dir.join("system");
    if system_dir.exists() {
        set_permissions(&system_dir, Permissions::from_mode(0o755))?;
        restore_syscon(&system_dir)?;
    }

    // 执行安装脚本
    println!("- Running module installer");
    exec_install_script(module_zip, is_metamodule, module_id)?;

    if let Some(request) = hot_install::take_request(&staged_dir)
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
        staged_dir.join("module.prop"),
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
pub fn install_module(module_zip: &str) -> Result<()> {
    ksucalls::ensure_uapi_version_matched()?;

    println!(include_str!("../banner"));

    // 模块签名校验需要显式开启；未开启时保持原来的安装流程。
    if Path::new(INTEGRITY_CHECK_PATH).exists() {
        println!("- Verifying module signature");
        module_integrity_check(module_zip)
            .map_err(|e| anyhow!("Module signature verification failed: {e}"))?;
        println!("- Module signature verified");
    }
    let module_id = install_module_from_zip(module_zip)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    if let Some((state, message)) = preinit::status_of(&module_id)
        && state != "ok"
    {
        println!("! preinit will not take effect: {state} {message}");
    }
    Ok(())
}

/// 把 `modules_update/` 中暂存的模块提升到 `modules/`。
///
/// 覆盖已有模块时会保留其禁用/删除标记，避免更新悄悄重新启用用户关掉的模块。
pub fn handle_updated_modules() -> Result<()> {
    let modules_root = Path::new(MODULE_DIR);
    foreach_module(ModuleType::Updated, |staged_module| {
        if !staged_module.is_dir() {
            return Ok(());
        }

        if let Some(name) = staged_module.file_name() {
            // 正式模块目录：暂存更新提交后的最终位置。
            let module_dir = modules_root.join(name);
            let mut was_disabled = false;
            let mut was_pending_remove = false;
            if module_dir.exists() {
                // 若旧模块已禁用，新模块也必须保持禁用
                was_disabled = module_dir.join(defs::DISABLE_FILE_NAME).exists();
                was_pending_remove = module_dir.join(defs::REMOVE_FILE_NAME).exists();
                remove_dir_all(&module_dir)?;
            }
            rename(staged_module, &module_dir)?;
            if was_pending_remove {
                let path = module_dir.join(defs::REMOVE_FILE_NAME);
                if let Err(e) = ensure_file_exists(&path) {
                    warn!("Failed to create {}: {e}", path.display());
                }
            } else if was_disabled {
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
