//! Preinit：把每个已启用模块的 `preinit/` 目录复制到 preinit 分区（/metadata）。下次开机时，
//! 内核内置的 runner 会在 SELinux 策略加载之前执行其中的 `preinit.conf`，
//! 参见 kernel/preinit/runner/runner.c。
//!
//! ksud 只负责准备内容并记录状态，是否执行由内核侧决定。这里写入的一切都是给下次开机用的，
//! 因此同步在 post-fs-data 阶段完成，不会触碰当前运行系统的文件。

use crate::{defs, restorecon::lsetfilecon, utils::ensure_dir_exists};

use anyhow::{Context, Result, bail};
use log::{info, warn};

use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    fmt::Write as _,
    fs::{self, File, Permissions},
    os::unix::fs::{MetadataExt, PermissionsExt, lchown},
    path::{Path, PathBuf},
};

// 空间按文件系统块计算
const FS_BLOCK_SIZE: u64 = 4096;
// preinit/ 下所有内容的总上限
const PREINIT_TOTAL_LIMIT: u64 = 4 << 20;
// 始终在 /metadata 上预留这么多空间：它还要存放 vold key 和 OTA 状态
const METADATA_RESERVE: u64 = 8 << 20;
// 必须与 runner 中的 BOOTCOUNT_LIMIT 保持一致
const BOOTCOUNT_LIMIT: u64 = 3;

// 写入状态文件的值；Manager 会把它们映射成用户可见的文案。`bootloop` 不会写在这里，
// 它是在读取状态时由启动计数推导出来的。
const STATE_OK: &str = "ok";
/// preinit 内容因磁盘空间不足而未能同步。
const STATE_NO_SPACE: &str = "no_space";
/// 扫描或复制 preinit 内容时发生错误。
const STATE_ERROR: &str = "error";
/// 由启动计数推导出的状态：连续多次开机失败后 preinit 被内核跳过。
const STATE_BOOTLOOP: &str = "bootloop";
/// 当前内核不支持执行 preinit。
const STATE_UNSUPPORTED: &str = "unsupported";

/// 模块 preinit 目录中的一个文件或目录。
struct PreinitEntry {
    /// 相对模块 `preinit/` 目录的路径；扫描、清单和暂存复制都以它为基准。
    rel: PathBuf,
    /// 是否为目录；目录本身不承载内容，复制时只按一个文件系统块计入预算。
    is_dir: bool,
    /// 普通文件的字节数；目录固定记为 0，避免把目录项大小混进内容预算。
    size: u64,
    // 用 ctime 而非 mtime：解压会保留 zip 里的 mtime，但 ctime 总会被设置
    ctime: (i64, i64),
    /// 从元数据取出的 Unix 权限位（已截断到 0o7777），安装时再收紧到 0o755。
    mode: u32,
}

impl PreinitEntry {
    /// 生成清单中的一行：`id<TAB>类型<TAB>大小<TAB>ctime<TAB>权限<TAB>相对路径`。
    ///
    /// 清单只是 ksud 自己的记录：把本次生成的行与上次的文件对比，就能在不重新读取每个文件的情况下
    /// 判断模块内容是否发生变化。
    fn manifest_line(&self, id: &str) -> String {
        format!(
            "{id}\t{}\t{}\t{}.{}\t{:o}\t{}",
            if self.is_dir { 'd' } else { 'f' },
            self.size,
            self.ctime.0,
            self.ctime.1,
            self.mode,
            self.rel.display()
        )
    }
}

/// 单个模块的处理结果，序列化到 `PREINIT_STATUS_PATH` 供 `ksud module list` 和 Manager 使用。
struct ModuleStatus {
    /// 模块 id，对应 `/data/adb/modules/<id>` 下的目录名，也是状态文件的行首字段。
    id: String,
    /// `STATE_*` 常量之一；`STATE_BOOTLOOP` 只在读取时合成，不会写入文件。
    state: &'static str,
    /// 自由格式的细节，成功时为空。
    message: String,
}

/// 向上取整到文件系统块，因为实际占用就是按块计算的。
const fn round_up(size: u64) -> u64 {
    size.div_ceil(FS_BLOCK_SIZE) * FS_BLOCK_SIZE
}

