//! 生成下次开机时内核读取的 `modules.rc` 和 preinit 状态。

use anyhow::{Context, Result};
use is_executable::is_executable;
use log::debug;

use std::collections::BTreeMap;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::defs;
use crate::restorecon::lsetfilecon;
use crate::{ksucalls, preinit};

// 低于该 UAPI 版本的内核只会在 /metadata/watchdog 和 /metadata 下查找 modules.rc。
const PREINIT_VOLD_UAPI_VERSION: u32 = 6;

/// 选择当前内核会读取的 preinit 目录。
///
/// 较新的内核优先 `/metadata/vold/ksu`（对大多数扫描器隐藏）；旧内核只查找
/// `/metadata/watchdog` 或 `/metadata`。
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
fn preinit_con(dir: &str) -> &'static str {
    if dir == defs::PREINIT_DIR_VOLD {
        defs::VOLD_METADATA_FILE_CON
    } else {
        defs::METADATA_FILE_CON
    }
}

/// 把 `dir` 下所有 `.rc` 文件按文件名排序后追加到 `out`。
///
/// `module_id` 用于区分模块的 initrc 目录（全部包含并标注来源）与公共的
/// `/data/adb/initrc.d`（后者用可执行位作为启用开关）。
fn collect_rc_files<P: AsRef<Path>>(
    dir: P,
    module_id: Option<&str>,
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
        if meta.is_file() && path.extension().and_then(|ext| ext.to_str()) == Some("rc") {
            if let Some(module_id) = module_id {
                writeln!(out, "# === from {module_id}:{} ===", path.display())?;
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
fn modules_for_next_boot() -> BTreeMap<String, PathBuf> {
    let mut modules: BTreeMap<String, Option<PathBuf>> = BTreeMap::new();
    for source_dir in [defs::MODULE_UPDATE_DIR, defs::MODULE_DIR] {
        let Ok(entries) = std::fs::read_dir(source_dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let module_path = entry.path();
            if !module_path.is_dir() {
                continue;
            }
            let Some(id) = module_path.file_name().and_then(|name| name.to_str()) else {
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

/// 通过拼接每个已启用模块的 *.rc 重建 `preinit_dir/modules.rc`。
/// 内核侧的读取钩子会在下次开机时把该文件拼接进 init.rc。
fn regenerate_preinit_rc(
    preinit_dir: &Path,
    modules: &BTreeMap<String, PathBuf>,
    con: &str,
) -> Result<()> {
    std::fs::create_dir_all(preinit_dir)
        .with_context(|| format!("Failed to create {}", preinit_dir.display()))?;

    let tmp_path = preinit_dir.join(defs::MODULES_RC_TMP_FILE);
    let out_path = preinit_dir.join(defs::MODULES_RC_FILE);

    {
        let mut tmp = File::create(&tmp_path)
            .with_context(|| format!("Failed to create {}", tmp_path.display()))?;

        // 先收集公共的 initrc
        collect_rc_files(Path::new(defs::ADB_DIR).join("initrc.d"), None, &mut tmp)?;
        // 再按字母顺序收集模块，并使用它们下次开机时的实际模块路径
        for (id, path) in modules {
            collect_rc_files(path.join(defs::MODULE_INIT_RC_DIR), Some(id), &mut tmp)?;
        }
        tmp.sync_all()?;
    }

    std::fs::rename(&tmp_path, &out_path).with_context(|| {
        format!(
            "Failed to rename {} -> {}",
            tmp_path.display(),
            out_path.display()
        )
    })?;

    // 设置 SELinux 标签，使内核在 init 上下文中调用 filp_open 时能读取它。
    if let Err(e) = lsetfilecon(&out_path, con) {
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
    let preinit_dir = preinit_ksu_dir();
    let con = preinit_con(preinit_dir);
    // 本次启动不会使用的候选目录；清理其中遗留的内容。
    let stale_dirs: Vec<&str> = [
        defs::PREINIT_DIR_VOLD,
        defs::PREINIT_DIR_WATCHDOG,
        defs::PREINIT_DIR_DEFAULT,
    ]
    .into_iter()
    .filter(|dir| *dir != preinit_dir)
    .collect();
    // 下次开机启用的模块集合：已剔除禁用/待删除项，modules_update 优先于 modules。
    let modules = modules_for_next_boot();

    let rc_result = regenerate_preinit_rc(Path::new(preinit_dir), &modules, con);
    // 清理其他候选路径下过期的 modules.rc。
    for stale in &stale_dirs {
        std::fs::remove_file(Path::new(stale).join(defs::MODULES_RC_FILE)).ok();
    }

    let stale_paths: Vec<&Path> = stale_dirs.iter().map(Path::new).collect();
    let preinit_result = preinit::sync(
        Path::new(preinit_dir),
        &stale_paths,
        &modules,
        con,
        ksucalls::is_preinit_supported(),
    );
    rc_result.and(preinit_result)
}
