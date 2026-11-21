//! 直接基于 `security.selinux` xattr 实现的 SELinux 标签工具。
//!
//! ksud 会在常规 `restorecon` 工具可用之前、或在其覆盖范围之外创建文件（daemon 二进制、模块目录、
//! preinit 副本），所以需要自己设置标签。`l*` 系列只作用于路径本身、不跟随最后一级符号链接，
//! 这对模块目录很重要。

use crate::defs;
use anyhow::Result;
use jwalk::{Parallelism::Serial, WalkDir};
use std::path::Path;

use anyhow::{Context, Ok};
use extattr::{Flags as XattrFlags, lsetxattr};

/// 普通系统文件的 context，用于丢失标签的模块文件。
pub const SYSTEM_CON: &str = "u:object_r:system_file:s0";
/// KernelSU 自有文件的 context，主要指 ksud daemon 二进制。
pub const KSU_CON: &str = "u:object_r:ksu_file:s0";
/// 未打标签的 inode 会报告这个值，恢复流程把它视为“没有有效标签”。
pub const UNLABEL_CON: &str = "u:object_r:unlabeled:s0";

const SELINUX_XATTR: &str = "security.selinux";

/// 设置 `path` 的 SELinux context，不跟随符号链接。
pub fn lsetfilecon<P: AsRef<Path>>(path: P, con: &str) -> Result<()> {
    lsetxattr(&path, SELINUX_XATTR, con, XattrFlags::empty()).with_context(|| {
        format!(
            "Failed to change SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    Ok(())
}

/// 读取 `path` 的 SELinux context，不跟随符号链接。
///
/// 这里用 lossy 方式解码：实际 context 都是 ASCII，直接失败只会让出问题的路径更难定位。
pub fn lgetfilecon<P: AsRef<Path>>(path: P) -> Result<String> {
    let con = extattr::lgetxattr(&path, SELINUX_XATTR).with_context(|| {
        format!(
            "Failed to get SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    let con = String::from_utf8_lossy(&con);
    Ok(con.to_string())
}

/// 把 `path` 标记为普通系统文件。
pub fn setsyscon<P: AsRef<Path>>(path: P) -> Result<()> {
    lsetfilecon(path, SYSTEM_CON)
}

/// 递归地把 `dir` 下所有条目强制设为 `SYSTEM_CON`，覆盖已有标签。
pub fn restore_syscon<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path()) {
            setsyscon(&path)?;
        }
    }
    Ok(())
}

/// 递归地只修复标签缺失或明确为 "unlabeled" 的条目。
///
/// 这是模块文件的修复流程：解压可能丢掉 xattr，但已经设置好的标签（例如模块自带 file_contexts
/// 得到的结果）必须保留。遍历错误和读不出的标签会被跳过，避免一个异常 inode 中断整次扫描。
fn restore_syscon_if_unlabeled<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path())
            && let anyhow::Result::Ok(con) = lgetfilecon(&path)
            && (con == UNLABEL_CON || con.is_empty())
        {
            lsetfilecon(&path, SYSTEM_CON)?;
        }
    }
    Ok(())
}

/// 修复 ksud 负责的那部分标签。
///
/// 在 post-fs-data 和 late-load 之后运行，此时模块文件已位于最终位置。daemon 二进制总是重置为
/// `KSU_CON`；模块文件只修复未打标签的条目。
pub fn restorecon() -> Result<()> {
    lsetfilecon(defs::DAEMON_PATH, KSU_CON)?;
    restore_syscon_if_unlabeled(defs::MODULE_DIR)?;
    Ok(())
}
