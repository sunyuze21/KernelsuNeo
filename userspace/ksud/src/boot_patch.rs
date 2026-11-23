//! 为 KernelSU 的 LKM（可加载内核模块）模式修补和还原 Android boot 镜像。
//!
//! 修补流程会把匹配的 `kernelsu.ko`、一阶段 init 替代程序 `ksuinit` 以及可选的
//! `ksu_config` 配置项写入 ramdisk，然后重新打包镜像，供刷写或输出到文件。
//! `android-bootimg` 解析器支持 boot、init_boot 和 vendor_boot 镜像：它能识别
//! boot header v0-v4 与 vendor_boot v3/v4，但实际修补仅限 boot header v3/v4
//! （以及 AVD 使用的裸 ramdisk）。还原时要么写回 ramdisk 中备份标记所指向的
//! 原厂镜像，要么从当前 ramdisk 中移除 KernelSU 后重新打包。
//!
//! LKM 必须与设备内核匹配，因此需要解析出形如 `android12-5.10` 的 KMI 字符串，
//! 来源依次为显式 `--kmi`、OTA 时另一个槽位的 boot 镜像、当前内核（`uname` 或
//! vendor 模块的 `vermagic`），最后是用户提供的镜像或内核。使用 `--kernel`
//! 替换内核时始终操作 `boot` 分区；LKM 模式在存在 `init_boot` 且 KMI 不是
//! Android 12 基线时优先选择 `init_boot`。

#![allow(clippy::ref_option, clippy::needless_pass_by_value)]

use std::fs::File;
use std::io::{Cursor, Seek, SeekFrom};
use std::path::Path;
use std::path::PathBuf;

use android_bootimg::cpio::{Cpio, CpioEntry};
use android_bootimg::parser::{BootImage, BootImageVersion, RamdiskImage};
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::Context;
use anyhow::Result;
use anyhow::anyhow;
use anyhow::bail;
use anyhow::ensure;
use memmap2::{Mmap, MmapOptions};
use regex_lite::Regex;

use crate::assets;
use crate::feature_id::{FeatureId, parse_feature_id};

/// 仅在 Android 设备上编译的辅助函数集合：内核版本与 KMI 探测、原厂镜像备份、
/// 分区选择、块设备刷写以及 OTA 槽位切换。
#[cfg(target_os = "android")]
mod android {
    use super::Result;
    pub(super) use crate::defs::{BACKUP_FILENAME, KSU_BACKUP_DIR, KSU_BACKUP_FILE_PREFIX};
    use crate::defs::{DEFAULT_PACKAGE_NAME, KSU_TEMP_BACKUP_DIR_NAME};
    use android_bootimg::cpio::{Cpio, CpioEntry};
    use anyhow::{Context, anyhow, bail, ensure};
    use regex_lite::Regex;
    use rustix::process::getuid;
    use std::fs::{File, OpenOptions};
    use std::io::Write;
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::PermissionsExt;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use crate::utils;

    /// 拒绝低于内置 LKM 所支持 GKI 基线（5.10）的内核。仅在直接修补当前设备
    /// 而非修补用户提供的镜像时调用。
    pub(super) fn ensure_gki_kernel() -> Result<()> {
        let version = get_kernel_version()?;
        // GKI 从 5.10 开始。注意最后一项比较的是 patch 版本号而非主版本号，
        // 因此它并没有额外校验更高的主版本。
        let is_gki = version.0 == 5 && version.1 >= 10 || version.2 > 5;
        ensure!(is_gki, "only support GKI kernel");
        Ok(())
    }

    /// 从运行中内核的 release 字符串中提取 `major.minor.patch`，版本号后可能
    /// 带有厂商后缀。
    pub fn get_kernel_version() -> Result<(i32, i32, i32)> {
        let uname = rustix::system::uname();
        let version = uname.release().to_string_lossy();
        let re = Regex::new(r"(\d+)\.(\d+)\.(\d+)")?;
        if let Some(captures) = re.captures(&version) {
            let major = captures
                .get(1)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Major version parse error"))?;
            let minor = captures
                .get(2)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Minor version parse error"))?;
            let patch = captures
                .get(3)
                .and_then(|m| m.as_str().parse::<i32>().ok())
                .ok_or_else(|| anyhow!("Patch version parse error"))?;
            Ok((major, minor, patch))
        } else {
            Err(anyhow!("Invalid kernel version string"))
        }
    }

    /// 将内核 release 或 `vermagic` 字符串规范化为 KernelSU 的 KMI 形式
    /// `androidN-X.Y`（例如 `android12-5.10`）。
    ///
    /// 正则跳过内核版本前的可选前缀，取出 `<major>.<minor>` 和 `androidN`
    /// 标记，并忽略其余部分。
    fn parse_kmi(version: &str) -> Result<String> {
        let re = Regex::new(r"(.* )?(\d+\.\d+)(\S+)?(android\d+)(.*)")?;
        let cap = re
            .captures(version)
            .ok_or_else(|| anyhow::anyhow!("Failed to get KMI from boot/modules"))?;
        let android_version = cap.get(4).map_or("", |m| m.as_str());
        let kernel_version = cap.get(2).map_or("", |m| m.as_str());
        Ok(format!("{android_version}-{kernel_version}"))
    }

    /// 从 `uname` 读取 KMI。部分厂商内核的 release 字符串缺少 `androidN`
    /// 标记，此时会失败。
    fn parse_kmi_from_uname() -> Result<String> {
        let uname = rustix::system::uname();
        let version = uname.release().to_string_lossy();
        parse_kmi(&version)
    }

    /// 兜底方案：从任意 vendor 内核模块的 `vermagic` 读取 KMI，该字段记录了
    /// 模块编译时对应的精确内核版本。
    fn parse_kmi_from_modules() -> Result<String> {
        use std::io::BufRead;
        // 在 /vendor/lib/modules 中查找一个 *.ko
        let modfile = std::fs::read_dir("/vendor/lib/modules")?
            .filter_map(Result::ok)
            .find(|entry| entry.path().extension().is_some_and(|ext| ext == "ko"))
            .map(|entry| entry.path())
            .ok_or_else(|| anyhow!("No kernel module found"))?;
        let output = Command::new("modinfo").arg(modfile).output()?;
        for line in output.stdout.lines().map_while(Result::ok) {
            if line.starts_with("vermagic") {
                return parse_kmi(&line);
            }
        }
        bail!("Parse KMI from modules failed")
    }

    /// 获取当前内核的 KMI：优先使用 `uname`，失败时回退到 vendor 模块元数据。
    pub fn get_current_kmi() -> Result<String> {
        parse_kmi_from_uname().or_else(|_| parse_kmi_from_modules())
    }

    /// 对整个镜像（或块设备）计算 SHA-1。小写十六进制摘要同时用作备份文件名，
    /// 这样还原时能精确找到被替换掉的原厂镜像。
    fn calculate_sha1(file_path: impl AsRef<Path>) -> Result<String> {
        use sha1::Digest;
        use std::io::Read;
        let mut file = std::fs::File::open(file_path.as_ref())?;
        let mut hasher = sha1::Sha1::new();
        let mut buffer = [0; 1024];

        loop {
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }

        let result = hasher.finalize();
        Ok(base16ct::lower::encode_string(&result))
    }

