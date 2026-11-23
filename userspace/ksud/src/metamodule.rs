//! 元模块管理
//!
//! 本模块负责所有与元模块相关的功能。
//! 元模块是一类特殊模块，用于管理普通模块的挂载方式，
//! 并在模块安装/卸载时提供钩子。
//! 同一时间只能有一个元模块生效；ksud 通过 /data/adb/metamodule 符号链接暴露它。

use anyhow::{Context, Result, ensure};
use log::{info, warn};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    process::Command,
};

use crate::module::{ModuleType::All, ScriptWait};
use crate::{assets, defs};

/// 判断给定的模块属性是否表明该模块是元模块
pub fn is_metamodule(props: &HashMap<String, String>) -> bool {
    // 各安装脚本的取值并不统一；无论大小写，都接受 "1" 和 "true"。
    props.get("metamodule").is_some_and(|s| {
        let trimmed = s.trim();
        trimmed == "1" || trimmed.eq_ignore_ascii_case("true")
    })
}

/// 获取元模块路径（如果存在）
/// 元模块存放在 /data/adb/modules/{id}，并通过 /data/adb/metamodule 处的符号链接指向它
pub fn get_metamodule_path() -> Option<PathBuf> {
    let path = Path::new(defs::METAMODULE_DIR);

    // 检查符号链接是否存在，并解析其目标
    if path.is_symlink()
        && let Ok(target) = std::fs::read_link(path)
    {
        // 目标为相对路径时也需要解析
        // 相对目标以 /data/adb（即链接所在目录）为基准解析。
        let resolved = if target.is_absolute() {
            target
        } else {
            path.parent()?.join(target)
        };

        if resolved.exists() && resolved.is_dir() {
            return Some(resolved);
        }
        warn!(
            "Metamodule symlink points to non-existent path: {}",
            resolved.display()
        );
    }

    // 兜底方案：在模块目录中搜索 metamodule=1
    // 用 All 而不是 Active，这样被禁用的元模块也能找到，由调用方检查禁用标记。
    // 若多个模块都声明该角色，最后一个会生效。
    let mut result = None;
    let _ = crate::module::foreach_module(All, |module_path| {
        if let Ok(props) = crate::module::read_module_prop(module_path)
            && is_metamodule(&props)
        {
            info!(
                "Found metamodule in modules directory: {}",
                module_path.display()
            );
            result = Some(module_path.to_path_buf());
        }
        Ok(())
    });

    result
}

/// 获取元模块 id
pub fn get_metamodule_id() -> Option<String> {
    // 模块 id 就是 /data/adb/modules 下的目录名。
    get_metamodule_path().and_then(|path| {
        path.file_name()
            .and_then(|os_str| os_str.to_str())
            .map(ToString::to_string)
    })
}

/// 检查元模块是否存在
pub fn has_metamodule() -> bool {
    get_metamodule_path().is_some()
}

/// 检查当前是否可以安全地安装普通模块
/// 可以安装时返回 Ok(())，被阻止时返回 Err(is_disabled)
/// - Err(true) 表示元模块已被禁用
/// - Err(false) 表示元模块处于其他不稳定状态
pub fn check_install_safety() -> Result<(), bool> {
    // 没有元模块 → 安全
    let Some(metamodule_path) = get_metamodule_path() else {
        return Ok(());
    };

    // 没有 metainstall.sh → 安全（使用默认安装器）
    // 暂存的更新目录里可能已有最新脚本，因此两个位置都要检查
    let has_metainstall = metamodule_path
        .join(defs::METAMODULE_METAINSTALL_SCRIPT)
        .exists()
        || metamodule_path.file_name().is_some_and(|module_id| {
            Path::new(defs::MODULE_UPDATE_DIR)
                .join(module_id)
                .join(defs::METAMODULE_METAINSTALL_SCRIPT)
                .exists()
        });
    if !has_metainstall {
        return Ok(());
    }

    // 检查标记文件
    let has_update = metamodule_path.join(defs::UPDATE_FILE_NAME).exists();
    let has_remove = metamodule_path.join(defs::REMOVE_FILE_NAME).exists();
    let has_disable = metamodule_path.join(defs::DISABLE_FILE_NAME).exists();

    // 稳定状态（没有任何标记）→ 安全
    if !has_update && !has_remove && !has_disable {
        return Ok(());
    }

    // 仅禁用时返回 true，其他不稳定状态返回 false
    // 只有“单纯被禁用”是用户重新启用即可恢复的状态；更新或卸载正在进行时再安装别的模块
    // 会丢失工作。
    Err(has_disable && !has_update && !has_remove)
}

/// 创建或更新元模块符号链接
/// 指向 /data/adb/metamodule -> /data/adb/modules/{module_id}
pub fn ensure_symlink(module_path: &Path) -> Result<()> {
    // METAMODULE_DIR 末尾可能带斜杠，需要先去掉
    let symlink_path = Path::new(defs::METAMODULE_DIR.trim_end_matches('/'));

    info!(
        "Creating metamodule symlink: {} -> {}",
        symlink_path.display(),
        module_path.display()
    );

    // 已存在符号链接时先删除
    if symlink_path.exists() || symlink_path.is_symlink() {
        info!("Removing old metamodule symlink/path");
        if symlink_path.is_symlink() {
            std::fs::remove_file(symlink_path).with_context(|| "Failed to remove old symlink")?;
        } else {
            // 也可能是目录，一并删除
            // 旧布局或残留安装可能在这里留下真实目录。
            std::fs::remove_dir_all(symlink_path)
                .with_context(|| "Failed to remove old directory")?;
        }
    }

    // 创建符号链接
    std::os::unix::fs::symlink(module_path, symlink_path)
        .with_context(|| format!("Failed to create symlink to {}", module_path.display()))?;

    info!("Metamodule symlink created successfully");
    Ok(())
}

