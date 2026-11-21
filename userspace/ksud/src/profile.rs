//! 每个应用（profile）的 root 配置数据：SELinux 规则片段和可复用模板。
//!
//! Manager 在 `/data/adb/ksu/profile/` 下为每个包保存规则片段，并保存命名模板；ksud 是这层文件
//! 访问的实现（`ksud profile` 子命令），同时会在 post-fs-data/late-load 阶段重新应用所有片段，
//! 使 profile 策略在重启后仍然有效。存储层刻意保持简单：只校验名字，不解释内容。

use crate::utils::ensure_dir_exists;
use crate::{defs, sepolicy};
use anyhow::{Context, Result, ensure};
use std::path::{Path, PathBuf};

/// 解析出 `dir/name`，并拒绝可能逃出 `dir` 或破坏单行输出的名字。
///
/// 对 sepolicy 片段来说 `name` 是包名，对模板来说是自由格式的 id。拒绝换行是因为
/// `list_templates` 每行打印一个条目；拒绝 NUL 是因为这些名字最终会拼进路径。
fn profile_path(dir: &str, name: &str) -> Result<PathBuf> {
    // 让名字保持在单个目录内，并在列出时只占一行
    ensure!(
        !name.is_empty()
            && name != "."
            && name != ".."
            && !name.chars().any(|c| matches!(c, '/' | '\0' | '\n' | '\r')),
        "Invalid profile name: {name:?}"
    );
    Ok(Path::new(dir).join(name))
}

/// 保存某个包的 sepolicy 片段并立即应用。
///
/// 立即应用意味着新保存的规则无需重启即可生效；文件会保留下来，供 `apply_sepolies` 在下次开机时
/// 重新应用。
pub fn set_sepolicy(pkg: &str, policy: String) -> Result<()> {
    let policy_file = profile_path(defs::PROFILE_SELINUX_DIR, pkg)?;
    ensure_dir_exists(defs::PROFILE_SELINUX_DIR)?;
    std::fs::write(&policy_file, policy)?;
    sepolicy::apply_file(&policy_file)?;
    Ok(())
}

/// 把某个包保存的 sepolicy 片段打印到 stdout，供 Manager 读取。
pub fn get_sepolicy(pkg: &str) -> Result<()> {
    let policy_file = profile_path(defs::PROFILE_SELINUX_DIR, pkg)?;
    let policy = std::fs::read_to_string(policy_file)?;
    println!("{policy}");
    Ok(())
}

/// 把命名模板保存到 profile 的模板目录。
// ksud 不校验模板内容的正确性，只负责原样保存
pub fn set_template(id: &str, template: String) -> Result<()> {
    let template_file = profile_path(defs::PROFILE_TEMPLATE_DIR, id)?;
    ensure_dir_exists(defs::PROFILE_TEMPLATE_DIR)?;
    std::fs::write(template_file, template)?;
    Ok(())
}

/// 把保存的模板打印到 stdout。
pub fn get_template(id: &str) -> Result<()> {
    let template_file = profile_path(defs::PROFILE_TEMPLATE_DIR, id)?;
    let template = std::fs::read_to_string(template_file)?;
    println!("{template}");
    Ok(())
}

/// 删除模板；文件不存在时把错误返回给调用者。
pub fn delete_template(id: &str) -> Result<()> {
    let template_file = profile_path(defs::PROFILE_TEMPLATE_DIR, id)?;
    std::fs::remove_file(template_file)?;
    Ok(())
}

/// 每行打印一个模板 id；目录不存在时等同于空列表。
pub fn list_templates() -> Result<()> {
    let templates = std::fs::read_dir(defs::PROFILE_TEMPLATE_DIR);
    let Ok(templates) = templates else {
        return Ok(());
    };
    for template in templates {
        let template = template?;
        let template = template.file_name();
        if let Some(template) = template.to_str() {
            println!("{template}");
        }
    }
    Ok(())
}

/// 重新应用所有已保存的、按包存放的 sepolicy 片段。
///
/// 在模块的 `sepolicy.rule` 之后运行（见 `init_event`/`late_load`）。单个文件失败只记录日志并跳过，
/// 这样它不会阻塞其余 profile。
pub fn apply_sepolies() -> Result<()> {
    let path = Path::new(defs::PROFILE_SELINUX_DIR);
    if !path.exists() {
        log::info!("profile sepolicy dir not exists.");
        return Ok(());
    }

    let sepolicies =
        std::fs::read_dir(path).with_context(|| "profile sepolicy dir open failed.".to_string())?;
    for sepolicy in sepolicies {
        let Ok(sepolicy) = sepolicy else {
            log::info!("profile sepolicy dir read failed.");
            continue;
        };
        let sepolicy = sepolicy.path();
        if sepolicy::apply_file(&sepolicy).is_ok() {
            log::info!("profile sepolicy applied: {}", sepolicy.display());
        } else {
            log::info!("profile sepolicy apply failed: {}", sepolicy.display());
        }
    }
    Ok(())
}
