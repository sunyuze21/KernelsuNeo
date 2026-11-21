//! `ksud` 是 KernelSU 的用户态部分：负责安装和管理模块、通过 `supercall`
//! ioctl 接口与内核驱动通信、执行 init 各阶段脚本，并提供兼容 Magisk 的
//! `su`/`resetprop` 入口。
//!
//! 该程序同时面向 Android 与构建主机编译；主机版本只暴露安装器/CI 使用的
//! 启动镜像修补命令。

#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]
#![allow(
    clippy::module_name_repetitions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::doc_markdown,
    clippy::too_many_lines,
    clippy::cast_possible_wrap,
    clippy::redundant_field_names
)]

// 启动镜像修补在所有目标平台都可用（主机上用于离线修补镜像），
// 因此这些模块始终参与编译。
mod apk_sign;
mod assets;
mod boot_patch;
#[cfg(target_os = "android")]
mod cli;
#[cfg(not(target_os = "android"))]
mod cli_non_android;
#[cfg(target_os = "android")]
mod debug;
mod defs;
#[cfg(target_os = "android")]
mod feature;
mod feature_id;
#[cfg(target_os = "android")]
mod hot_install;
#[cfg(target_os = "android")]
mod init_event;
#[cfg(target_os = "android")]
mod ksucalls;
#[cfg(target_os = "android")]
mod late_load;
mod lkm_image;
mod lkm_image_btf;
#[cfg(target_os = "android")]
mod magica;
#[cfg(target_os = "android")]
mod metamodule;
#[cfg(target_os = "android")]
mod module;
#[cfg(target_os = "android")]
mod module_config;
#[cfg(target_os = "android")]
mod preinit;
#[cfg(target_os = "android")]
mod profile;
#[cfg(target_os = "android")]
mod resetprop;
#[cfg(target_os = "android")]
mod restorecon;
#[cfg(target_os = "android")]
mod sepolicy;
#[cfg(target_os = "android")]
mod soft_reboot;
#[cfg(target_os = "android")]
mod su;
#[cfg(target_os = "android")]
mod sulog;
#[cfg(target_os = "android")]
mod unload;
#[cfg(target_os = "android")]
mod utils;

#[cfg(target_os = "android")]
#[allow(nonstandard_style, unused, unsafe_op_in_unsafe_fn)]
mod ksu_uapi;

fn main() -> anyhow::Result<()> {
    // Android 构建使用 `cli.rs` 中的完整命令行；主机构建只需要离线的
    // 启动镜像修补命令。
    #[cfg(target_os = "android")]
    {
        cli::run()
    }
    #[cfg(not(target_os = "android"))]
    {
        cli_non_android::run()
    }
}
