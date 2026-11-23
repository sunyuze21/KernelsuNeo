//! 单个模块的生命周期操作：脚本与属性加载、修剪、启用/禁用、卸载和 action。

use anyhow::{Context, Result, ensure};
use log::{info, warn};

use std::fs::{File, remove_dir_all};
use std::path::Path;

use super::{
    ModuleType, ScriptWait, exec_script, foreach_active_module, foreach_module, read_module_prop,
    regenerate_preinit, validate_module_id,
};
use crate::utils::ensure_file_exists;
use crate::{defs, metamodule, module_config, resetprop, sepolicy};

/// 逐个活动模块加载 `sepolicy.rule`。
///
/// 单个模块的规则应用失败只告警，不影响其他模块继续加载。
pub fn load_sepolicy_rule() -> Result<()> {
    foreach_active_module(|module_dir| {
        let rule_file = module_dir.join("sepolicy.rule");
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

/// 逐个活动模块加载 `system.prop`，并通过 resetprop 写入属性。
pub fn load_system_prop() -> Result<()> {
    foreach_active_module(|module_dir| {
        let system_prop = module_dir.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load {} system.prop", module_dir.display());

        resetprop::load_system_prop_file(&system_prop)?;

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
    foreach_module(ModuleType::All, |module_dir| {
        if !module_dir.join(defs::REMOVE_FILE_NAME).exists() {
            return Ok(());
        }

        info!("remove module: {}", module_dir.display());

        // 先执行 metamodule 的 metauninstall.sh
        let module_id = module_dir
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");

        // 检查它是否为 metamodule
        let is_metamodule =
            read_module_prop(module_dir).is_ok_and(|props| metamodule::is_metamodule(&props));

        if is_metamodule {
            info!("Removing metamodule symlink");
            if let Err(e) = metamodule::remove_symlink() {
                warn!("Failed to remove metamodule symlink: {e}");
            }
        } else if let Err(e) = metamodule::exec_metauninstall_script(module_id) {
            warn!("Failed to exec metamodule uninstall for {module_id}: {e}");
        }

        // 再执行模块自身的 uninstall.sh
        let uninstall_script = module_dir.join("uninstall.sh");
        if uninstall_script.exists()
            && let Err(e) = exec_script(uninstall_script, ScriptWait::Forever)
        {
            warn!("Failed to exec uninstaller: {e}");
        }

        // 删除模块目录前先清理模块配置
        if let Err(e) = module_config::clear_module_configs(module_id) {
            warn!("Failed to clear configs for {module_id}: {e}");
        }

        // 最后删除模块目录
        if let Err(e) = remove_dir_all(module_dir) {
            warn!("Failed to remove {}: {e}", module_dir.display());
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

/// 撤销待删除标记，使模块在下次 post-fs-data 不会被清理。
///
/// # Errors
///
/// id 格式非法或模块目录不存在时返回错误。
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

/// 执行模块的 `action.sh`。
///
/// 先检查 UAPI 版本兼容性，然后同步等待脚本结束。
pub fn run_action(id: &str) -> Result<()> {
    validate_module_id(id)?;
    crate::ksucalls::ensure_uapi_version_matched()?;

    let action_script_path = Path::new(defs::MODULE_DIR)
        .join(id)
        .join(defs::MODULE_ACTION_SH);
    exec_script(&action_script_path, ScriptWait::Forever)
}

/// 删除 `disable` 标记以启用模块，并刷新 preinit 内容。
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

/// 创建 `disable` 标记以禁用模块，并刷新 preinit 内容。
///
/// 与 `enable_module` 不同，这里不校验 id 格式；调用方应保证传入可信的模块 id。
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

/// 给所有模块打上禁用标记，并刷新 preinit 内容。
pub fn disable_all_modules() -> Result<()> {
    mark_all_modules(defs::DISABLE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    Ok(())
}

/// 给所有模块打上待删除标记，下次 post-fs-data 统一清理。
pub fn uninstall_all_modules() -> Result<()> {
    info!("Uninstalling all modules");
    mark_all_modules(defs::REMOVE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }
    Ok(())
}

/// 在模块目录下为每个模块创建指定标记文件。
///
/// 单个模块标记失败只告警，尽量让其余模块完成操作。
fn mark_all_modules(flag_name: &str) -> Result<()> {
    // 假定模块目录已经挂载
    let dir = std::fs::read_dir(defs::MODULE_DIR)?;
    for entry in dir.flatten() {
        let path = entry.path();
        let flag = path.join(flag_name);
        if let Err(e) = ensure_file_exists(flag) {
            warn!("Failed to mark module: {}: {e}", path.display());
        }
    }

    Ok(())
}
