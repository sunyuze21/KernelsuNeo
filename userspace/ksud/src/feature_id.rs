//! 特性 id 由特性管理器和 boot-patch（后者也会在主机上运行）共用。
//!
//! 数值是 ksud/内核契约的一部分：它们会通过 feature supercall 发送并存入
//! 持久化配置，因此绝不能重新编号。名称是 CLI 命令和模块
//! `manage.<feature>=true` 声明使用的稳定标识。

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use anyhow::{Result, bail};

/// 当前版本已知的特性标识。
///
/// `repr(u32)` 使判别值成为内核 supercall 的线上格式。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum FeatureId {
    SuCompat = 0,
    KernelUmount = 1,
    Sulog = 2,
    AdbRoot = 3,
    SelinuxHide = 4,
    // 5 曾是 webview_zygote_umount（v3.3.0），保留为未使用，以免旧配置被误用
    ShellSuAdbOnly = 6,
}

impl FeatureId {
    /// 按展示顺序排列的所有特性；未使用的 id 不列入。
    pub const ALL: [Self; 6] = [
        Self::SuCompat,
        Self::KernelUmount,
        Self::Sulog,
        Self::AdbRoot,
        Self::SelinuxHide,
        Self::ShellSuAdbOnly,
    ];

    /// 将线上 id 映射到已知特性；退役或未知 id（尤其是 5）返回 `None`，
    /// 以便调用方原样透传。
    pub const fn from_u32(id: u32) -> Option<Self> {
        match id {
            0 => Some(Self::SuCompat),
            1 => Some(Self::KernelUmount),
            2 => Some(Self::Sulog),
            3 => Some(Self::AdbRoot),
            4 => Some(Self::SelinuxHide),
            6 => Some(Self::ShellSuAdbOnly),
            _ => None,
        }
    }

    /// 配置、CLI 参数和模块声明中使用的稳定标识。
    ///
    /// 重命名会破坏现有模块，请视为 API。
    pub const fn name(self) -> &'static str {
        match self {
            Self::SuCompat => "su_compat",
            Self::KernelUmount => "kernel_umount",
            Self::Sulog => "sulog",
            Self::AdbRoot => "adb_root",
            Self::SelinuxHide => "selinux_hide",
            Self::ShellSuAdbOnly => "shell_su_adb_only",
        }
    }

    /// 特性 CLI 命令展示的人类可读说明。
    pub const fn description(self) -> &'static str {
        match self {
            Self::SuCompat => {
                "SU Compatibility Mode - allows authorized apps to gain root via traditional 'su' command"
            }
            Self::KernelUmount => {
                "Kernel Umount - controls whether kernel automatically unmounts modules when not needed"
            }
            Self::Sulog => {
                "SU Log - streams kernel sulog events to userspace and persists them to disk"
            }
            Self::AdbRoot => "ADB Root - Enable adbd root",
            Self::SelinuxHide => {
                "SELinux Hide - sanitize /sys/fs/selinux access results for app UIDs"
            }
            Self::ShellSuAdbOnly => {
                "Shell SU ADB Only - when shell is granted root, additionally require the process to be spawned by adbd or a KernelSU root process (ksu domain)"
            }
        }
    }
}

/// 把特性名称或其十进制 id 解析为已知的 `FeatureId`。
///
/// 接受数字形式，便于脚本使用内核 UAPI 中的 id。可接受的集合必须与
/// `FeatureId::from_u32` 保持一致；id 5 会被拒绝，因为它已退役，
/// 不能写回配置。
pub fn parse_feature_id(name: &str) -> Result<FeatureId> {
    match name {
        "su_compat" | "0" => Ok(FeatureId::SuCompat),
        "kernel_umount" | "1" => Ok(FeatureId::KernelUmount),
        "sulog" | "2" => Ok(FeatureId::Sulog),
        "adb_root" | "3" => Ok(FeatureId::AdbRoot),
        "selinux_hide" | "4" => Ok(FeatureId::SelinuxHide),
        "shell_su_adb_only" | "6" => Ok(FeatureId::ShellSuAdbOnly),
        _ => bail!("Unknown feature: {name}"),
    }
}
