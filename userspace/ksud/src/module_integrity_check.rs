use std::fs;
use std::fs::File;
use std::io::ErrorKind;
use std::path::Path;
use zipsign_api::verify::{collect_keys, verify_zip};

const PUBLIC_KEY: [u8; 32] = *include_bytes!("../pub.key");

pub fn module_integrity_check(zip: &str) -> Result<(), Box<dyn std::error::Error>> {
    let keys = collect_keys(std::iter::once(Ok(PUBLIC_KEY)))?;
    let mut file = File::open(zip)?;
    verify_zip(&mut file, &keys, None)?;
    println!("签名验证通过");
    Ok(())
}
pub fn enable_integrity() -> std::io::Result<()> {
    if let Some(parent) = Path::new("/data/adb/ksu/.enable_integrity_check").parent() {
        fs::create_dir_all(parent)?;
    }
    File::create("/data/adb/ksu/.enable_integrity_check")?;
    Ok(())
}
pub fn disable_integrity() -> std::io::Result<()> {
    match fs::remove_file("/data/adb/ksu/.enable_integrity_check") {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(()), // 已不存在视为成功
        Err(e) => Err(e),
    }
}
