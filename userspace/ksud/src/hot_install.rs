//! 热安装：让刚安装的模块无需重启即可生效。
//!
//! 模块在 customize.sh 中设置 `MODULE_HOT_INSTALL_REQUEST=true`（以及可选的
//! `MODULE_HOT_RUN_SCRIPT=<script>`）即可启用。installer.sh 会把该请求记录到暂存模块目录中的
//! 标记文件里；安装脚本成功执行后，ksud 把暂存目录移动到正在使用的目录上，应用 sepolicy.rule
//! 并运行请求的脚本。任何只在开机时发生的操作（挂载 system/、system.prop、post-fs-data.sh 等）
//! 仍然要等到下次开机。

use crate::{
    assets, defs,
    module::{ScriptWait, exec_script_with},
    sepolicy,
    utils::{ensure_dir_exists, is_safe_mode, switch_mnt_ns},
};

use anyhow::{Context, Result, bail};
use log::{info, warn};

use std::{
    fs::{canonicalize, remove_dir_all, remove_file, rename},
    os::unix::process::CommandExt,
    path::{Component, Path, PathBuf},
    process::{Command, Stdio},
    time::{Instant, SystemTime, UNIX_EPOCH},
};

/// installer.sh 写入标记文件的热安装请求。
///
/// 由 `take_request` 在安装脚本执行完后产生，交给 `apply` 消费。
pub struct Request {
    /// 来自 `MODULE_HOT_RUN_SCRIPT` 的脚本路径，相对于模块目录。
    run_script: Option<String>,
}

/// 消费暂存模块目录中留下的热安装标记（如果存在）。
///
/// 成功读取后尽力删除标记，避免该请求被后续安装重复执行。内容为空的标记仍然表示
/// 请求热安装，只是没有需要运行的脚本。
pub fn take_request(staged_dir: &Path) -> Option<Request> {
    let marker = staged_dir.join(defs::HOT_INSTALL_MARKER);
    let content = std::fs::read_to_string(&marker).ok()?;
    if let Err(e) = remove_file(&marker) {
        warn!("Failed to remove {}: {e}", marker.display());
    }
    let script = content.trim();
    Some(Request {
        run_script: (!script.is_empty()).then(|| script.to_owned()),
    })
}

/// 把运行脚本解析为相对于模块目录的路径。脚本不存在时会被跳过，
/// 与元模块实现的行为一致。
fn resolve_run_script(staged_dir: &Path, script: &str) -> Result<Option<PathBuf>> {
    // 安装脚本通常写成 "./script.sh"，这里去掉前导 "./"，让两种写法都能解析。
    let mut components = Path::new(script).components().peekable();
    while components.next_if_eq(&Component::CurDir).is_some() {}
    let relative: PathBuf = components.collect();
    // 拒绝空名称、绝对路径、上级目录等非普通路径分量，否则脚本可能逃出模块目录。
    if relative.as_os_str().is_empty()
        || !relative
            .components()
            .all(|c| matches!(c, Component::Normal(_)))
    {
        bail!("invalid MODULE_HOT_RUN_SCRIPT '{script}'");
    }

    let path = staged_dir.join(&relative);
    // 这里用 lstat：已存在的符号链接也要继续走下面的目录归属检查。
    if path.symlink_metadata().is_err() {
        println!("! MODULE_HOT_RUN_SCRIPT '{script}' not found, skipping it");
        return Ok(None);
    }
    // 符号链接只能指向模块内部的文件
    let resolved = canonicalize(&path)?;
    if !resolved.is_file() || !resolved.starts_with(canonicalize(staged_dir)?) {
        bail!("MODULE_HOT_RUN_SCRIPT '{script}' is not a file inside the module");
    }
    Ok(Some(relative))
}

/// 校验是否允许热安装，并解析请求的脚本路径。
///
/// 该检查发生在移动任何目录之前，被拒绝的请求会保留暂存目录，让重启后的常规更新流程接手。
fn validate_request(
    module_id: &str,
    is_metamodule: bool,
    request: &Request,
) -> Result<Option<PathBuf>> {
    // 元模块自己决定挂载后端，运行中中途替换不受支持。
    if is_metamodule {
        bail!("metamodules are always applied at boot");
    }
    if is_safe_mode() {
        bail!("modules are disabled in safe mode");
    }
    let Some(script) = &request.run_script else {
        return Ok(None);
    };
    resolve_run_script(&Path::new(defs::MODULE_UPDATE_DIR).join(module_id), script)
}

/// 把正在使用的模块目录移开。运行中的进程和已有的挂载可能仍然引用它，
/// 因此只在下一次开机时才删除。
fn move_to_trash(live_dir: &Path, module_id: &str) -> Result<PathBuf> {
    ensure_dir_exists(defs::HOT_INSTALL_TRASH_DIR)?;
    // 纳秒时间戳避免同一模块连续热安装时目标目录重名。
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    let target = Path::new(defs::HOT_INSTALL_TRASH_DIR).join(format!("{module_id}.{nanos}"));
    // 此处绝不能删除活动目录：失败时常规更新流程仍然需要它
    rename(live_dir, &target).with_context(|| format!("Failed to move {}", live_dir.display()))?;
    Ok(target)
}