/// 删除元模块符号链接
pub fn remove_symlink() -> Result<()> {
    let symlink_path = Path::new(defs::METAMODULE_DIR.trim_end_matches('/'));

    // 只处理符号链接；如果这里哪天变成了目录，remove_dir_all 会造成灾难性后果。
    if symlink_path.is_symlink() {
        std::fs::remove_file(symlink_path)
            .with_context(|| "Failed to remove metamodule symlink")?;
        info!("Metamodule symlink removed");
    }

    Ok(())
}

/// 获取安装脚本内容；元模块提供 metainstall.sh 时优先使用它
/// 返回将要执行的脚本内容
pub fn get_install_script(
    is_metamodule: bool,
    installer_content: &str,
    install_module_script: &str,
) -> Result<String> {
    // 检查是否存在带 metainstall.sh 的元模块
    // 该逻辑仅对普通模块生效（安装元模块自身时不适用）
    let install_script = if is_metamodule {
        info!("Installing metamodule, using default installer");
        install_module_script.to_string()
    } else if let Some(metamodule_path) = get_metamodule_path() {
        if metamodule_path.join(defs::DISABLE_FILE_NAME).exists() {
            info!("Metamodule is disabled, using default installer");
            install_module_script.to_string()
        } else {
            let metainstall_path = metamodule_path.join(defs::METAMODULE_METAINSTALL_SCRIPT);

            if metainstall_path.exists() {
                info!("Using metainstall.sh from metamodule");
                let metamodule_content = std::fs::read_to_string(&metainstall_path)
                    .with_context(|| "Failed to read metamodule metainstall.sh")?;
                // 先拼上 installer.sh，metainstall.sh 才能调用其中的辅助函数（ui_print、
                // install_module 等）。末尾的 exit 0 会掩盖 metainstall.sh 最后一条命令的失败，
                // 因为它的退出状态只作为提示使用。
                format!("{installer_content}\n{metamodule_content}\nexit 0\n")
            } else {
                info!("Metamodule exists but has no metainstall.sh, using default installer");
                install_module_script.to_string()
            }
        }
    } else {
        info!("No metamodule found, using default installer");
        install_module_script.to_string()
    };

    Ok(install_script)
}

/// 检查元模块脚本是否存在且可执行
/// 元模块不存在、被禁用或脚本缺失时返回 None
/// 脚本可用时返回 Some(script_path)
fn check_metamodule_script(script_name: &str) -> Option<PathBuf> {
    // 检查元模块是否存在
    let metamodule_path = get_metamodule_path()?;

    // 检查元模块是否被禁用
    if metamodule_path.join(defs::DISABLE_FILE_NAME).exists() {
        info!("Metamodule is disabled, skipping {script_name}");
        return None;
    }

    // 检查脚本是否存在
    let script_path = metamodule_path.join(script_name);
    if !script_path.exists() {
        return None;
    }

    Some(script_path)
}

/// 针对指定模块执行元模块的 metauninstall.sh
pub fn exec_metauninstall_script(module_id: &str) -> Result<()> {
    let Some(metauninstall_path) = check_metamodule_script(defs::METAMODULE_METAUNINSTALL_SCRIPT)
    else {
        return Ok(());
    };

    info!("Executing metamodule metauninstall.sh for module: {module_id}");

    // unwrap 是安全的：该路径由元模块目录拼接脚本名得到。
    // 在元模块目录中运行，脚本里的相对路径才能按预期解析。
    let result = Command::new(assets::BUSYBOX_PATH)
        .args(["sh", metauninstall_path.to_str().unwrap()])
        .current_dir(metauninstall_path.parent().unwrap())
        .envs(crate::module::common_script_env(
            get_metamodule_id().as_deref(),
        ))
        // MODULE_ID 指向被卸载的模块，而不是元模块自身。
        .env("MODULE_ID", module_id)
        .status()?;

    ensure!(
        result.success(),
        "Metamodule metauninstall.sh failed for module {module_id}: {result:?}"
    );

    info!("Metamodule metauninstall.sh executed successfully for {module_id}");
    Ok(())
}

/// 执行元模块的挂载脚本
///
/// `metamount.sh` 负责构建让模块文件可见的叠加层；`module_dir` 会作为
/// MODULE_DIR 传入，脚本据此知道要挂载的模块位于何处。
pub fn exec_mount_script(module_dir: &str) -> Result<()> {
    let Some(mount_script) = check_metamodule_script(defs::METAMODULE_MOUNT_SCRIPT) else {
        return Ok(());
    };

    info!("Executing mount script for metamodule");

    let result = Command::new(assets::BUSYBOX_PATH)
        .args(["sh", mount_script.to_str().unwrap()])
        .envs(crate::module::common_script_env(
            get_metamodule_id().as_deref(),
        ))
        .env("MODULE_DIR", module_dir)
        .status()?;

    ensure!(
        result.success(),
        "Metamodule mount script failed with status: {result:?}"
    );

    info!("Metamodule mount script executed successfully");
    Ok(())
}

/// 执行元模块在指定阶段的脚本
///
/// 阶段名与开机阶段一致（post-fs-data、late-load、post-mount、service 等）。
/// init_event 会在常规模块脚本之前调用它，让元模块先准备好后续脚本依赖的挂载。
pub fn exec_stage_script(stage: &str, wait: ScriptWait) -> Result<()> {
    let Some(script_path) = check_metamodule_script(&format!("{stage}.sh")) else {
        return Ok(());
    };

    info!("Executing metamodule {stage}.sh");
    crate::module::exec_script(&script_path, wait)?;
    info!("Metamodule {stage}.sh executed successfully");
    Ok(())
}