/// 递归收集 `root/rel` 下的条目到 `out`，顺序稳定（已排序）。
///
/// 顺序很重要，因为清单行会与上一次的结果逐行对比。符号链接和特殊文件直接拒绝：内核 runner
/// 不会处理它们。
fn scan_tree(root: &Path, rel: &Path, out: &mut Vec<PreinitEntry>) -> Result<()> {
    let dir = root.join(rel);
    let mut entries: Vec<_> = fs::read_dir(&dir)
        .with_context(|| format!("Failed to read {}", dir.display()))?
        .flatten()
        .collect();
    entries.sort_by_key(fs::DirEntry::file_name);
    for entry in entries {
        let rel = rel.join(entry.file_name());
        let meta = entry.metadata()?;
        let file_type = meta.file_type();
        if !file_type.is_dir() && !file_type.is_file() {
            bail!("unsupported file type: {}", rel.display());
        }
        out.push(PreinitEntry {
            rel: rel.clone(),
            is_dir: file_type.is_dir(),
            size: if file_type.is_file() { meta.len() } else { 0 },
            ctime: (meta.ctime(), meta.ctime_nsec()),
            mode: meta.mode() & 0o7777,
        });
        if file_type.is_dir() {
            scan_tree(root, &rel, out)?;
        }
    }
    Ok(())
}

/// 复制所需的磁盘空间，包含模块目录本身。
fn copy_usage(entries: &[PreinitEntry]) -> u64 {
    FS_BLOCK_SIZE
        + entries
            .iter()
            .map(|e| {
                if e.is_dir {
                    FS_BLOCK_SIZE
                } else {
                    round_up(e.size)
                }
            })
            .sum::<u64>()
}

/// `path` 下当前已分配的空间。
fn disk_usage(path: &Path) -> u64 {
    let Ok(meta) = fs::symlink_metadata(path) else {
        return 0;
    };
    // `st_blocks` 以 512 字节为单位，与文件系统块大小无关。
    let mut total = meta.blocks() * 512;
    if meta.is_dir()
        && let Ok(entries) = fs::read_dir(path)
    {
        total += entries
            .flatten()
            .map(|e| disk_usage(&e.path()))
            .sum::<u64>();
    }
    total
}

/// `path` 所在文件系统的可用字节数。
///
/// 使用 `f_bavail` 而不是 `f_bfree`，以避开 root 保留块，与普通写入者实际能分配的空间一致。
fn available_bytes(path: &Path) -> Result<u64> {
    let fs_stat = rustix::fs::statvfs(path)
        .with_context(|| format!("Failed to statvfs {}", path.display()))?;
    Ok(fs_stat.f_bavail * fs_stat.f_frsize)
}

/// runner 会拒绝任何非 root 所有、或对 group/other 可写的文件。
///
/// 权限统一与 0o755 相与：无论模块带来什么，setuid/setgid/sticky 位以及 group/other 的写权限
/// 都会被去掉。设置标签失败只告警，因为文件内容本身是对的，后续 restorecon 还能补上标签。
fn secure_path(path: &Path, mode: u32, con: &str) -> Result<()> {
    lchown(path, Some(0), Some(0))
        .with_context(|| format!("Failed to chown {}", path.display()))?;
    fs::set_permissions(path, Permissions::from_mode(mode & 0o755))
        .with_context(|| format!("Failed to chmod {}", path.display()))?;
    if let Err(e) = lsetfilecon(path, con) {
        warn!("{e:#}");
    }
    Ok(())
}

/// 对目录做 fsync，使其中新建或重命名的条目在掉电后仍然存在。
///
/// 文件由调用者单独同步；这里只保证目录元数据落盘。
fn sync_dir(path: &Path) {
    if let Err(e) = File::open(path).and_then(|f| f.sync_all()) {
        warn!("Failed to sync {}: {e}", path.display());
    }
}