/// 用暂存目录替换正在使用的模块目录，旧目录移入回收站。
///
/// 如果暂存目录无法移入，就恢复旧目录，保证待处理的更新仍能在下次开机时正常应用。
fn replace_live_module(module_id: &str) -> Result<()> {
    // 暂存目录：安装器刚写完、等待替换到正式位置的内容。
    let staged_dir = Path::new(defs::MODULE_UPDATE_DIR).join(module_id);
    // 活动目录：当前系统可能仍在引用的版本，替换时先移入回收站。
    let live_dir = Path::new(defs::MODULE_DIR).join(module_id);

    let stashed = if live_dir.exists() {
        Some(move_to_trash(&live_dir, module_id)?)
    } else {
        None
    };

    if let Err(e) = rename(&staged_dir, &live_dir) {
        // 恢复旧目录，让开机时的常规更新流程仍然可用
        if let Some(old) = stashed
            && let Err(e) = rename(&old, &live_dir)
        {
            warn!("Failed to restore {}: {e}", live_dir.display());
        }
        return Err(e).with_context(|| format!("Failed to move {}", staged_dir.display()));
    }
    // 暂存目录已经不在了，handle_updated_modules 不会再看到这次待更新。
    Ok(())
}

/// 尝试立即应用暂存模块。如果调用方应回退到开机时的常规更新流程，
/// 则返回 false。
pub fn apply(module_id: &str, is_metamodule: bool, request: &Request) -> bool {
    println!("- Hot install requested");

    let run_script = match validate_request(module_id, is_metamodule, request)
        .and_then(|script| replace_live_module(module_id).map(|()| script))
    {
        Ok(script) => script,
        Err(e) => {
            println!("- Hot install skipped: {e:#}");
            println!("- Reboot to apply the module");
            return false;
        }
    };
    info!("Module {module_id} hot installed");

    let live_dir = Path::new(defs::MODULE_DIR).join(module_id);

    // 规则是累加的，所以现在加载新规则是安全的；过期规则会在重启后消失
    let rule_file = live_dir.join("sepolicy.rule");
    if rule_file.exists() && sepolicy::apply_file(&rule_file).is_err() {
        println!("! Failed to apply sepolicy.rule");
    }

    if !live_dir.join("skip_mount").exists()
        && ["system", "vendor", "product", "system_ext", "odm"]
            .iter()
            .any(|partition| live_dir.join(partition).is_dir())
    {
        // 与 installer.sh 使用相同的分区集合；真正的叠加挂载仍只在开机时构建。
        println!("- Note: partition files are mounted on the next boot");
    }

    // preinit 动作由开机流程生成并执行，热安装不会处理它们。
    if live_dir.join(defs::MODULE_PREINIT_DIR).is_dir() {
        println!("- Note: preinit actions run from the next boot");
    }

    if let Some(script) = run_script {
        // 安装器通常运行在 Manager 的挂载命名空间中；开机脚本运行在 init 的挂载命名空间中，
        // 因此脚本建立的挂载也必须落在那里
        if let Err(e) = switch_mnt_ns(1) {
            warn!("Failed to switch to init mount namespace: {e}");
        }
        println!("- Running {}", script.display());
        let deadline = Instant::now() + defs::HOT_INSTALL_SCRIPT_TIMEOUT;
        let result = exec_script_with(
            live_dir.join(script),
            ScriptWait::Until(deadline),
            |command| {
                // 输出会写入安装器的管道，Manager 在安装结束后会关闭它；
                // 忽略 SIGPIPE，让这里启动的后台进程继续运行
                command.stdin(Stdio::null());
                // pre_exec 在 fork 之后、exec 之前的子进程里运行，只能调用异步信号安全的函数；
                // libc::signal 满足要求。
                unsafe {
                    command.pre_exec(|| {
                        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
                        Ok(())
                    });
                }
            },
        );
        if let Err(e) = result {
            println!("! {e:#}");
        }
    }

    true
}

/// 删除上次开机的热安装所替换下来的模块目录。回收站会先被重命名，
/// 然后在后台删除，这样一个很大的回收站就不会拖慢开机。
pub fn clean_trash() {
    let trash = Path::new(defs::HOT_INSTALL_TRASH_DIR);
    if !trash.exists() {
        return;
    }
    let old = Path::new(defs::WORKING_DIR).join("hot_install_trash.old");
    // 上次的后台删除未完成时会留下残留
    if old.exists()
        && let Err(e) = remove_dir_all(&old)
    {
        warn!("Failed to clean {}: {e}", old.display());
    }
    // 先改名可以立刻腾出标准回收站路径，本次开机后续的热安装会写入全新的目录。
    if let Err(e) = rename(trash, &old) {
        warn!("Failed to move {}: {e}", trash.display());
        return;
    }
    let spawned = Command::new(assets::BUSYBOX_PATH)
        .args(["rm", "-rf"])
        .arg(&old)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    if let Err(e) = spawned {
        // 没能在后台删除，就直接同步删除，避免回收站跨开机不断累积。
        warn!("Failed to spawn trash removal: {e}");
        if let Err(e) = remove_dir_all(&old) {
            warn!("Failed to clean {}: {e}", old.display());
        }
    }
}
