use std::fs;
use std::fs::File;
use std::io::ErrorKind;
use std::path::Path;
use zipsign_api::verify::{collect_keys, verify_zip};

const PUBLIC_KEY: [u8; 32] = *include_bytes!("../pub.key");
/// 标记文件存在时才启用模块签名校验。
pub const INTEGRITY_CHECK_PATH: &str = "/data/adb/ksu/.enable_integrity_check";

/// 使用内置公钥校验模块 ZIP 的 zipsign 签名。
///
/// # Errors
///
/// ZIP 无法打开、没有签名，或签名与内置公钥不匹配时返回错误。
pub fn module_integrity_check(zip: &str) -> Result<(), Box<dyn std::error::Error>> {
    let keys = collect_keys(std::iter::once(Ok(PUBLIC_KEY)))?;
    let mut file = File::open(zip)?;
    verify_zip(&mut file, &keys, None)?;
    Ok(())
}

/// 创建标记文件，开启安装时的模块签名校验。
///
/// # Errors
///
/// 无法创建 `/data/adb/ksu` 或标记文件时返回错误。
pub fn enable_integrity() -> std::io::Result<()> {
    if let Some(parent) = Path::new(INTEGRITY_CHECK_PATH).parent() {
        fs::create_dir_all(parent)?;
    }
    File::create(INTEGRITY_CHECK_PATH)?;
    Ok(())
}

/// 删除标记文件，关闭模块签名校验。
///
/// # Errors
///
/// 标记文件存在但无法删除时返回错误；不存在视为成功。
pub fn disable_integrity() -> std::io::Result<()> {
    match fs::remove_file(INTEGRITY_CHECK_PATH) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(()), // 已不存在视为成功
        Err(e) => Err(e),
    }
}