/// 先把模块复制到 `.<id>.tmp`，再将其替换为 `<id>`。
///
/// 暂存目录以点开头，因为内核 runner 会忽略隐藏条目，所以同步在任何时刻中断都不会留下可执行的
/// 半成品模块。
fn install_module_copy(
    preinit_dir: &Path,
    id: &str,
    src: &Path,
    entries: &[PreinitEntry],
    con: &str,
) -> Result<()> {
    // 上次异常退出留下的残留，先清理干净。
    let tmp = preinit_dir.join(format!(".{id}.tmp"));
    if tmp.exists() {
        fs::remove_dir_all(&tmp)?;
    }
    fs::create_dir(&tmp).with_context(|| format!("Failed to create {}", tmp.display()))?;
    secure_path(&tmp, 0o755, con)?;

    for entry in entries {
        let to = tmp.join(&entry.rel);
        if entry.is_dir {
            fs::create_dir(&to).with_context(|| format!("Failed to create {}", to.display()))?;
        } else {
            let from = src.join(&entry.rel);
            fs::copy(&from, &to).with_context(|| format!("Failed to copy {}", from.display()))?;
            // 在下面的重命名之前逐个同步文件；目录同步放在后面统一做。
            File::open(&to)?.sync_all()?;
        }
        secure_path(&to, entry.mode, con)?;
    }
    // 先同步子目录再同步父目录，确保整棵树在对外可见前已经落盘。
    for entry in entries.iter().filter(|e| e.is_dir) {
        sync_dir(&tmp.join(&entry.rel));
    }
    sync_dir(&tmp);

    let dst = preinit_dir.join(id);
    // 先把现有副本挪到一边而不是直接删除，这样第二次重命名失败也不会同时丢掉新旧两份；
    // 新副本就位后再删掉旧副本。
    let old = preinit_dir.join(format!(".{id}.old"));
    if dst.exists() {
        if old.exists() {
            fs::remove_dir_all(&old)?;
        }
        fs::rename(&dst, &old)?;
    }
    fs::rename(&tmp, &dst)?;
    sync_dir(preinit_dir);
    if old.exists() {
        fs::remove_dir_all(&old)?;
    }
    Ok(())
}

/// 判断 `dst` 是否已与 `install` 将要复制的内容完全一致：对模块文件做
/// chmod、chcon 会改变 ctime，但不应被视为内容更新。
fn same_content(dst: &Path, src: &Path, entries: &[PreinitEntry]) -> bool {
    let mut existing = Vec::new();
    if scan_tree(dst, Path::new(""), &mut existing).is_err() || existing.len() != entries.len() {
        return false;
    }
    entries.iter().zip(&existing).all(|(want, have)| {
        want.rel == have.rel
            && want.is_dir == have.is_dir
            && want.mode & 0o755 == have.mode
            && (want.is_dir
                || (want.size == have.size
                    && matches!(
                        (fs::read(src.join(&want.rel)), fs::read(dst.join(&have.rel))),
                        (Ok(a), Ok(b)) if a == b
                    )))
    })
}

/// 把 `sync_candidates` 上次写下的清单解析成 `id -> 原始行`。
///
/// 清单缺失或无法读取时返回空 map，这只会让所有模块看起来都变了；`sync_candidates` 里的比较
/// 随后会退回到 `same_content`。
fn read_manifest(path: &Path) -> HashMap<String, Vec<String>> {
    let mut manifest: HashMap<String, Vec<String>> = HashMap::new();
    let Ok(content) = fs::read_to_string(path) else {
        return manifest;
    };
    for line in content.lines() {
        // 模块 id 是第一个以制表符分隔的字段（见 `PreinitEntry::manifest_line`）。
        if let Some((id, _)) = line.split_once('\t') {
            manifest
                .entry(id.to_owned())
                .or_default()
                .push(line.to_owned());
        }
    }
    manifest
}

/// 先写临时文件再重命名，保证读取者永远看不到写了一半的文件。
fn write_atomic(path: &Path, content: &str) -> Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content).with_context(|| format!("Failed to write {}", tmp.display()))?;
    File::open(&tmp)?.sync_all()?;
    fs::rename(&tmp, path).with_context(|| format!("Failed to rename to {}", path.display()))?;
    Ok(())
}

/// 把每个模块的状态行（`id state message`）写入文件，供之后查询。
///
/// 这只是诊断信息，写入失败仅记录日志，不会让同步失败。
fn write_status(statuses: &[ModuleStatus]) {
    let mut content = String::new();
    for status in statuses {
        let _ = writeln!(content, "{} {} {}", status.id, status.state, status.message);
    }
    let result = ensure_dir_exists(defs::WORKING_DIR)
        .and_then(|()| write_atomic(Path::new(defs::PREINIT_STATUS_PATH), &content));
    if let Err(e) = result {
        warn!("Failed to write preinit status: {e:#}");
    }
}