    /// 为原厂镜像备份打开目标文件，优先使用 `/data/adb/ksu`，失败时回退到
    /// Manager 应用的设备加密数据目录。
    ///
    /// 需要回退是因为 Manager 可能以应用 UID 调用 ksud，而该 UID 无法写入
    /// `/data/adb`。两条路径都会截断同名旧文件，这是安全的，因为哈希值就是
    /// 文件的身份标识。
    fn find_backup_location(sha1: &String) -> Result<(File, String)> {
        let filename = format!("{KSU_BACKUP_FILE_PREFIX}{sha1}");
        let target = format!("{KSU_BACKUP_DIR}{filename}");
        if let Ok(target_file) = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&target)
        {
            return Ok((target_file, target));
        }

        // 无权访问 /data/adb，
        // 因此改为保存到 /data/user_de/$USER/$PKG/boot_backup
        // Android 用户 id 等于 uid / 100000（每个用户占用 100000 个 uid）。
        let user_id = getuid().as_raw() / 100_000;

        let backup_dir =
            format!("/data/user_de/{user_id}/{DEFAULT_PACKAGE_NAME}/{KSU_TEMP_BACKUP_DIR_NAME}");
        // 重建目录前先清理残留的临时文件。
        std::fs::remove_dir_all(&backup_dir).ok();
        std::fs::create_dir(&backup_dir)?;
        let backup_file = format!("{backup_dir}/{filename}");
        if let Ok(file) = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&backup_file)
        {
            return Ok((file, backup_file));
        }

        bail!("Both /data/adb/ksu and {backup_dir} are not accessible!")
    }

    /// 将未修补的镜像复制到备份位置，并把其 SHA-1 以 `stock_image.sha1`
    /// 写入 ramdisk，这样即使存在其他备份或活动槽位发生变化，后续还原也能
    /// 找到正确的文件。
    ///
    /// 必须在修改 ramdisk 之前执行，且仅当源镜像仍为原厂状态时调用。
    pub(super) fn do_backup(cpio: &mut Cpio, image: &Path) -> Result<()> {
        let sha1 = calculate_sha1(image)?;
        let (mut target_file, target) = find_backup_location(&sha1)?;
        println!("- Backing up stock boot image");
        let mut source = OpenOptions::new()
            .create(false)
            .truncate(false)
            .read(true)
            .write(false)
            .open(image)?;

        // 使用 io::copy 而不是 fs::copy，以便能够复制块设备
        std::io::copy(&mut source, &mut target_file)
            .with_context(|| format!("failed to backup to {target}"))?;

        let backup_file = CpioEntry::regular(0o755, Box::new(sha1));
        cpio.add(BACKUP_FILENAME, backup_file)?;
        println!("- Stock image backed up to");
        println!("- {target}");
        Ok(())
    }

    /// 删除旧的原厂镜像备份，只保留 `sha1` 对应的文件。实际只需要一份原厂
    /// 镜像，而这些文件很大，因此还原成功后清理过期备份。
    pub(super) fn clean_backup(sha1: &str) -> Result<()> {
        println!("- Cleaning up backup");
        let backup_name = format!("{KSU_BACKUP_FILE_PREFIX}{sha1}");
        let dir = std::fs::read_dir(KSU_BACKUP_DIR)?;
        for entry in dir.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            if let Some(name) = path.file_name() {
                let name = name.to_string_lossy().to_string();
                if name != backup_name
                    && name.starts_with(KSU_BACKUP_FILE_PREFIX)
                    && std::fs::remove_file(path).is_ok()
                {
                    println!("- Removed {name}");
                }
            }
        }
        Ok(())
    }

    /// 将完整镜像写入块设备并刷新。
    ///
    /// 分区可能被内核标记为只读，因此先清除该标志。设备刚打开时偏移为 0，
    /// 不需要额外 seek。
    pub(super) fn flash_partition(partition: &str, data: &[u8]) -> Result<()> {
        let mut blk = std::fs::OpenOptions::new()
            .write(true)
            .truncate(false)
            .create(false)
            .open(partition)
            .with_context(|| format!("open {partition}"))?;
        // BLKROSET 是设置块设备只读标志的 ioctl，`_IO(0x12, 93)` 是它的命令号
        // （0x12 表示块设备子系统，93 即 BLKROSET）。传入 0 表示清除只读标志。
        // 这里必须用 unsafe：ioctl 接收裸 fd 和指针，`val` 在调用期间保持存活。
        unsafe {
            // 块设备只读标志的 ioctl 命令号；0 表示清除只读标志。
            const BLKROSET: i32 = libc::_IO(0x12, 93);
            let mut val: libc::c_int = 0;
            if libc::ioctl(blk.as_raw_fd(), BLKROSET, &raw mut val) != 0 {
                bail!("Failed to set rw for {partition}: {}", *libc::__errno());
            }
        }
        blk.write_all(data).context("flash boot failed")?;
        blk.sync_all().context("sync boot failed")?;
        Ok(())
    }

    /// 选择接收补丁的分区。
    ///
    /// Android 13+ 的 GKI 设备把通用 ramdisk 放在 `init_boot`，内核仍留在
    /// `boot`，因此 LKM 模式必须修补 `init_boot`；Android 12（`android12-`
    /// KMI 基线）的 ramdisk 仍在 `boot` 中。替换内核（`--kernel`）同样只能
    /// 选择 `boot`。显式 `--partition` 会覆盖以上判断。
    pub fn choose_boot_partition(
        kmi: &str,
        is_replace_kernel: bool,
        partition: &Option<String>,
    ) -> String {
        let slot_suffix = get_slot_suffix(false);
        let skip_init_boot = kmi.starts_with("android12-");
        let init_boot_exist =
            Path::new(&format!("/dev/block/by-name/init_boot{slot_suffix}")).exists();

        // 若显式指定了分区，则直接使用该分区
        // 未知名称回退到 `boot`，避免拼出不存在的设备路径。
        if let Some(part) = partition {
            return match part.as_str() {
                "boot" | "init_boot" | "vendor_boot" => part.clone(),
                _ => "boot".to_string(),
            };
        }

        // 若存在 init_boot 且未跳过，则使用它
        if !is_replace_kernel && init_boot_exist && !skip_init_boot {
            return "init_boot".to_string();
        }

        "boot".to_string()
    }

    /// 返回当前活动槽位后缀（`_a` 或 `_b`），A-only 设备返回空字符串。
    /// `ota` 为 true 时返回另一个槽位，因为 OTA 会安装并启动非活动槽位。
    pub fn get_slot_suffix(ota: bool) -> String {
        let mut slot_suffix = utils::getprop("ro.boot.slot_suffix").unwrap_or_default();
        if !slot_suffix.is_empty() && ota {
            if slot_suffix == "_a" {
                slot_suffix = "_b".to_string();
            } else {
                slot_suffix = "_a".to_string();
            }
        }
        slot_suffix
    }

    /// 列出当前槽位中实际存在的可修补分区。
    pub fn list_available_partitions() -> Vec<String> {
        let slot_suffix = get_slot_suffix(false);
        let candidates = vec!["boot", "init_boot", "vendor_boot"];
        candidates
            .into_iter()
            .filter(|name| Path::new(&format!("/dev/block/by-name/{name}{slot_suffix}")).exists())
            .map(ToString::to_string)
            .collect()
    }

    /// 根据 `choose_boot_partition` 的选择构造 `/dev/block/by-name/...` 路径，
    /// 并按 `ota` 切换槽位。
    pub(super) fn auto_boot_partition_path(
        kmi: &str,
        ota: bool,
        is_replace_kernel: bool,
        partition: &Option<String>,
    ) -> PathBuf {
        let slot_suffix = get_slot_suffix(ota);
        let name = choose_boot_partition(kmi, is_replace_kernel, partition);
        PathBuf::from(format!("/dev/block/by-name/{name}{slot_suffix}"))
    }

    /// 完成 OTA 安装：把刚刷入的槽位设为活动槽位。
    ///
    /// 只切换槽位还不够：如果新系统启动失败，设备仍需回退到旧槽位。因此这里
    /// 还会写入一次性的 `post-fs-data.d/post_ota.sh`，在下次开机时标记启动
    /// 成功，然后删除该脚本自身和 `bootctl`。
    ///
    /// 如果 boot_control HAL 不可用（`bootctl hal-info` 执行失败），则不存在
    /// 可切换的槽位，直接返回。
    pub(super) fn post_ota() -> Result<()> {
        use crate::assets::BOOTCTL_PATH;
        use crate::defs::ADB_DIR;
        let status = Command::new(BOOTCTL_PATH).arg("hal-info").status()?;
        if !status.success() {
            return Ok(());
        }

        let current_slot = Command::new(BOOTCTL_PATH)
            .arg("get-current-slot")
            .output()?
            .stdout;
        let current_slot = String::from_utf8(current_slot)?;
        let current_slot = current_slot.trim();
        // 槽位编号为 0 和 1：当前为 0 时目标为 1，否则目标为 0。
        let target_slot = i32::from(current_slot == "0");

        Command::new(BOOTCTL_PATH)
            .arg(format!("set-active-boot-slot {target_slot}"))
            .status()?;

        let post_fs_data = Path::new(ADB_DIR).join("post-fs-data.d");
        utils::ensure_dir_exists(&post_fs_data)?;
        let post_ota_sh = post_fs_data.join("post_ota.sh");

        let sh_content = format!(
            r"
{BOOTCTL_PATH} mark-boot-successful
rm -f {BOOTCTL_PATH}
rm -f /data/adb/post-fs-data.d/post_ota.sh
"
        );

        std::fs::write(&post_ota_sh, sh_content)?;
        std::fs::set_permissions(post_ota_sh, std::fs::Permissions::from_mode(0o755))?;

        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

/// 以只读方式映射镜像，使解析器可以直接借用切片而无需复制整个文件。块设备
/// 能通过 `seek(End(0))` 报告大小，因此 `/dev/block/by-name` 路径同样适用。
fn map_file(file: &Path) -> Result<Mmap> {
    let mut f = File::open(file).with_context(|| format!("open {}", file.display()))?;
    let len = f
        .seek(SeekFrom::End(0))
        .with_context(|| format!("seek end of {}", file.display()))? as usize;
    // 只要映射期间底层文件不被修改就是安全的；调用方需要在所有解析结果仍
    // 借用该映射时保持 Mmap 存活。
    let mmap = unsafe { MmapOptions::new().len(len).map(&f)? };
    Ok(mmap)
}

/// 在原始内核字节中定位并解析 KMI 字符串。
///
/// 内核镜像中会内嵌形如 `5.10.66-android12-9-...` 的字符串。随机数据也可能
/// 含有 `N.N`，因此扫描只保留类似版本号的位置：主版本号必须是 5-9（`5.x`
/// 还要求点号后第二位也是数字，即匹配 5.10 这类双位次版本），并且候选字符串
/// 必须在接下来 100 字节内以 NUL 结尾。随后正则要求同时出现
/// `<major>.<minor>` 和 `androidN` 标记。
pub fn parse_kmi(buffer: &[u8]) -> Result<String> {
    let re = Regex::new(r"(\d+\.\d+)(?:\S+)?(android\d+)").context("Failed to compile regex")?;
    buffer
        .windows(4)
        .enumerate()
        .filter(|(_, x)| {
            x[1] == b'.'
                && x[2].is_ascii_digit()
                && match x[0] {
                    b'5' => x[3].is_ascii_digit(),
                    b'6'..=b'9' => true,
                    _ => false,
                }
        })
        .find_map(|(i, _)| {
            let a = &buffer[i..buffer.len().min(i + 100)];
            if let Some(e) = a.iter().position(|c| *c == 0)
                && let Ok(s) = std::str::from_utf8(&a[..e])
                && let Some(caps) = re.captures(s)
                && let (Some(kernel_version), Some(android_version)) = (caps.get(1), caps.get(2))
            {
                Some(format!(
                    "{}-{}",
                    android_version.as_str(),
                    kernel_version.as_str()
                ))
            } else {
                None
            }
        })
        .ok_or_else(|| {
            // 走 stderr：boot-info 的 stdout 会被 Manager 当作返回值解析。
            eprintln!("- Failed to detect KMI version");
            anyhow!("Try to choose LKM manually")
        })
}

/// 读取独立内核镜像（例如 `--kernel` 的参数）并提取其 KMI。
fn parse_kmi_from_kernel(kernel: &Path) -> Result<String> {
    let data = std::fs::read(kernel).context("Failed to read kernel file")?;
    parse_kmi(&data)
}

/// 从 boot 镜像的内核块中提取 KMI。OTA 时也可用于 `boot_b`，因为当前运行的
/// 内核属于另一个槽位。
fn parse_kmi_from_boot(image: &Path) -> Result<String> {
    let data = map_file(image)?;
    let boot = BootImage::parse(&data)?;
    if let Some(kernel) = boot.get_blocks().get_kernel() {
        let mut output = Vec::<u8>::new();
        // raw=false 会先解压内核；版本字符串位于解压后的数据中。
        kernel.dump(&mut output, false)?;
        parse_kmi(&output)
    } else {
        // init_boot 和 vendor_boot 不包含内核，无法用于探测。
        bail!("no kernel found in boot image")
    }
}

/// 解压并解析 boot 或 vendor_boot 镜像中的 cpio ramdisk。
///
/// 对 vendor boot 而言，优先选择名为 `init_boot` 的 ramdisk 表项，而不是名称为空的表项，
/// 这与原版 magiskboot 的查找顺序一致（先 init_boot.cpio，再 ramdisk.cpio）。
/// 返回的索引是后续需要替换的 vendor ramdisk 表项；普通 boot 镜像只有一个
/// ramdisk，因此返回 `None`。
///
/// vendor ramdisk 表无法整体导出，因此只选择目标表项，并按该项自身记录的
/// 压缩格式（gzip、lz4 等）解压。
fn extract_ramdisk(ramdisk_image: &RamdiskImage) -> Result<(Cpio, Option<usize>)> {
    if ramdisk_image.is_vendor_ramdisk() {
        let (pos, target) = ramdisk_image
            .iter_vendor_ramdisk()
            .enumerate()
            .find(|e| e.1.get_name_raw() == b"init_boot")
            .or_else(|| {
                // GKI 的 vendor_boot 把通用 ramdisk 放在名称为空的表项中；
                // 旧版布局同样以该表项作为兜底。
                ramdisk_image
                    .iter_vendor_ramdisk()
                    .enumerate()
                    .find(|e| e.1.get_name_raw() == b"")
            })
            .ok_or_else(|| anyhow!("No suitable vendor ramdisk entry found"))?;
        let mut buf = Vec::<u8>::new();
        // raw=false：先解压，再交给 cpio 解析器。
        target.dump(&mut buf, false)?;
        Ok((Cpio::load_from_data(&buf)?, Some(pos)))
    } else {
        // 普通 boot 镜像只有一个 ramdisk，直接整体解压。
        let mut buf = Vec::<u8>::new();
        ramdisk_image.dump(&mut buf, false)?;
        Ok((Cpio::load_from_data(&buf)?, None))
    }
}

/// 拒绝当前修补器不支持的 boot 镜像版本。低于 v3 的 Android boot header
/// 会被拒绝；vendor_boot v3/v4 和裸 ramdisk 可以继续处理。对非 Android
/// header 版本，此检查不做任何限制。
fn enforce_bootimage_version(boot: &BootImage<'_>) -> Result<()> {
    if let BootImageVersion::Android(ver) = boot.get_header().get_version()
        && ver < 3
    {
        bail!("bootimage version {ver} is not supported!")
    }
    Ok(())
}

/// `ksud boot-patch` 的命令行参数：源镜像或自动探测的分区、可选的
/// kernel/init/LKM 替换、ramdisk 配置，以及刷写和输出目标。
#[allow(clippy::struct_excessive_bools)]
#[derive(clap::Args, Debug)]
pub struct BootPatchArgs {
    /// boot 镜像路径；未指定时会尝试自动查找 boot 镜像
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// 用于替换的内核镜像路径
    #[arg(short, long)]
    pub kernel: Option<PathBuf>,

    /// 用于替换的 LKM 模块路径；未指定时使用内置模块
    #[arg(short, long)]
    pub module: Option<PathBuf>,

    /// 需要替换的 init
    #[arg(short, long)]
    pub init: Option<PathBuf>,

    /// 未指定 boot 镜像时使用另一个槽位
    #[cfg(target_os = "android")]
    #[arg(short = 'u', long, default_value = "false")]
    pub ota: bool,

    /// 修补后刷写到 boot 分区
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = "false")]
    pub flash: bool,

    /// 强制把源镜像备份为原厂镜像
    #[cfg(target_os = "android")]
    #[arg(long, default_value = "false")]
    pub backup: bool,

    /// 输出路径。未指定时使用当前目录。
    /// 若指定了该路径，即使带有 --flash，
    /// boot 镜像也会写入该目录。
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// 输出路径。未指定时使用当前目录。
    #[cfg(not(target_os = "android"))]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// KMI 版本；指定后使用给定的 KMI
    #[arg(long, default_value = None)]
    pub kmi: Option<String>,

    /// 覆盖目标分区（init_boot | boot | vendor_boot）
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub partition: Option<String>,

    /// 输出文件名。若指定了该值，即使带有 --flash，
    /// boot 镜像也会写入输出目录。
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// 输出文件名。
    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// 追加到 boot 镜像 header 的额外 cmdline
    #[arg(long, default_value = None)]
    pub cmdline: Option<String>,

    #[command(flatten)]
    config: KsuConfigArgs,

    /// 强制启用 adbd 并关闭 adbd 认证
    #[arg(long, default_value = "false")]
    enable_adbd: bool,

    /// 添加更多 adb_debug 属性
    #[arg(long, required = false)]
    adb_debug_prop: Option<String>,

    /// 不（重新）安装 kernelsu，只修改配置（allow_shell 等）
    #[arg(long, default_value = "false")]
    no_install: bool,

    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = "aarch64")]
    arch: String,

    /// 修补 ramdisk 而不是 boot 镜像。用于 AVD ramdisk
    #[arg(long, default_value = "false")]
    ramdisk: bool,
}

