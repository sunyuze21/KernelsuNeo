//! 编译期嵌入的二进制文件和内核模块。
//!
//! `bin/<arch>` 下的文件通过 `RustEmbed` 打包进 ksud。在 Android 上，
//! `ensure_binaries` 会把辅助二进制解压到 `/data/adb/ksu/bin`；刷入 boot 时
//! 则直接读取资源。这里还提供 KMI 发现，供安装器选择匹配设备的模块。

use anyhow::Result;
use rust_embed::RustEmbed;

#[cfg(target_os = "android")]
mod android {
    use crate::assets::Asset;
    use crate::defs::BINARY_DIR;
    use crate::utils::ensure_binary;
    use const_format::concatcp;

    pub const RESETPROP_PATH: &str = concatcp!(BINARY_DIR, "resetprop");
    pub const BUSYBOX_PATH: &str = concatcp!(BINARY_DIR, "busybox");
    pub const BOOTCTL_PATH: &str = concatcp!(BINARY_DIR, "bootctl");

    /// 把所有嵌入的辅助二进制解压到 `BINARY_DIR`。
    ///
    /// `ignore_if_exist` 为真时跳过已存在的文件，这正是启动初始化想要的，
    /// 避免重写二进制。内部可执行文件（`ksuinit`、`waitsys`）和内核模块
    /// 不在此解压，因为它们直接从嵌入数据中使用。
    pub fn ensure_binaries(ignore_if_exist: bool) -> anyhow::Result<()> {
        for file in Asset::iter() {
            if file == "ksuinit" || file == "waitsys" || file.ends_with(".ko") {
                // 不解压内部可执行文件和内核模块
                continue;
            }
            let asset =
                Asset::get(&file).ok_or_else(|| anyhow::anyhow!("asset not found: {file}"))?;
            ensure_binary(format!("{BINARY_DIR}{file}"), &asset.data, ignore_if_exist)?;
        }

        // 创建 resetprop -> ksud 符号链接（resetprop 现已内置于 ksud）
        // 先删除，保证调用幂等，同时替换可能残留的旧链接。
        let resetprop_link = RESETPROP_PATH;
        let _ = std::fs::remove_file(resetprop_link);
        std::os::unix::fs::symlink("/data/adb/ksud", resetprop_link)?;

        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

// Android 上只需要本机架构的辅助文件，嵌入其他架构会让二进制变大。
// 主机构建保留所有架构，以便刷入 boot 时为目标设备打包对应的 ksuinit。
#[cfg(all(target_arch = "x86_64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/x86_64"]
struct Asset;

#[cfg(all(target_arch = "aarch64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/aarch64"]
struct Asset;

#[cfg(all(target_arch = "riscv64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/riscv64"]
struct Asset;

// 若非 Android，例如 macos、linux、windows，则包含所有架构。
#[cfg(not(target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin"]
struct Asset;

/// 获取嵌入资源；若嵌入器无需解压，则返回借用切片。
#[allow(unused)]
pub fn get_asset_data(name: &str) -> Result<std::borrow::Cow<'static, [u8]>> {
    let asset = Asset::get(name).ok_or_else(|| anyhow::anyhow!("asset not found: {name}"))?;
    Ok(asset.data)
}

/// 以装箱的字节源形式获取嵌入资源。
///
/// 这里抹掉具体的 `Cow` 类型，使只需要 `AsRef<[u8]>` 的调用方（例如与主机
/// 共享的 boot-patch 代码）不必依赖嵌入器的生命周期类型。
pub fn get_asset(name: &str) -> Result<Box<dyn AsRef<[u8]>>> {
    let asset = Asset::get(name).ok_or_else(|| anyhow::anyhow!("asset not found: {name}"))?;
    Ok(Box::new(asset.data))
}

/// 列出存在对应 `*_kernelsu.ko` 的 KMI 版本。
///
/// 文件名约定是 `<kmi>_kernelsu.ko`，去掉后缀即可得到 KMI 名称，
/// 供安装器展示给用户。
pub fn list_supported_kmi() -> std::vec::Vec<std::string::String> {
    let mut list = Vec::new();
    for file in Asset::iter() {
        // kmi_name = "xxx_kernelsu.ko"
        if let Some(kmi) = file.strip_suffix("_kernelsu.ko") {
            list.push(kmi.to_string());
        }
    }
    list
}