/// 尽力删除文件或目录；返回是否真的删除成功。
fn remove_entry(path: &Path) -> bool {
    let removed = if path.is_dir() {
        fs::remove_dir_all(path)
    } else {
        fs::remove_file(path)
    };
    if let Err(e) = &removed {
        warn!("Failed to remove {}: {e}", path.display());
    }
    removed.is_ok()
}

/// 清空 `ksu_dir` 下所有 preinit 状态（副本和启动计数）。
///
/// 用于旧版本探测顺序留下的过期 preinit 目录，以及当前内核根本不支持 preinit 的情况。
fn remove_ksu_preinit(ksu_dir: &Path) {
    let preinit_dir = ksu_dir.join(defs::PREINIT_SUBDIR);
    if preinit_dir.exists()
        && let Err(e) = fs::remove_dir_all(&preinit_dir)
    {
        warn!("Failed to remove {}: {e}", preinit_dir.display());
    }
    fs::remove_file(ksu_dir.join(defs::PREINIT_BOOTCOUNT_FILE)).ok();
}

/// 让 `<ksu_dir>/preinit/` 与 `modules` 中各模块的 `preinit/` 目录保持一致（id -> 模块目录，
/// 仅包含下次开机启用的模块）。未变化的模块保持原样，以减少对 /metadata 的写入。
pub fn sync(
    ksu_dir: &Path,
    stale_ksu_dirs: &[&Path],
    modules: &BTreeMap<String, PathBuf>,
    con: &str,
    supported: bool,
) -> Result<()> {
    // 内核会按顺序探测多个 preinit 位置；如果本次选中的目录与上次不同，就清掉旧目录里的副本，
    // 避免降级后复活过期模块。
    for stale in stale_ksu_dirs {
        remove_ksu_preinit(stale);
    }

    // 没有任何东西会执行这些副本：保持 /metadata 干净，并说明原因。
    if !supported {
        remove_ksu_preinit(ksu_dir);
        let statuses: Vec<ModuleStatus> = modules
            .iter()
            .filter(|(_, dir)| dir.join(defs::MODULE_PREINIT_DIR).is_dir())
            .map(|(id, _)| ModuleStatus {
                id: id.clone(),
                state: STATE_UNSUPPORTED,
                message: "kernel does not run preinit".to_owned(),
            })
            .collect();
        write_status(&statuses);
        return Ok(());
    }

    let mut statuses = Vec::new();
    let mut candidates = Vec::new();
    for (id, module_dir) in modules {
        let src = module_dir.join(defs::MODULE_PREINIT_DIR);
        if !src.is_dir() {
            continue;
        }
        let mut entries = Vec::new();
        // preinit.conf 是 runner 的入口脚本，没有它的目录不会产生任何效果。
        let scanned = if src.join(defs::PREINIT_CONF).is_file() {
            scan_tree(&src, Path::new(""), &mut entries)
        } else {
            Err(anyhow::anyhow!("{} not found", defs::PREINIT_CONF))
        };
        match scanned {
            Ok(()) => candidates.push((id.clone(), src, entries)),
            Err(e) => statuses.push(ModuleStatus {
                id: id.clone(),
                state: STATE_ERROR,
                message: format!("{e:#}"),
            }),
        }
    }

    if candidates.is_empty() {
        remove_ksu_preinit(ksu_dir);
        write_status(&statuses);
        return Ok(());
    }

    let mut changed = false;
    let result = sync_candidates(ksu_dir, con, &candidates, &mut statuses, &mut changed);
    if let Err(e) = &result {
        // 尚未完成处理的模块都不会按预期运行
        for (id, _, _) in &candidates {
            if !statuses.iter().any(|existing| &existing.id == id) {
                statuses.push(ModuleStatus {
                    id: id.clone(),
                    state: STATE_ERROR,
                    message: format!("{e:#}"),
                });
            }
        }
    }

    // 内容有更新，应重新给予若干次尝试机会
    if changed {
        fs::remove_file(ksu_dir.join(defs::PREINIT_BOOTCOUNT_FILE)).ok();
        sync_dir(ksu_dir);
    }

    statuses.sort_by(|a, b| a.id.cmp(&b.id));
    write_status(&statuses);
    result
}