/// KernelSU 模块参数，保存在 ramdisk 的 `/ksu_config`（boot-patch）或
/// 注入的 capsule（boot-patch-v2）中。
#[derive(clap::Args, Debug, Default)]
pub struct KsuConfigArgs {
    /// 始终允许 shell 获取 root 权限
    #[arg(long, default_value = "false")]
    allow_shell: bool,

    /// 不加载自定义 rc
    #[arg(long, default_value = "false")]
    no_custom_rc: bool,

    /// 不执行模块的 preinit 动作
    #[arg(long, default_value = "false")]
    no_preinit: bool,

    /// 在启动时把某个内核功能强制设为指定值，例如 `su_compat=0`。
    /// 被强制的功能无法由用户空间修改或加载。可重复指定。
    #[arg(long, value_name = "FEATURE=VALUE", value_parser = parse_force_feature)]
    force_feature: Vec<(FeatureId, u64)>,

    /// [仅开发者] 在启动时按名称阻止某个内核模块加载，例如
    /// `oplus_secure_guard`。可用逗号分隔和/或重复指定。省略即清除。
    /// 被阻止的模块会因 EPERM 加载失败；若阻止的是第一阶段
    /// init 会加载的模块（modules.load / modules.load.recovery），
    /// 或其他模块所依赖的模块，可能导致设备无法开机。风险自负。
    #[arg(long = "block-module", value_name = "NAME", value_delimiter = ',', value_parser = parse_block_module)]
    block_module: Vec<String>,
}

