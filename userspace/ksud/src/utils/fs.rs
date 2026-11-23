//! 文件系统辅助函数：确保目录/文件存在，以及写入可执行文件。

use anyhow::{Context, Error, Result, bail};
use std::{
    fs::{File, Permissions, create_dir_all, remove_file, set_permissions, write},
    io::ErrorKind::{AlreadyExists, NotFound},
    os::unix::prelude::PermissionsExt,
    path::Path,
};

/// 确保 `dir` 是一个空的、已存在的目录。
///
/// 若目录已存在会先整棵删除（`remove_dir_all`），因此调用方必须保证其中没有
/// 需要保留的数据；常用于重建模块临时工作目录。
pub fn ensure_clean_dir(dir: impl AsRef<Path>) -> Result<()> {
    let path = dir.as_ref();
    log::debug!("ensure_clean_dir: {}", path.display());
    if path.exists() {
        log::debug!("ensure_clean_dir: {} exists, remove it", path.display());
        std::fs::remove_dir_all(path)?;
    }
    Ok(std::fs::create_dir_all(path)?)
}

/// 确保 `file` 存在且是普通文件。
///
/// 用 `create_new` 探测：已存在的普通文件视为成功，目录/特殊文件或权限错误则报错，
/// 以免后续把目录当文件写入。
pub fn ensure_file_exists<T: AsRef<Path>>(file: T) -> Result<()> {
    match File::options().write(true).create_new(true).open(&file) {
        std::result::Result::Ok(_) => Ok(()),
        Err(err) => {
            if err.kind() == AlreadyExists && file.as_ref().is_file() {
                Ok(())
            } else {
                Err(Error::from(err))
                    .with_context(|| format!("{} is not a regular file", file.as_ref().display()))
            }
        }
    }
}

/// 递归创建目录，并确认最终路径确实是目录。
///
/// `create_dir_all` 对已存在目录返回成功，但同名的普通文件会报错；这里统一
/// 转成明确的“不是目录”错误，便于定位。
pub fn ensure_dir_exists<T: AsRef<Path>>(dir: T) -> Result<()> {
    let result = create_dir_all(&dir);
    if dir.as_ref().is_dir() && result.is_ok() {
        Ok(())
    } else {
        bail!("{} is not a regular directory", dir.as_ref().display())
    }
}

/// 把 `contents` 写成可执行文件 `path`。
///
/// `ignore_if_exist` 为真时保留已有文件；否则先 unlink 再写入，从调用方视角
/// 看相当于替换。
pub fn ensure_binary<T: AsRef<Path>>(
    path: T,
    contents: &[u8],
    ignore_if_exist: bool,
) -> Result<()> {
    if ignore_if_exist && path.as_ref().exists() {
        return Ok(());
    }

    ensure_dir_exists(path.as_ref().parent().ok_or_else(|| {
        anyhow::anyhow!(
            "{} does not have parent directory",
            path.as_ref().to_string_lossy()
        )
    })?)?;

    if let Err(e) = remove_file(path.as_ref())
        && e.kind() != NotFound
    {
        return Err(Error::from(e))
            .with_context(|| format!("failed to unlink {}", path.as_ref().display()));
    }

    write(&path, contents)?;
    set_permissions(&path, Permissions::from_mode(0o755))?;
    Ok(())
}

/// 统计 ZIP 中所有条目解压后的总大小。
pub fn get_zip_uncompressed_size(zip_path: &str) -> Result<u64> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(zip_path)?)?;
    let mut total: u64 = 0;
    for index in 0..zip.len() {
        total += zip.by_index(index)?.size();
    }
    Ok(total)
}