/// 在可用空间内挑选候选模块，安装内容有变化的模块，并重写清单和状态。
///
/// 候选按模块 id 顺序传入（来自 `sync` 里的 `BTreeMap`），因此每次开机选中的集合是确定的。
fn sync_candidates(
    ksu_dir: &Path,
    con: &str,
    candidates: &[(String, PathBuf, Vec<PreinitEntry>)],
    statuses: &mut Vec<ModuleStatus>,
    changed: &mut bool,
) -> Result<()> {
    let preinit_dir = ksu_dir.join(defs::PREINIT_SUBDIR);

    // ksud 以 umask 0 运行；这里收紧旧版本创建内容的权限
    secure_path(ksu_dir, 0o755, con)?;
    ensure_dir_exists(&preinit_dir)?;
    secure_path(&preinit_dir, 0o755, con)?;

    let manifest_path = preinit_dir.join(defs::PREINIT_MANIFEST_FILE);
    let old_manifest = read_manifest(&manifest_path);

    // 挑选放得下的模块，并把每个已存在的副本都视为可回收空间
    let free = available_bytes(ksu_dir)? + disk_usage(&preinit_dir);
    // 既限制总量，也为共享同一 /metadata 文件系统的 vold key/OTA 状态留出 METADATA_RESERVE。
    let budget = PREINIT_TOTAL_LIMIT.min(free.saturating_sub(METADATA_RESERVE));
    // preinit/ 目录本身和清单文件
    let mut used = 2 * FS_BLOCK_SIZE;
    let mut fit = Vec::new();
    for (id, src, entries) in candidates {
        let need = copy_usage(entries);
        if used + need > budget {
            info!(
                "preinit: {id} needs {need} bytes, {} left",
                budget.saturating_sub(used)
            );
            statuses.push(ModuleStatus {
                id: id.clone(),
                state: STATE_NO_SPACE,
                message: format!("need {need}, available {}", budget.saturating_sub(used)),
            });
            continue;
        }
        used += need;
        fit.push((id, src, entries, need));
    }

    // 释放已消失、未能放入或复制中途被打断的模块所占空间
    let wanted: BTreeSet<&str> = fit.iter().map(|(id, ..)| id.as_str()).collect();
    remove_unlisted(&preinit_dir, &wanted, changed)?;

    let mut keep = BTreeSet::new();
    let mut manifest = String::new();
    for (id, src, entries, need) in fit {
        let lines: Vec<String> = entries.iter().map(|e| e.manifest_line(id)).collect();
        let dst = preinit_dir.join(id);
        // 清单命中时开销很小；`same_content` 用于清单缺失或过期的情况（例如从旧版 ksud 升级后的
        // 第一次运行）。
        let unchanged = dst.is_dir()
            && (old_manifest.get(id.as_str()) == Some(&lines) || same_content(&dst, src, entries));
        if !unchanged {
            // 预算把旧副本视为可回收空间，但 install_module_copy() 会先写入新副本：
            // 两者同时存在会侵占预留空间时，先删掉旧副本。
            if available_bytes(ksu_dir)? < need + METADATA_RESERVE && dst.exists() {
                remove_entry(&dst);
                *changed = true;
            }
            let avail = available_bytes(ksu_dir)?;
            if avail < need + METADATA_RESERVE {
                statuses.push(ModuleStatus {
                    id: id.clone(),
                    state: STATE_NO_SPACE,
                    message: format!(
                        "need {need}, available {}",
                        avail.saturating_sub(METADATA_RESERVE)
                    ),
                });
                continue;
            }
            if let Err(e) = install_module_copy(&preinit_dir, id, src, entries, con) {
                warn!("preinit: install {id} failed: {e:#}");
                statuses.push(ModuleStatus {
                    id: id.clone(),
                    state: STATE_ERROR,
                    message: format!("{e:#}"),
                });
                continue;
            }
            info!("preinit: synced {id}");
            *changed = true;
        }

        keep.insert(id.as_str());
        for line in lines {
            manifest.push_str(&line);
            manifest.push('\n');
        }
        statuses.push(ModuleStatus {
            id: id.clone(),
            state: STATE_OK,
            message: String::new(),
        });
    }

    // 丢弃安装失败的模块
    remove_unlisted(&preinit_dir, &keep, changed)?;

    write_atomic(&manifest_path, &manifest)?;
    if let Err(e) = lsetfilecon(&manifest_path, con) {
        warn!("{e:#}");
    }
    sync_dir(&preinit_dir);
    Ok(())
}