/// `block_modules=` 值的最大长度。内核会把该值复制到 256 字节的缓冲区，
/// 因此最多只剩 255 字节可用；更长的内容会在内核侧被静默截断，
/// 所以在主机端就直接拒绝。
const MAX_BLOCK_MODULES_LEN: usize = 255;

impl KsuConfigArgs {
    /// 校验完整取值只有在拼接后才能确定的选项。请在调用 `apply` 之前执行，
    /// 以便用户收到报错，而不是内核静默截断过长的列表
    /// （否则会有部分模块未被拦截）。
    pub fn validate(&self) -> Result<()> {
        if !self.block_module.is_empty() {
            let len = self.block_module.join(",").len();
            ensure!(
                len <= MAX_BLOCK_MODULES_LEN,
                "block-module list is too long ({len} bytes, max {MAX_BLOCK_MODULES_LEN}); block fewer modules"
            );
        }
        Ok(())
    }

    /// 在 `config` 中完整设置这些选项的状态，其余条目保持不变。
    pub fn apply(&self, config: &mut Vec<String>) {
        set_config_flag(config, "no custom rc", "norc=1", self.no_custom_rc);
        set_config_flag(config, "allow shell", "allow_shell=1", self.allow_shell);
        set_config_flag(config, "no preinit", "preinit=0", self.no_preinit);

        config.retain(|v| !v.starts_with("force_feature="));
        if !self.force_feature.is_empty() {
            let mut entries = Vec::with_capacity(self.force_feature.len());
            for (feature_id, value) in &self.force_feature {
                println!("- Forcing feature {} to {value}", feature_id.name());
                entries.push(format!("{}:{value}", feature_id.name()));
            }
            config.push(format!("force_feature={}", entries.join(",")));
        }

        config.retain(|v| !v.starts_with("block_modules="));
        if !self.block_module.is_empty() {
            let list = self.block_module.join(",");
            println!("- Blocking modules: {list}");
            config.push(format!("block_modules={list}"));
        }
    }
}

