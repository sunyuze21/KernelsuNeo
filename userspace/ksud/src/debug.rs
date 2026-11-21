//! 面向启用 CONFIG_KSU_DEBUG 内核的调试辅助功能。
//!
//! 除了 manager/appid 覆盖，本模块还负责带符号解析的内核模块加载（`insmod`）
//! 和进程标记管理。标记命令以 pid 为参数；set/unset 时 pid 0 表示所有进程。

use anyhow::{Context, Ok, Result, bail, ensure};
use std::{
    ffi::CString,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use crate::ksucalls;

const KERNEL_PARAM_PATH: &str = "/sys/module/kernelsu";

/// 从 sysfs 属性读取一个 u32，并去掉行尾换行。
fn read_u32(path: &PathBuf) -> Result<u32> {
    let content = std::fs::read_to_string(path)?;
    let content = content.trim();
    let content = content.parse::<u32>()?;
    Ok(content)
}

/// 覆写调试用 manager appid 内核参数，并打印变更前后的值。
fn set_kernel_param(appid: u32) -> Result<()> {
    let kernel_param_path = Path::new(KERNEL_PARAM_PATH).join("parameters");

    let ksu_debug_manager_appid = kernel_param_path.join("ksu_debug_manager_appid");
    let before_appid = read_u32(&ksu_debug_manager_appid)?;
    std::fs::write(&ksu_debug_manager_appid, appid.to_string())?;
    let after_appid = read_u32(&ksu_debug_manager_appid)?;

    println!("set manager appid: {before_appid} -> {after_appid}");

    Ok(())
}

/// 从 `/data/data/<pkg>` 的所有者推导 Android app id。
///
/// Android 的 uid 编码为 `user_id * 100000 + app_id`，取模即可去掉用户部分。
/// 这里假设包安装在主用户（user 0）下，这也是调试覆盖唯一针对的场景。
fn get_pkg_appid(pkg: &str) -> Result<u32> {
    // stat /data/data/<pkg>
    let uid = rustix::fs::stat(format!("/data/data/{pkg}"))
        .with_context(|| format!("stat /data/data/{pkg}"))?
        .st_uid as u32;
    Ok(uid % 100_000)
}

/// 让调试内核把 `pkg` 当作 manager 应用。
///
/// 该参数只在启用 CONFIG_KSU_DEBUG 时存在，因此其缺失会作为配置错误上报，
/// 而不是笼统的 I/O 失败。
pub fn set_manager(pkg: &str) -> Result<()> {
    ensure!(
        Path::new(KERNEL_PARAM_PATH).exists(),
        "CONFIG_KSU_DEBUG is not enabled"
    );

    let appid = get_pkg_appid(pkg)?;
    set_kernel_param(appid)?;
    // 强制停止该进程
    // 强制停止，使运行中的实例带着新 appid 重启。
    let _ = Command::new("am").args(["force-stop", pkg]).status();
    Ok(())
}

/// 加载内核模块，插入前先解析其未定义符号。
///
/// `params` 会拼成模块加载器要求的单个空格分隔字符串；路径会做规范化，
/// 因为加载器可能在不同工作目录下运行。
pub fn insmod(module: &Path, params: &[String]) -> Result<()> {
    // KSU_IOCTL_LOOKUP_SYMBOLS 在 UAPI v6 中引入。优先通过它解析模块的
    // 未定义（未导出）符号；较旧的内核，或解析失败时，回退到 /proc/kallsyms。
    const LOOKUP_SYMBOL_UAPI_VERSION: u32 = 6;

    let module = module
        .canonicalize()
        .with_context(|| format!("resolve module path failed: {}", module.display()))?;
    let cparams = CString::new(params.join(" "))?;
    let module_data =
        fs::read(&module).with_context(|| format!("read module failed: {}", module.display()))?;

    if ksucalls::get_info().uapi_version >= LOOKUP_SYMBOL_UAPI_VERSION {
        ksuinit::load_module_with_resolver(&module_data, &cparams, |names| {
            ksucalls::lookup_symbols(names).or_else(|e| {
                log::warn!("kernel symbol lookup failed: {e:#}, using /proc/kallsyms");
                ksuinit::resolve_kallsyms(names)
            })
        })
    } else {
        log::info!("kernel symbol lookup unsupported, using /proc/kallsyms");
        ksuinit::load_module(&module_data, &cparams)
    }
    .with_context(|| format!("load module failed: {}", module.display()))?;

    println!("Loaded kernel module: {}", module.display());
    Ok(())
}

/// 获取进程的标记状态
///
/// 先发起内核查询再检查 pid，因此内核错误优先，pid 0 也不会报告误导性的默认状态。
pub fn mark_get(pid: i32) -> Result<()> {
    let result = ksucalls::mark_get(pid)?;
    if pid == 0 {
        bail!("Please specify a pid to get its mark status");
    }
    println!(
        "Process {pid} mark status: {}",
        if result != 0 { "marked" } else { "unmarked" }
    );
    Ok(())
}

/// 标记一个进程
///
/// pid 0 表示标记所有进程。
pub fn mark_set(pid: i32) -> Result<()> {
    ksucalls::mark_set(pid)?;
    if pid == 0 {
        println!("All processes marked successfully");
    } else {
        println!("Process {pid} marked successfully");
    }
    Ok(())
}

/// 取消标记一个进程
///
/// pid 0 表示取消标记所有进程。
pub fn mark_unset(pid: i32) -> Result<()> {
    ksucalls::mark_unset(pid)?;
    if pid == 0 {
        println!("All processes unmarked successfully");
    } else {
        println!("Process {pid} unmarked successfully");
    }
    Ok(())
}

/// 刷新所有运行中进程的标记
pub fn mark_refresh() -> Result<()> {
    ksucalls::mark_refresh()?;
    println!("Refreshed mark for all running processes");
    Ok(())
}