/// 删除 `preinit_dir` 下除清单文件和 `keep` 中模块之外的所有内容。
fn remove_unlisted(preinit_dir: &Path, keep: &BTreeSet<&str>, changed: &mut bool) -> Result<()> {
    for entry in fs::read_dir(preinit_dir)?.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name == defs::PREINIT_MANIFEST_FILE || keep.contains(name.as_ref()) {
            continue;
        }
        if remove_entry(&entry.path()) && !name.starts_with('.') {
            // 以点开头的名字是暂存残留（`.id.tmp`、`.id.old`）或清单文件，删除它们并不改变
            // runner 实际会执行的内容。
            info!("preinit: removed {name}");
            *changed = true;
        }
    }
    Ok(())
}

/// 与内核 runner 共享的启动计数文件路径（对应 runner.c 里的 `BOOTCOUNT_NAME`）。
fn bootcount_path() -> PathBuf {
    Path::new(crate::module::preinit_ksu_dir()).join(defs::PREINIT_BOOTCOUNT_FILE)
}

/// 读取启动计数；文件缺失或内容非法时按 0 处理。
fn read_bootcount() -> u64 {
    fs::read_to_string(bootcount_path())
        .ok()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or(0)
}

/// 由于之前的开机未能完成，本次开机跳过了 preinit。
fn disabled_by_bootloop() -> bool {
    read_bootcount() > BOOTCOUNT_LIMIT
}

/// 开机完成：本次运行的 preinit 内容没有问题。计数器会停在原值，这样
/// 导致开机失败的模块不会在每隔一次开机时被重试。
pub fn on_boot_completed() {
    let count = read_bootcount();
    if count > BOOTCOUNT_LIMIT {
        warn!(
            "preinit was disabled after {BOOTCOUNT_LIMIT} failed boots, run `ksud module preinit reset` to retry"
        );
        return;
    }
    // 正常开机说明当前内容可以运行，清除失败计数。停住的值（`LIMIT + 1`）不会在这里清除，
    // 因此 preinit 会一直保持关闭，直到显式重置。
    if count > 0 {
        fs::remove_file(bootcount_path()).ok();
    }
}

/// 清除启动计数，使下次开机重新运行 preinit（即使之前因 bootloop 被停用）。
pub fn reset_bootcount() -> Result<()> {
    let path = bootcount_path();
    if path.exists() {
        fs::remove_file(&path).with_context(|| format!("Failed to remove {}", path.display()))?;
    }
    Ok(())
}

/// 各模块在下次开机时的 preinit 状态：ok、no_space、error 或 bootloop。
pub fn module_states() -> HashMap<String, String> {
    let bootloop = disabled_by_bootloop();
    let Ok(content) = fs::read_to_string(defs::PREINIT_STATUS_PATH) else {
        return HashMap::new();
    };
    content
        .lines()
        .filter_map(|line| {
            let mut parts = line.splitn(3, ' ');
            let id = parts.next()?;
            let state = parts.next()?;
            // 只把本应真正运行过的模块报告为 bootloop；error/no_space 的模块从未把内容放到位。
            let state = if bootloop && state == STATE_OK {
                STATE_BOOTLOOP
            } else {
                state
            };
            Some((id.to_owned(), state.to_owned()))
        })
        .collect()
}

/// 在上次同步写出的状态文件里查某个模块的 `(状态, 消息)`。
///
/// 消息本身可能含空格，所以用 `splitn(3, ' ')`。
pub fn status_of(id: &str) -> Option<(String, String)> {
    let content = fs::read_to_string(defs::PREINIT_STATUS_PATH).ok()?;
    content.lines().find_map(|line| {
        let mut parts = line.splitn(3, ' ');
        (parts.next()? == id).then(|| {
            (
                parts.next().unwrap_or_default().to_owned(),
                parts.next().unwrap_or_default().trim().to_owned(),
            )
        })
    })
}

/// 打印启动计数和原始状态文件（`ksud module preinit status`）。
pub fn print_status() {
    let count = read_bootcount();
    if count > BOOTCOUNT_LIMIT {
        println!(
            "preinit: disabled after {BOOTCOUNT_LIMIT} failed boots (reset with `ksud module preinit reset`)"
        );
    } else {
        println!("preinit: bootcount {count}/{BOOTCOUNT_LIMIT}");
    }
    let content = fs::read_to_string(defs::PREINIT_STATUS_PATH).unwrap_or_default();
    print!("{content}");
}