/// 校验 `--block-module` 选项的单个模块名。内核的 `block_modules`
/// 缓冲区只有 255 字节，因此模块名要远小于该上限。
fn parse_block_module(s: &str) -> Result<String> {
    ensure!(!s.is_empty(), "module name must not be empty");
    ensure!(
        s.len() <= 64,
        "module name '{s}' is too long (max 64 bytes)"
    );
    ensure!(
        s.bytes().all(|ch| ch.is_ascii_alphanumeric() || ch == b'_'),
        "invalid module name '{s}': only letters, digits and '_' are allowed"
    );
    Ok(s.to_owned())
}

/// 在 `ksu_config` 列表中精确添加或删除一个配置项。列表以空格分隔，因此这里
/// 使用完全相等（而非前缀匹配）比较，保证对同一镜像重复修补是幂等的。
fn set_config_flag(config: &mut Vec<String>, name: &str, value: &str, add: bool) {
    let has_value = config.iter().any(|v| v == value);

    if add {
        println!("- Adding {name} config");
        if !has_value {
            config.push(value.to_owned());
        }
    } else if has_value {
        println!("- Removing {name} config");
        config.retain(|v| v != value);
    }
}

/// 解析 `--force-feature` 的 `FEATURE=VALUE` 形式参数。
fn parse_force_feature(s: &str) -> Result<(FeatureId, u64)> {
    let (name, value) = s
        .split_once('=')
        .context("expected FEATURE=VALUE, e.g. su_compat=0")?;
    let value = value
        .parse()
        .with_context(|| format!("invalid value for feature {name}: {value}"))?;
    Ok((parse_feature_id(name)?, value))
}

/// 在 boot、init_boot 或 vendor_boot 镜像上应用或更新 KernelSU 的 LKM 补丁。
///
/// 执行顺序很重要：先定位镜像和 KMI，解压 ramdisk 并加载 cpio 归档；首次修补
/// 时把原始 `init` 重命名为 `init.real`，再以 `init` 注入 `ksuinit`，同时写入
/// `kernelsu.ko` 和配置项；随后视需要备份原厂镜像，重新打包，最后刷写或
/// 输出结果。
///
/// 使用 `--no-install` 时只修改 ramdisk 配置，保留已有载荷；使用 `--kernel`
/// 时还会替换内核块（GKI 模式），因此强制选择 `boot` 分区。`--ramdisk` 表示
/// 输入是没有 boot header 的裸 ramdisk（供 AVD 使用）。
pub fn patch(args: BootPatchArgs) -> Result<()> {
    let inner = move || {
        let BootPatchArgs {
            boot: image,
            init,
            kernel,
            module: kmod,
            out,
            kmi,
            out_name,
            cmdline,
            config,
            enable_adbd,
            adb_debug_prop,
            no_install,
            #[cfg(target_os = "android")]
            ota,
            #[cfg(target_os = "android")]
            flash,
            #[cfg(target_os = "android")]
            backup,
            #[cfg(target_os = "android")]
            partition,
            #[cfg(not(target_os = "android"))]
            arch,
            ramdisk,
        } = args;

        println!(include_str!("banner"));

        // 显式指定 --boot 表示修补的是文件而不是当前设备分区，因此可以跳过
        // 设备相关检查和自动探测。
        #[cfg(target_os = "android")]
        let patch_file = image.is_some();

        #[cfg(target_os = "android")]
        if !patch_file {
            // 内置 LKM 只支持 GKI 内核；外部提供的镜像可能属于其他设备，
            // 因此该检查只对正在运行的设备有意义。
            ensure_gki_kernel()?;
        }

        let is_replace_kernel = kernel.is_some();

        if ramdisk && is_replace_kernel {
            bail!("incompatiable option: --ramdisk and --kernel")
        }

        #[cfg(target_os = "android")]
        if ramdisk && flash {
            bail!("incompatiable option: --ramdisk and --flash")
        }

        if is_replace_kernel {
            ensure!(
                init.is_none() && kmod.is_none(),
                "init and module must not be specified."
            );
        }

        // None 表示 --no-install：保留现有 LKM 的标记。
        // 否则记录 LKM 来自内置资源（`bundled=1`）还是用户提供的文件。
        let bundled_lkm = (!no_install).then_some(kmod.is_none());

        // 按优先级解析 KMI：显式 --kmi；自定义 LKM 或 --no-install（无需匹配）；
        // OTA 时另一个槽位的 boot 镜像；当前运行的内核；最后是提供的镜像或内核。
        let kmi = kmi.map_or_else(
            || -> Result<_> {
                if kmod.is_some() || (no_install && image.is_some()) {
                    return Ok(String::new());
                }
                #[cfg(target_os = "android")]
                if ota {
                    // 当前运行的内核仍属于旧槽位，而 OTA 载荷属于另一个槽位，
                    // 因此改为探测另一个槽位的 boot 镜像。
                    let slot_suffix = get_slot_suffix(true);
                    println!("- Detecting KMI from boot image");
                    return parse_kmi_from_boot(Path::new(&format!(
                        "/dev/block/by-name/boot{slot_suffix}"
                    )));
                }
                #[cfg(target_os = "android")]
                match get_current_kmi() {
                    Ok(value) => {
                        return Ok(value);
                    }
                    Err(e) => {
                        println!("- {e}");
                    }
                }
                Ok(if ramdisk {
                    // 裸 ramdisk 不包含内核或 header，无法探测。
                    bail!("please specify kmi manually")
                } else if let Some(image_path) = &image {
                    println!("- Detecting KMI from {}", image_path.display());
                    parse_kmi_from_boot(image_path)?
                } else if let Some(kernel_path) = &kernel {
                    println!("- Detecting KMI from {}", kernel_path.display());
                    parse_kmi_from_kernel(kernel_path)?
                } else {
                    String::new()
                })
            },
            Ok,
        )?;

        let boot_image_file = if let Some(image) = image {
            ensure!(image.exists(), "boot image not found");
            // 将 by-name 符号链接解析为真实设备节点，确保后续刷写与读取
            // 操作的是同一个对象。
            std::fs::canonicalize(image)?
        } else {
            #[cfg(target_os = "android")]
            {
                auto_boot_partition_path(&kmi, ota, is_replace_kernel, &partition)
            }
            #[cfg(not(target_os = "android"))]
            {
                bail!("Please specify a boot image");
            }
        };

        #[cfg(target_os = "android")]
        println!("- Boot device: {}", boot_image_file.display());

        // 尝试提取 bootctl/busybox
        // bootctl 只在 post_ota() 中需要；这里失败不能影响普通修补或刷写，
        // 因此故意忽略返回值。
        #[cfg(target_os = "android")]
        let _ = assets::ensure_binaries(false);

        println!("- Preparing assets");
        println!("- Unpacking boot image");
        let boot_image_data = map_file(&boot_image_file)?;
        let boot_image = if ramdisk {
            // AVD ramdisk 是没有 boot header 的压缩 cpio 数据块。
            BootImage::parse_raw_ramdisk(&boot_image_data)?
        } else {
            BootImage::parse(&boot_image_data)?
        };
        enforce_bootimage_version(&boot_image)?;

        let mut patcher = BootImagePatchOption::new(&boot_image);

        if let Some(cmdline_value) = &cmdline {
            // 使用固定大小的 cmdline 字段，长度受其容量限制。
            patcher.override_cmdline(cmdline_value.as_bytes());
            println!("- Setting cmdline to: {cmdline_value}");
        }

        if let Some(kernel_path) = kernel {
            println!("- Adding Kernel");
            let kernel_data = map_file(&kernel_path)?;
            // `false` 表示沿用源内核的压缩格式。
            patcher.replace_kernel(Box::new(Cursor::new(kernel_data)), false);
        }

        // 使用 --no-install 时不会注入载荷，空占位只是为了让各分支类型一致。
        // 否则优先使用 --module 指定的模块，默认则加载与 KMI 匹配的内置资源；
        // 在宿主机构建中，资源按架构分目录存放。
        let kernelsu_ko: Box<dyn AsRef<[u8]>> = if no_install {
            Box::new(Vec::<u8>::new())
        } else if let Some(kmod_path) = kmod {
            Box::new(map_file(&kmod_path)?)
        } else {
            #[cfg(target_os = "android")]
            {
                println!("- KMI: {kmi}");
                let name = format!("{kmi}_kernelsu.ko");
                assets::get_asset_boxed(&name).with_context(|| format!("Failed to load {name}"))?
            }
            #[cfg(not(target_os = "android"))]
            {
                println!("- KMI: {kmi}");
                println!("- Arch: {arch}");
                let name = format!("{arch}/{kmi}_kernelsu.ko");
                assets::get_asset_boxed(&name).with_context(|| format!("Failed to load {name}"))?
            }
        };

        // `ksuinit` 是替代的 /init：它加载内置 LKM、应用 ksu_config，然后
        // 执行原本的 init（已保存为 init.real）。
        let ksu_init: Box<dyn AsRef<[u8]>> = if no_install {
            Box::new(Vec::<u8>::new())
        } else if let Some(init_path) = init {
            Box::new(map_file(&init_path)?)
        } else {
            #[cfg(not(target_os = "android"))]
            {
                assets::get_asset_boxed(&format!("{arch}/ksuinit"))
                    .context("Failed to load ksuinit")?
            }
            #[cfg(target_os = "android")]
            {
                assets::get_asset_boxed("ksuinit").context("Failed to load ksuinit")?
            }
        };

        let (mut cpio, vendor_ramdisk_idx) =
            if let Some(ramdisk_image) = boot_image.get_blocks().get_ramdisk() {
                extract_ramdisk(ramdisk_image)?
            } else {
                // 部分镜像把 ramdisk 拆分到 init_boot 或 vendor_boot；即使
                // 当前镜像没有 ramdisk，空归档仍可用于注入载荷。
                println!("- No ramdisk, creating one");
                (Cpio::new(), None)
            };

        if !no_install {
            // 拒绝叠加在 Magisk 之上：两者都会替换 /init，并操作相同的
            // ramdisk 条目。
            ensure!(
                !cpio.is_magisk_patched(),
                "Cannot work with Magisk patched image"
            );

            println!("- Adding KernelSU LKM");
            let is_kernelsu_patched = cpio.exists("kernelsu.ko");

            // 只在首次修补时保留原始 init。重复修补时 init 已经是 ksuinit，
            // init.real 才是原厂 init，再次重命名会用 ksuinit 覆盖原厂程序。
            if !is_kernelsu_patched && cpio.exists("init") {
                cpio.mv("init", "init.real")?;
            }

            // 权限 0755：一阶段 init 需要可执行权限。
            cpio.add("init", CpioEntry::regular(0o755, ksu_init))?;
            cpio.add("kernelsu.ko", CpioEntry::regular(0o755, kernelsu_ko))?;

            #[cfg(target_os = "android")]
            // 在重新打包 ramdisk 之前备份，但仅限刷写此前未修补过的镜像，
            // 或用户显式指定 --backup 的情况。离线修补不需要备份，因为源镜像
            // 仍由用户保管。
            if (backup || (!is_kernelsu_patched && flash))
                && let Err(e) = do_backup(&mut cpio, &boot_image_file)
            {
                println!("! Backup of stock image failed: {e:?}");
            }
        }

        // ksu_config 是一个以空格分隔的配置项列表。先读取并保留已有项，
        // 使重复修补只改变本次显式指定的选项。
        let mut ksu_config: Vec<String> = cpio
            .entry_by_name("ksu_config")
            .and_then(CpioEntry::data)
            .and_then(|v| str::from_utf8(v).ok())
            .map_or_else(Vec::new, |v| {
                v.split(' ').map(std::borrow::ToOwned::to_owned).collect()
            });

        // 写入前先校验完整选项集，让过长值在这里报错，而不是之后被内核截断。
        config.validate()?;
        config.apply(&mut ksu_config);
        if let Some(bundled) = bundled_lkm {
            set_config_flag(&mut ksu_config, "bundled LKM", "bundled=1", bundled);
        }

        if ksu_config.is_empty() {
            // 无需配置；直接省略该文件，避免留下空条目。
            cpio.rm("ksu_config", false);
        } else {
            // 权限 0644：一阶段 init 只需读取，绝不执行。
            let data = ksu_config.join(" ").into_bytes();
            cpio.add("ksu_config", CpioEntry::regular(0o644, Box::new(data)))?;
        }

        // 移除旧版配置文件
        cpio.rm("allow_shell", false);

        if enable_adbd || adb_debug_prop.is_some() {
            println!("- Adding adb_debug props");
            // 空标记文件：一阶段 init 依据它的存在，在可调试构建中加载
            // /adb_debug.prop。
            cpio.add(
                "force_debuggable",
                CpioEntry::regular(0o644, Box::new(Vec::<u8>::new())),
            )?;

            let mut prop = Vec::<u8>::new();
            if enable_adbd {
                println!("- Adding props to enable adbd");
                prop.extend_from_slice(
                    b"ro.debuggable=1\nro.force.debuggable=1\nro.adb.secure=0\n",
                );
            }
            if let Some(extra) = adb_debug_prop {
                println!("- Adding custom props");
                prop.extend_from_slice(extra.as_bytes());
            }
            cpio.add("adb_debug.prop", CpioEntry::regular(0o644, Box::new(prop)))?;
        } else {
            // 未请求任何 adb 选项；删除上次修补留下的文件，使结果与本次请求
            // 的状态一致。
            if cpio.exists("force_debuggable") {
                println!("- Removing /force_debuggable");
                cpio.rm("force_debuggable", false);
            }
            if cpio.exists("adb_debug.prop") {
                println!("- Removing /adb_debug.prop");
                cpio.rm("adb_debug.prop", false);
            }
        }

        // 重新生成未压缩的 cpio；patcher 会按原镜像记录的格式重新压缩。
        let mut new_cpio = Vec::<u8>::new();
        cpio.dump(&mut new_cpio)?;

        // vendor_boot 只能替换具体的 ramdisk 表项；普通 boot 直接替换整个
        // ramdisk。第二个参数 false 表示沿用原压缩格式。
        if let Some(idx) = vendor_ramdisk_idx {
            patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
        } else {
            patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
        }

        println!("- Repacking boot image");
        let mut new_boot_buf = Cursor::new(Vec::<u8>::with_capacity(boot_image.get_size()));
        patcher.patch(&mut new_boot_buf)?;
        let new_boot_bytes = new_boot_buf.into_inner();

        // 尽早释放源 mmap，使 boot 分区不再处于只读映射状态，
        // 否则部分内核会拒绝后续的写入。
        drop(boot_image);
        drop(boot_image_data);

        #[cfg(target_os = "android")]
        if flash {
            println!("- Flashing new boot image");
            let bootdevice = boot_image_file.display().to_string();
            flash_partition(&bootdevice, &new_boot_bytes)?;
            if ota {
                post_ota()?;
            }
        }

        // 仅在离线修补、没有刷写，或显式指定输出时才写文件；刷写后不必再
        // 额外留下一份副本。
        #[cfg(target_os = "android")]
        let should_write_output = patch_file || !flash || out_name.is_some() || out.is_some();
        #[cfg(not(target_os = "android"))]
        let should_write_output = true;

        if should_write_output {
            let output_dir = out.unwrap_or(std::env::current_dir()?);
            let name = out_name.unwrap_or_else(|| {
                // 默认文件名带 UTC 时间戳，避免覆盖之前的输出。
                let now = chrono::Utc::now();
                format!("kernelsu_patched_{}.img", now.format("%Y%m%d_%H%M%S"))
            });
            let output_image = output_dir.join(name);
            std::fs::write(&output_image, &new_boot_bytes).context("write out new boot failed")?;
            println!("- Output file is written to");
            println!("- {}", output_image.display().to_string().trim_matches('"'));
        }

        println!("- Done!");
        Ok(())
    };

    // 统一在这里打印错误，调用方只需根据返回值处理退出状态。
    let result = inner();
    if let Err(ref e) = result {
        println!("- Patch Error: {e}");
    }
    result
}

/// `ksud boot-restore` 的命令行参数：源镜像（或自动探测的分区）、刷写开关
/// 以及输出目标。
#[derive(clap::Args, Debug)]
pub struct BootRestoreArgs {
    /// boot 镜像路径；未指定时会尝试自动查找 boot 镜像
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// 还原后刷写到 boot 分区
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = "false")]
    pub flash: bool,

    /// 输出路径。未指定时使用当前目录。
    /// 若指定了该路径，即使带有 --flash，
    /// boot 镜像也会写入该目录。
    #[cfg(target_os = "android")]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// 输出路径。未指定时使用当前目录。
    #[cfg(not(target_os = "android"))]
    #[arg(short, long, default_value = None)]
    pub out: Option<PathBuf>,

    /// 输出文件名。若指定了该值，即使带有 --flash，
    /// boot 镜像也会写入输出目录。
    #[cfg(target_os = "android")]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,

    /// 输出文件名。
    #[cfg(not(target_os = "android"))]
    #[arg(long, default_value = None)]
    pub out_name: Option<String>,
}

/// 将镜像还原为未打补丁的原厂状态。
///
/// 优先使用 ramdisk 中 `stock_image.sha1` 指向的备份文件，按字节原样写回；
/// 如果备份缺失（例如更换了设备或备份已被清理），则从当前 ramdisk 中移除
/// `kernelsu.ko`，并把 `init.real` 恢复为 `init` 后重新打包。
/// `--flash` 会直接把结果写入自动选择的分区。
pub fn restore(args: BootRestoreArgs) -> Result<()> {
    let BootRestoreArgs {
        boot: image,
        out_name,
        out,
        #[cfg(target_os = "android")]
        flash,
    } = args;

    // 自动选择分区时需要用 KMI 判断是否存在 init_boot；探测失败就回退为空
    // 字符串，只影响分区选择。
    #[cfg(target_os = "android")]
    let kmi = get_current_kmi().unwrap_or_default();

    #[cfg(target_os = "android")]
    let image_supplied = image.is_some();

    let boot_image_file = if let Some(image) = image {
        ensure!(image.exists(), "boot image not found");
        // 与 patch 相同：解析符号链接，确保后续写回的是同一设备节点。
        std::fs::canonicalize(image)?
    } else {
        #[cfg(target_os = "android")]
        {
            auto_boot_partition_path(&kmi, false, false, &None)
        }
        #[cfg(not(target_os = "android"))]
        {
            bail!("Please specify a boot image");
        }
    };

    #[cfg(target_os = "android")]
    println!("- Bootdevice: {}", boot_image_file.display());

    println!("- Unpacking boot image");
    let bootimage_data = map_file(&boot_image_file)?;
    let boot_image = BootImage::parse(&bootimage_data)?;
    enforce_bootimage_version(&boot_image)?;

    let (mut cpio, vendor_ramdisk_idx) =
        if let Some(ramdisk_image) = boot_image.get_blocks().get_ramdisk() {
            extract_ramdisk(ramdisk_image)?
        } else {
            bail!("No compatible ramdisk found.")
        };

    // ramdisk 中存在 LKM 才说明镜像确实被 KernelSU 修补过，避免误还原。
    ensure!(
        cpio.exists("kernelsu.ko"),
        "boot image is not patched by KernelSU"
    );

    #[cfg(target_os = "android")]
    let mut stock_boot: Option<PathBuf> = None;

    #[cfg(target_os = "android")]
    if let Some(backup_file) = cpio.entry_by_name(BACKUP_FILENAME) {
        // 备份标记的内容是十六进制 SHA-1 字符串；trim 用于兼容可能存在的空白。
        let sha = String::from_utf8(backup_file.data().unwrap_or_default().to_vec())?;
        let sha = sha.trim();
        let backup_path =
            PathBuf::from(KSU_BACKUP_DIR).join(format!("{KSU_BACKUP_FILE_PREFIX}{sha}"));
        if backup_path.is_file() {
            println!("- Using backup file {}", backup_path.display());
            stock_boot = Some(backup_path);
        } else {
            println!("- Warning: no backup {} found!", backup_path.display());
        }
        // 即使本次没有找到对应备份，也清理其他旧备份以释放空间。
        if let Err(e) = clean_backup(sha) {
            println!("- Warning: Cleanup backup image failed: {e}");
        }
    } else {
        println!("- Backup info is absent!");
    }

    #[cfg(target_os = "android")]
    let mut stock_source: Option<PathBuf> = None;

    let new_boot_bytes: Vec<u8> = {
        #[cfg(target_os = "android")]
        {
            // 有原厂备份时按字节原样写回最可靠；否则只能从当前镜像中移除
            // KernelSU 后重新打包。
            if let Some(stock_path) = stock_boot {
                let bytes = std::fs::read(&stock_path)
                    .with_context(|| format!("read stock boot {}", stock_path.display()))?;
                stock_source = Some(stock_path);
                bytes
            } else {
                rebuild_without_ksu(&boot_image, &mut cpio, vendor_ramdisk_idx)?
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            rebuild_without_ksu(&boot_image, &mut cpio, vendor_ramdisk_idx)?
        }
    };

    // 与 patch 相同：写块设备前先释放只读映射，否则部分内核会拒绝写入。
    drop(boot_image);
    drop(bootimage_data);

    #[cfg(target_os = "android")]
    if flash {
        if let Some(ref source) = stock_source {
            println!("- Flashing new boot image from {}", source.display());
        } else {
            println!("- Flashing new boot image");
        }
        let bootdevice = boot_image_file.display().to_string();
        flash_partition(&bootdevice, &new_boot_bytes)?;
    }

    // 与 patch 相同的输出条件：仅刷写且未显式要求输出时不写文件。
    #[cfg(target_os = "android")]
    let should_write_output = image_supplied || !flash || out_name.is_some() || out.is_some();
    #[cfg(not(target_os = "android"))]
    let should_write_output = true;

    if should_write_output {
        let output_dir = out.unwrap_or(std::env::current_dir()?);
        let name = out_name.unwrap_or_else(|| {
            // 默认文件名带 UTC 时间戳，避免覆盖之前的输出。
            let now = chrono::Utc::now();
            format!("kernelsu_restore_{}.img", now.format("%Y%m%d_%H%M%S"))
        });
        let output_image = output_dir.join(name);
        std::fs::write(&output_image, &new_boot_bytes).context("copy out new boot failed")?;
        println!("- Output file is written to");
        println!("- {}", output_image.display().to_string().trim_matches('"'));
    }

    println!("- Done!");
    Ok(())
}

/// 在没有原厂备份时的兜底还原：从 cpio 中删除 `kernelsu.ko`，若存在
/// `init.real` 则恢复为 `init`，然后按原镜像格式重新打包。
///
/// 这只能撤销 KernelSU 自己的条目；其他修改（例如 adb 调试配置）会保留。
fn rebuild_without_ksu(
    boot_image: &BootImage<'_>,
    cpio: &mut Cpio,
    vendor_ramdisk_idx: Option<usize>,
) -> Result<Vec<u8>> {
    println!("- Removing KernelSU from boot image");
    cpio.rm("kernelsu.ko", false);
    // 首次修补时原厂 init 被改名为 init.real；没有该条目说明镜像不是由
    // 本工具的标准流程修补的，此时只能保留现有 init。
    if cpio.exists("init.real") {
        cpio.mv("init.real", "init")?;
    }

    // 生成未压缩 cpio，由 patcher 沿用原镜像的压缩格式。
    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio)?;

    println!("- Repacking boot image");
    let mut patcher = BootImagePatchOption::new(boot_image);
    if let Some(idx) = vendor_ramdisk_idx {
        patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }

    let mut buf = Cursor::new(Vec::<u8>::with_capacity(boot_image.get_size()));
    patcher.patch(&mut buf)?;
    Ok(buf.into_inner())
}

/// `--block-module` 名称校验与 `ksu_config` 写入逻辑的单元测试。
#[cfg(test)]
mod block_modules_tests {
    use super::*;

    /// 合法模块名（字母、数字、下划线）应被原样接受。
    #[test]
    fn parse_block_module_accepts_valid_names() {
        assert_eq!(
            parse_block_module("oplus_secure_guard").unwrap(),
            "oplus_secure_guard"
        );
        assert_eq!(parse_block_module("vr").unwrap(), "vr");
        assert_eq!(parse_block_module("mkp0").unwrap(), "mkp0");
    }

    /// 空名、含连字符/空格以及超过 64 字节的名称都必须被拒绝。
    #[test]
    fn parse_block_module_rejects_invalid_names() {
        assert!(parse_block_module("").is_err());
        assert!(parse_block_module("bad-name").is_err());
        assert!(parse_block_module("a b").is_err());
        assert!(parse_block_module(&"a".repeat(65)).is_err());
    }

    /// 指定模块时写入 `block_modules=`，省略该选项时删除旧条目，且不动其他配置项。
    #[test]
    fn apply_writes_and_clears_block_modules() {
        let args = KsuConfigArgs {
            block_module: vec!["vr".to_owned(), "vklp".to_owned()],
            ..Default::default()
        };
        // 无关条目会被保留；apply() 只负责它自己管理的键。
        let mut config = vec!["bundled=1".to_owned()];
        args.apply(&mut config);
        assert!(config.contains(&"block_modules=vr,vklp".to_owned()));
        assert!(config.contains(&"bundled=1".to_owned()));

        // 省略该选项会删除之前写入的条目。
        let empty = KsuConfigArgs::default();
        empty.apply(&mut config);
        assert!(!config.iter().any(|v| v.starts_with("block_modules=")));
    }

    /// 重复修补时旧的 `block_modules=` 必须被整体替换，而不是追加多份。
    #[test]
    fn apply_replaces_existing_block_modules() {
        let args = KsuConfigArgs {
            block_module: vec!["vr".to_owned()],
            ..Default::default()
        };
        let mut config = vec!["block_modules=old,stale".to_owned()];
        args.apply(&mut config);
        assert_eq!(
            config
                .iter()
                .filter(|v| v.starts_with("block_modules="))
                .count(),
            1
        );
        assert!(config.contains(&"block_modules=vr".to_owned()));
    }

    /// 前缀相似但键名不同的条目（如 `block_modules_foo=`）不能被误删。
    #[test]
    fn apply_keeps_sibling_keys_with_shared_prefix() {
        // 仅以 "block_modules" 开头、但并非精确的 "block_modules=" 条目的键，
        // 不能被 retain() 删除。
        let args = KsuConfigArgs {
            block_module: vec!["vr".to_owned()],
            ..Default::default()
        };
        let mut config = vec!["block_modules_foo=1".to_owned()];
        args.apply(&mut config);
        assert!(config.contains(&"block_modules_foo=1".to_owned()));
        assert!(config.contains(&"block_modules=vr".to_owned()));
    }

    /// 拼接后超过内核 255 字节缓冲区的列表必须在主机端就报错，避免被静默截断。
    #[test]
    fn validate_rejects_overlong_list() {
        // 许多合法名称拼接后的长度会超过内核 255 字节的值预算，
        // 此时必须报错拒绝，而不是静默截断。
        let names: Vec<String> = (0..30).map(|i| format!("module_name_{i:03}")).collect();
        assert!(names.join(",").len() > MAX_BLOCK_MODULES_LEN);
        let args = KsuConfigArgs {
            block_module: names,
            ..Default::default()
        };
        assert!(args.validate().is_err());

        // 较短的列表可以接受。
        let ok = KsuConfigArgs {
            block_module: vec!["vr".to_owned(), "mkp".to_owned()],
            ..Default::default()
        };
        assert!(ok.validate().is_ok());
        assert!(KsuConfigArgs::default().validate().is_ok());
    }
}
