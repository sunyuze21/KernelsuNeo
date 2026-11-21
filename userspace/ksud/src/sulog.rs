//! 内核侧 su 事件日志（sulog）的消费端。
//!
//! 内核把执行、sucompat 重定向和授权 root 事件放入队列；本模块通过匿名
//! inode fd 排空队列，把每个事件转义成一行追加到按天编号的日志文件。
//! `run_sulogd` 持有该 fd 并按需重启会话，`ensure_sulogd_running` 在功能
//! 开启时拉起守护进程；保留天数与单文件大小是用户可编辑的模块配置。

use anyhow::{Context, Result, bail, ensure};
use chrono::{Days, Local, NaiveDate};
use std::fmt::Write as FmtWrite;
use std::fs::{self, DirBuilder, File, OpenOptions, Permissions};
use std::io::{self, ErrorKind, LineWriter, Write};
use std::mem::size_of;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::Duration;

use crate::{defs, ksucalls, module_config, utils};

// 与 kernel/infra/event_queue.h 共享的协议常量。
const KSU_EVENT_QUEUE_TYPE_DROPPED: u16 = u16::MAX;
const KSU_EVENT_RECORD_FLAG_INTERNAL: u16 = 1;
// 与 uapi/sulog.h 中的 TASK_COMM_LEN 保持一致。
const TASK_COMM_LEN: usize = 16;
// 大于内核单条最大记录（24 字节头 + 2 KiB 负载），保证一次 read 拿到的是
// 完整记录。
const READ_BUF_SIZE: usize = 8192;
const SULOGD_RESTART_DELAY: Duration = Duration::from_secs(3);
// 日志包含命令行，因此目录和文件都必须保持私有。
const SULOG_DIR_MODE: u32 = 0o700;
const SULOG_FILE_MODE: u32 = 0o600;
/// 保存 sulogd 设置的模块配置命名空间。
pub const SULOG_CONFIG_MODULE_ID: &str = "internal.ksud.sulogd";
const SULOG_RETENTION_CONFIG_KEY: &str = "log.retention.days";
const SULOG_MAX_FILE_SIZE_CONFIG_KEY: &str = "log.max_file_size";
const DEFAULT_SULOG_RETENTION_DAYS: u64 = 3;
const DEFAULT_SULOG_MAX_FILE_SIZE: u64 = 10 * 1024 * 1024;

/// 事件队列中每条记录前面的头部。
///
/// 布局（包括 packed）对应 `struct ksu_event_record_hdr`；`payload_len` 只
/// 统计头部之后的字节数。
#[repr(C, packed)]
#[derive(Clone, Copy, Debug)]
struct EventRecordHeader {
    record_type: u16,
    flags: u16,
    payload_len: u32,
    seq: u64,
    ts_ns: u64,
}

/// 内核因丢弃事件而发出的内部记录。
///
/// `first_seq`/`last_seq` 标出丢失的序号区间，日志中的序号缺口因此可以
/// 解释清楚，而无需猜测。
#[repr(C, packed)]
#[derive(Clone, Copy, Debug)]
struct DroppedInfo {
    dropped: u64,
    first_seq: u64,
    last_seq: u64,
}

/// sulog 事件的固定部分，后面紧跟 `filename_len` + `argv_len` 字节。
///
/// 对应 uapi/sulog.h 中的 `struct ksu_sulog_event`；两个长度字段描述存放在
/// 该结构之后、以 NUL 结尾的字符串。
#[repr(C, packed)]
#[derive(Clone, Copy, Debug)]
struct SulogEventHeader {
    version: u16,
    event_type: u16,
    retval: i32,
    pid: u32,
    tgid: u32,
    ppid: u32,
    uid: u32,
    euid: u32,
    comm: [u8; TASK_COMM_LEN],
    filename_len: u32,
    argv_len: u32,
}

/// 解析完成的事件，变长字符串已解码。
#[derive(Clone, Debug)]
struct SulogEvent {
    version: u16,
    event_type: u16,
    retval: i32,
    pid: u32,
    tgid: u32,
    ppid: u32,
    uid: u32,
    euid: u32,
    comm: String,
    file: String,
    argv: String,
}

/// 排空一次队列后的状态。
enum ReadState {
    /// fd 暂时读空（EAGAIN），等待下一次 poll。
    Drained,
    /// 内核关闭了队列（read 返回 0），会话需要重启。
    Closed,
}

/// 会话结束的原因，用于在重启前选择日志文案。
enum SessionExitReason {
    FdClosed,
    EpollHangup,
}

/// 单实例 sulogd 的独占所有权标记。
struct SulogdLockGuard {
    _lock_file: File,
}

/// 按天切分、按大小滚动的追加写入器。
struct DailyLogWriter {
    current_day: String,
    current_index: u32,
    current_size: u64,
    max_file_size: u64,
    writer: LineWriter<File>,
}

impl EventRecordHeader {
    /// 从记录开头解码头部。
    fn parse(bytes: &[u8]) -> Result<Self> {
        read_packed_struct(bytes)
    }
}

impl DroppedInfo {
    /// 解码丢事件通知的负载。
    fn parse(bytes: &[u8]) -> Result<Self> {
        read_packed_struct(bytes)
    }
}

impl SulogEventHeader {
    /// 解码固定的事件头。
    fn parse(bytes: &[u8]) -> Result<Self> {
        read_packed_struct(bytes)
    }
}

impl SulogEvent {
    /// 解码完整事件，包括变长字符串。
    ///
    /// 内核发送的布局是 `fixed header | filename | argv`，头部的两个长度都
    /// 包含各自的 NUL 终止符。这里用 `checked_add` 校验长度之和必须恰好等于
    /// 负载长度，从而拒绝畸形队列项而不是越界读取。
    fn parse(payload: &[u8]) -> Result<Self> {
        let header = SulogEventHeader::parse(payload)?;
        let fixed_len = size_of::<SulogEventHeader>();
        let comm = parse_c_string(&header.comm);
        let filename_len =
            usize::try_from(header.filename_len).context("filename length overflow")?;
        let argv_len = usize::try_from(header.argv_len).context("argv length overflow")?;
        let version = header.version;
        let event_type = header.event_type;
        let retval = header.retval;
        let process_id = header.pid;
        let tgid = header.tgid;
        let parent_process_id = header.ppid;
        let uid = header.uid;
        let euid = header.euid;

        let variable_len = filename_len
            .checked_add(argv_len)
            .context("sulog variable payload length overflow")?;
        let total_len = fixed_len
            .checked_add(variable_len)
            .context("sulog event length overflow")?;
        ensure!(
            total_len == payload.len(),
            "sulog payload length mismatch: expected {total_len}, got {}",
            payload.len()
        );

        let file = parse_c_string(&payload[fixed_len..fixed_len + filename_len]);
        let argv =
            parse_c_string(&payload[fixed_len + filename_len..fixed_len + filename_len + argv_len]);

        Ok(Self {
            version,
            event_type,
            retval,
            pid: process_id,
            tgid,
            ppid: parent_process_id,
            uid,
            euid,
            comm,
            file,
            argv,
        })
    }

    /// 日志行中可读的事件名。
    ///
    /// 数字对应 `enum ksu_sulog_event_type`；未知值保留为 `unknown`，这样
    /// 新版内核产生的事件仍能写出可解析的行。
    const fn event_name(&self) -> &'static str {
        match self.event_type {
            1 => "root_execve",
            2 => "sucompat",
            3 => "ioctl_grant_root",
            _ => "unknown",
        }
    }
}

impl SulogdLockGuard {
    /// 尝试成为唯一运行的 sulogd。
    ///
    /// 已有实例持锁时返回 `None`。锁文件位于私有的 ksud 工作目录，创建模式
    /// 与日志文件相同（0600）。
    fn acquire() -> Result<Option<Self>> {
        ensure_private_dir_exists(Path::new(defs::WORKING_DIR))?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(SULOG_FILE_MODE)
            .open(defs::SULOGD_LOCK_PATH)
            .with_context(|| format!("failed to open {}", defs::SULOGD_LOCK_PATH))?;

        if try_lock_file(&file)? {
            return Ok(Some(Self { _lock_file: file }));
        }

        Ok(None)
    }
}

impl DailyLogWriter {
    /// 准备日志目录、加载配置并打开当天的文件。
    ///
    /// 这里以及跨天时都会清理过期日志，这样长期运行的守护进程也能响应通过
    /// 配置修改的保留策略。
    fn open() -> Result<Self> {
        ensure_private_dir_exists(Path::new(defs::LOG_DIR))?;
        let config = ensure_sulog_config()?;
        cleanup_expired_logs(config.retention_days)?;
        let current_day = current_log_day();
        let (current_index, current_size, writer) =
            open_log_writer_for_day(&current_day, config.max_file_size)?;
        Ok(Self {
            current_day,
            current_index,
            current_size,
            max_file_size: config.max_file_size,
            writer,
        })
    }

    /// 必要时切换到新的一天或带序号的文件。
    ///
    /// `next_write_len` 包含行尾换行符。跨天时会重新读取配置，因此保留/大小
    /// 修改无需重启即可生效。只有当前文件已有数据时才会滚动，避免在限制很小
    /// 时创建空的 `-1` 文件。
    fn rotate_if_needed(&mut self, next_write_len: usize) -> Result<()> {
        let current_day = current_log_day();
        let next_write_len = u64::try_from(next_write_len).context("invalid log line length")?;
        if current_day != self.current_day {
            let config = ensure_sulog_config()?;
            cleanup_expired_logs(config.retention_days)?;
            let (current_index, current_size, writer) =
                open_log_writer_for_day(&current_day, config.max_file_size)?;
            self.writer = writer;
            self.current_day = current_day;
            self.current_index = current_index;
            self.current_size = current_size;
            self.max_file_size = config.max_file_size;
            return Ok(());
        }

        if self.current_size > 0
            && self.current_size.saturating_add(next_write_len) > self.max_file_size
        {
            self.current_index = self.current_index.saturating_add(1);
            let path = daily_log_path(&self.current_day, self.current_index);
            self.writer = open_line_writer(&path)?;
            self.current_size = 0;
        }
        Ok(())
    }
}

/// 解码以 NUL 结尾的字节串。
///
/// 内核字符串会被截断到目标长度，可能因此缺少终止符；此时使用整个切片。
/// 非法 UTF-8 用替换字符处理，而不是丢弃整个事件。
fn parse_c_string(bytes: &[u8]) -> String {
    let end = bytes
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

/// 从字节切片中拷贝一个 packed 结构体。
///
/// 记录来自字节缓冲区，其对齐无法保证（结构体本身也是 packed），因此必须
/// 使用非对齐读取。`T: Copy` 保证读取不涉及析构或别名问题。
fn read_packed_struct<T: Copy>(bytes: &[u8]) -> Result<T> {
    ensure!(bytes.len() >= size_of::<T>(), "truncated packed struct");
    let ptr = bytes.as_ptr().cast::<T>();
    Ok(unsafe { std::ptr::read_unaligned(ptr) })
}

/// 本地日历日期（非 UTC），用于按天命名文件。
fn current_log_day() -> String {
    Local::now().format("%Y-%m-%d").to_string()
}

/// 构造某一天的路径，可带滚动序号。
///
/// 序号 0 保持 `sulog-YYYY-MM-DD.log` 的普通名字；之后的序号追加 `-N`，
/// 使按字典序列出时大致保持时间顺序。
fn daily_log_path(day: &str, index: u32) -> PathBuf {
    let file_name = if index == 0 {
        format!("sulog-{day}.log")
    } else {
        format!("sulog-{day}-{index}.log")
    };
    Path::new(defs::LOG_DIR).join(file_name)
}

/// 解析 `log.retention.days`，拒绝 0，保证至少保留当天日志。
fn parse_retention_days(value: &str) -> Result<u64> {
    let days = value
        .trim()
        .parse::<u64>()
        .with_context(|| format!("invalid {SULOG_RETENTION_CONFIG_KEY} value: '{value}'"))?;
    ensure!(
        days > 0,
        "{SULOG_RETENTION_CONFIG_KEY} must be greater than 0"
    );
    Ok(days)
}

/// 把 `log.max_file_size` 解析为原始字节数。
///
/// 刻意不接受单位：Manager 写入的就是字节数，允许单位反而会让笔误悄悄改变
/// 预期大小。
fn parse_max_file_size(value: &str) -> Result<u64> {
    let size = value
        .trim()
        .parse::<u64>()
        .with_context(|| format!("invalid {SULOG_MAX_FILE_SIZE_CONFIG_KEY} value: '{value}'"))?;
    ensure!(
        size > 0,
        "{SULOG_MAX_FILE_SIZE_CONFIG_KEY} must be greater than 0"
    );
    Ok(size)
}

/// 当前生效的保留天数与滚动上限。
#[derive(Clone, Copy, Debug)]
struct SulogConfig {
    retention_days: u64,
    max_file_size: u64,
}

/// 读取配置值；首次读取时把默认值持久化。
///
/// 写入默认值让这些键对 Manager 可见、可编辑，而不是只存在于代码里。
/// `merge_configs` 允许 temp 配置覆盖持久值，但不会改写持久值。
fn ensure_config_value(key: &str, default_value: u64) -> Result<String> {
    let config = module_config::merge_configs(SULOG_CONFIG_MODULE_ID)?;
    if let Some(value) = config.get(key) {
        return Ok(value.clone());
    }

    let default_value = default_value.to_string();
    module_config::set_config_value(
        SULOG_CONFIG_MODULE_ID,
        key,
        &default_value,
        module_config::ConfigType::Persist,
    )?;
    Ok(default_value)
}

/// 加载并校验两项 sulog 设置。
///
/// 会话开始时、跨天时以及跨天触发的滚动时都会调用，因此修改配置后无需
/// 重启 sulogd 即可生效。
fn ensure_sulog_config() -> Result<SulogConfig> {
    let retention_days = parse_retention_days(&ensure_config_value(
        SULOG_RETENTION_CONFIG_KEY,
        DEFAULT_SULOG_RETENTION_DAYS,
    )?)?;
    let max_file_size = parse_max_file_size(&ensure_config_value(
        SULOG_MAX_FILE_SIZE_CONFIG_KEY,
        DEFAULT_SULOG_MAX_FILE_SIZE,
    )?)?;
    Ok(SulogConfig {
        retention_days,
        max_file_size,
    })
}

/// 只取日志文件名中的日期，用于清理。
fn parse_log_date_from_path(path: &Path) -> Option<NaiveDate> {
    parse_log_name(path).map(|(date, _)| date)
}

/// 从日志文件名中解析日期和滚动序号。
///
/// 支持两种历史格式：
/// - `sulog-YYYY-MM-DD.log` 与 `sulog-YYYY-MM-DD-N.log`（当前格式）
/// - `sulog-YYYY-MM-DD.N.log`（旧格式，以点分隔序号）
///
/// 由于日期本身含 `-`，当前格式通过 `rsplit_once('-')` 后会检查前半段长度
/// 是否正好是日期长度来消除歧义；不符合时回退到按点分隔的旧格式。无法识别
/// 的文件返回 `None`，清理逻辑会忽略它们。
fn parse_log_name(path: &Path) -> Option<(NaiveDate, u32)> {
    let file_name = path.file_name()?.to_str()?;
    let name = file_name.strip_prefix("sulog-")?.strip_suffix(".log")?;
    let (date_str, index) = if name.len() == "YYYY-MM-DD".len() {
        (name, 0)
    } else if let Some((date_str, index_str)) = name.rsplit_once('-') {
        if date_str.len() == "YYYY-MM-DD".len() && index_str.chars().all(|ch| ch.is_ascii_digit()) {
            (date_str, index_str.parse::<u32>().ok()?)
        } else {
            let (legacy_date_str, legacy_index_str) = name.rsplit_once('.')?;
            (legacy_date_str, legacy_index_str.parse::<u32>().ok()?)
        }
    } else {
        let (legacy_date_str, legacy_index_str) = name.rsplit_once('.')?;
        (legacy_date_str, legacy_index_str.parse::<u32>().ok()?)
    };
    Some((NaiveDate::parse_from_str(date_str, "%Y-%m-%d").ok()?, index))
}

/// 删除超过保留期的日志文件。
///
/// 截止日期为 `today - (retention_days - 1)`，即 "3 天"保留今天加前两天，
/// 差一天都不会删。`retention_days` 已校验大于 0，这里再用 `saturating_sub`
/// 防御性避免下溢。无法解析出日期的文件一律保留。
fn cleanup_expired_logs(retention_days: u64) -> Result<()> {
    let log_dir = Path::new(defs::LOG_DIR);
    if !log_dir.exists() {
        return Ok(());
    }

    let today = Local::now().date_naive();
    let cutoff = today
        .checked_sub_days(Days::new(retention_days.saturating_sub(1)))
        .context("failed to compute sulog retention cutoff")?;

    for entry in
        fs::read_dir(log_dir).with_context(|| format!("failed to read {}", log_dir.display()))?
    {
        let entry = entry.with_context(|| format!("failed to read {}", log_dir.display()))?;
        let path = entry.path();
        let Some(log_date) = parse_log_date_from_path(&path) else {
            continue;
        };

        if log_date < cutoff {
            fs::remove_file(&path)
                .with_context(|| format!("failed to remove expired sulog {}", path.display()))?;
            log::info!(
                "removed expired sulog log {}, retention_days={retention_days}",
                path.display()
            );
        }
    }

    Ok(())
}

/// 递归创建私有目录，并把它固定为 0700。
///
/// 即使目录已存在也要重新 chmod：部署或升级过程可能留下过宽权限，而日志内容
/// 包含命令行。
fn ensure_private_dir_exists(path: &Path) -> Result<()> {
    DirBuilder::new()
        .recursive(true)
        .mode(SULOG_DIR_MODE)
        .create(path)
        .with_context(|| format!("failed to create {}", path.display()))?;
    fs::set_permissions(path, Permissions::from_mode(SULOG_DIR_MODE))
        .with_context(|| format!("failed to chmod {} to {:o}", path.display(), SULOG_DIR_MODE))?;
    Ok(())
}

/// 以追加模式打开日志文件并强制 0600，返回按行刷新的写入器。
///
/// 用 `LineWriter` 是为了每条事件落盘后立即可读，同时仍把短行合并成一次
/// write。哪怕文件已存在也要重新 chmod，避免继承过宽权限。
fn open_line_writer(path: &Path) -> Result<LineWriter<File>> {
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(SULOG_FILE_MODE)
        .open(path)
        .with_context(|| format!("failed to open {}", path.display()))?;
    file.set_permissions(Permissions::from_mode(SULOG_FILE_MODE))
        .with_context(|| {
            format!(
                "failed to chmod {} to {:o}",
                path.display(),
                SULOG_FILE_MODE
            )
        })?;
    Ok(LineWriter::new(file))
}

/// 找到当天编号最大的日志文件并打开（必要时新建下一个序号）。
///
/// 返回值同时包含序号和当前大小，供写入器判断何时滚动。若最新文件已经达到
/// 上限，则顺延到下一个序号；重启后继续追加而不是清空，避免丢失上次会话的
/// 事件。
fn open_log_writer_for_day(day: &str, max_file_size: u64) -> Result<(u32, u64, LineWriter<File>)> {
    let mut highest_index = 0u32;
    let mut found = false;
    for entry in
        fs::read_dir(defs::LOG_DIR).with_context(|| format!("failed to read {}", defs::LOG_DIR))?
    {
        let entry = entry.with_context(|| format!("failed to read {}", defs::LOG_DIR))?;
        let path = entry.path();
        let Some((log_date, index)) = parse_log_name(&path) else {
            continue;
        };
        if log_date == NaiveDate::parse_from_str(day, "%Y-%m-%d").context("invalid current day")? {
            highest_index = highest_index.max(index);
            found = true;
        }
    }

    let mut index = if found { highest_index } else { 0 };
    let mut path = daily_log_path(day, index);
    let mut current_size = fs::metadata(&path).map_or(0, |meta| meta.len());
    if current_size >= max_file_size && current_size > 0 {
        index = index.saturating_add(1);
        path = daily_log_path(day, index);
        current_size = fs::metadata(&path).map_or(0, |meta| meta.len());
    }

    let writer = open_line_writer(&path)?;
    Ok((index, current_size, writer))
}

/// 读取本次启动的唯一 id，用于把多个 sulogd 会话对应到同一次开机。
fn read_boot_id() -> Result<String> {
    let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .context("failed to read /proc/sys/kernel/random/boot_id")?;
    Ok(boot_id.trim().to_string())
}

/// 尝试对文件加非阻塞独占 flock。
///
/// 返回 `true` 表示加锁成功，`false` 表示锁已被其他实例持有（EWOULDBLOCK /
/// EAGAIN）。flock 作用于打开的文件描述，因此跨进程有效，且进程退出后由内核
/// 自动释放。这里同时检查 errno 和 `ErrorKind::WouldBlock`，以兼容不同平台对
/// 该错误的映射。
fn try_lock_file(file: &File) -> io::Result<bool> {
    // SAFETY: file.as_raw_fd() 在调用期间保持有效，flock 不会保存该 fd。
    let ret = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
    if ret == 0 {
        return Ok(true);
    }

    let err = io::Error::last_os_error();
    if let Some(code) = err.raw_os_error()
        && (code == libc::EWOULDBLOCK || code == libc::EAGAIN)
    {
        return Ok(false);
    }

    if err.kind() == ErrorKind::WouldBlock {
        return Ok(false);
    }

    Err(err)
}

/// 转义字段值，保证一个事件始终只占一行。
///
/// 采用 logfmt 风格：反斜杠、引号、换行、回车、制表符转成短转义，其余控制
/// 字符写成 `\xNN`。这样 argv 里的换行不会被误认为新的日志行。
fn escape_field(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => escaped.push_str("\\\\"),
            '"' => escaped.push_str("\\\""),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            ch if ch.is_control() => {
                let code = u32::from(ch);
                let _ = write!(escaped, "\\x{code:02x}");
            }
            ch => escaped.push(ch),
        }
    }
    escaped
}

/// 把一条事件格式化为单行文本。
///
/// `EventRecordHeader` 是 packed 结构，直接引用其字段会触发未对齐引用错误，
/// 因此先拷贝到局部变量再格式化。字符串字段统一转义，数值字段原样输出。
fn format_event_line(header: &EventRecordHeader, event: &SulogEvent) -> String {
    let ts_ns = header.ts_ns;
    let seq = header.seq;
    let version = event.version;
    let retval = event.retval;
    let process_id = event.pid;
    let tgid = event.tgid;
    let parent_process_id = event.ppid;
    let uid = event.uid;
    let euid = event.euid;
    format!(
        "ts_ns={} seq={} type={} version={} retval={} pid={} tgid={} ppid={} uid={} euid={} comm=\"{}\" file=\"{}\" argv=\"{}\"",
        ts_ns,
        seq,
        event.event_name(),
        version,
        retval,
        process_id,
        tgid,
        parent_process_id,
        uid,
        euid,
        escape_field(&event.comm),
        escape_field(&event.file),
        escape_field(&event.argv),
    )
}

/// 把内核的丢事件通知格式化为单行。
///
/// 同样先把 packed 字段拷贝到局部变量，避免未对齐引用。
fn format_dropped_line(header: &EventRecordHeader, info: &DroppedInfo) -> String {
    let ts_ns = header.ts_ns;
    let seq = header.seq;
    let dropped = info.dropped;
    let first_seq = info.first_seq;
    let last_seq = info.last_seq;
    format!(
        "ts_ns={ts_ns} seq={seq} type=dropped dropped={dropped} first_seq={first_seq} last_seq={last_seq}"
    )
}

/// 写出一行日志，按需先滚动文件。
///
/// 长度包含换行符，与磁盘上的实际增量一致。每行显式 flush，使 `tail -f`
/// 能立即看到；由于 `LineWriter` 不暴露文件偏移，写入后手工累加
/// `current_size` 用于下次滚动判断。
fn write_log_line(writer: &mut DailyLogWriter, line: &str) -> io::Result<()> {
    let write_len = line
        .len()
        .checked_add(1)
        .ok_or_else(|| io::Error::other("sulog line length overflow"))?;
    writer
        .rotate_if_needed(write_len)
        .map_err(io::Error::other)?;
    writer.writer.write_all(line.as_bytes())?;
    writer.writer.write_all(b"\n")?;
    writer.writer.flush()?;
    writer.current_size = writer
        .current_size
        .saturating_add(u64::try_from(write_len).map_err(io::Error::other)?);
    Ok(())
}

/// 根据记录类型解码并格式化一条队列记录。
///
/// 类型 `0xFFFF` 是内核内部使用的丢事件通知，必须同时带 internal 标志；
/// 其他值都按普通 sulog 事件处理。
fn format_record_line(header: EventRecordHeader, payload: &[u8]) -> Result<String> {
    if header.record_type == KSU_EVENT_QUEUE_TYPE_DROPPED {
        ensure!(
            header.flags & KSU_EVENT_RECORD_FLAG_INTERNAL != 0,
            "dropped record missing internal flag"
        );
        let info = DroppedInfo::parse(payload)?;
        return Ok(format_dropped_line(&header, &info));
    }

    let event = SulogEvent::parse(payload)?;
    Ok(format_event_line(&header, &event))
}

/// 一次可读事件中把队列尽量排空，并逐条写入日志。
///
/// fd 已设为非阻塞，因此循环读到 EAGAIN 才返回 `Drained`；读到 0 则说明内核
/// 关闭了队列。单次读取是若干 `header + payload` 的拼接流，按头部长度顺序
/// 解析。缓冲区大于内核允许的最大记录，正常情况下不会出现半条记录；若出现，
/// 说明数据损坏或对端异常，只能丢弃剩余部分并等下一次读取重新同步（记录之间
/// 没有可用于重新对齐的帧标记）。
fn handle_readable(fd: RawFd, writer: &mut DailyLogWriter) -> Result<ReadState> {
    let mut buf = [0u8; READ_BUF_SIZE];

    loop {
        // SAFETY: buf 可写且长度正确，fd 在本函数内保持打开。
        let read_len =
            unsafe { libc::read(fd, buf.as_mut_ptr().cast::<libc::c_void>(), buf.len()) };
        if read_len < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            if matches!(err.kind(), ErrorKind::WouldBlock)
                || err.raw_os_error() == Some(libc::EAGAIN)
            {
                return Ok(ReadState::Drained);
            }
            return Err(err).context("failed to read sulog event queue");
        }

        if read_len == 0 {
            return Ok(ReadState::Closed);
        }

        let read_len = usize::try_from(read_len).context("negative sulog read length")?;
        let mut offset = 0usize;

        while offset < read_len {
            let remaining = read_len - offset;
            if remaining < size_of::<EventRecordHeader>() {
                log::warn!("dropping truncated sulog frame header: {remaining} bytes remaining");
                break;
            }

            let header =
                EventRecordHeader::parse(&buf[offset..offset + size_of::<EventRecordHeader>()])?;
            let payload_len = match usize::try_from(header.payload_len) {
                Ok(payload_len) => payload_len,
                Err(err) => {
                    let seq = header.seq;
                    log::warn!(
                        "dropping sulog frame with invalid payload length at seq={seq}: {err:#}"
                    );
                    break;
                }
            };
            let Some(frame_len) = size_of::<EventRecordHeader>().checked_add(payload_len) else {
                let seq = header.seq;
                log::warn!("dropping sulog frame with overflowing length at seq={seq}");
                break;
            };
            if remaining < frame_len {
                log::warn!(
                    "dropping truncated sulog frame payload: need {frame_len}, got {remaining}"
                );
                break;
            }

            let payload = &buf[offset + size_of::<EventRecordHeader>()..offset + frame_len];
            match format_record_line(header, payload) {
                Ok(line) => {
                    write_log_line(writer, &line).context("failed to write sulog line")?;
                }
                Err(err) => {
                    // 单条记录损坏不应终止整个会话，跳过它继续解析后续记录。
                    let seq = header.seq;
                    let record_type = header.record_type;
                    log::warn!(
                        "dropping malformed sulog record seq={seq} type={record_type}: {err:#}"
                    );
                }
            }
            offset += frame_len;
        }
    }
}

/// 通过超命令取回内核的 sulog 事件队列 fd，并设为非阻塞。
///
/// 取回的原始 fd 所有权移交给 `OwnedFd`，`Drop` 时 close 会触发内核侧
/// `ksu_sulog_release`，清除 active 状态。非阻塞使排空循环能以 EAGAIN 结束，
/// 而不会卡在 read 上。
pub fn open_sulog_fd() -> Result<OwnedFd> {
    let fd = ksucalls::get_sulog_fd()?;
    // SAFETY: ioctl 成功时 fd 归调用方所有，这里立即接管唯一所有权。
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    // SAFETY: fd 有效，F_GETFL 只读取文件状态标志。
    let flags = unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_GETFL) };
    if flags < 0 {
        bail!("open_sulog_fd: get flags: {}", io::Error::last_os_error())
    }

    // 在原有标志上追加 O_NONBLOCK，保留内核/OwnedFd 已经设置的属性。
    // SAFETY: fd 有效，F_SETFL 不涉及缓冲区或指针。
    // 注意：这条错误文案沿用了 cloexec 字样，实际设置的是 O_NONBLOCK。
    if unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        bail!(
            "open_sulog_fd: set cloexec flags: {}",
            io::Error::last_os_error()
        )
    }

    Ok(fd)
}

/// 写入会话分隔标记，便于把日志按开机和重启次数分组。
///
/// 首次启动写 `daemon_start`，之后每次会话重启写 `daemon_restart` 并附带
/// 递增计数；`boot_id` 用来区分不同次开机。
fn write_session_marker(
    writer: &mut DailyLogWriter,
    boot_id: &str,
    restart_count: u64,
) -> Result<()> {
    let line = if restart_count == 0 {
        format!("type=daemon_start boot_id=\"{}\"", escape_field(boot_id))
    } else {
        format!(
            "type=daemon_restart boot_id=\"{}\" restart={restart_count}",
            escape_field(boot_id)
        )
    };
    write_log_line(writer, &line).context("failed to write sulogd session marker")
}

/// 运行一次 sulogd 会话，直到 fd 关闭或 epoll 挂断。
///
/// 每次会话都会重新打开 sulog fd、日志写入器和 epoll，因为内核侧关闭旧队列
/// 后无法复用。返回退出原因而不是直接重试，由调用方统一记录并延时。
fn run_sulog_session(restart_count: u64) -> Result<SessionExitReason> {
    let sulog_fd = open_sulog_fd().context("failed to open sulog fd")?;
    let mut writer = DailyLogWriter::open()?;
    let boot_id = read_boot_id()?;

    // EPOLL_CLOEXEC：当前没有 exec，但避免将来 exec 子进程时泄漏 epoll fd。
    // SAFETY: epoll_create1 无指针参数，返回值在下方检查。
    let epoll_raw = unsafe { libc::epoll_create1(libc::EPOLL_CLOEXEC) };
    if epoll_raw < 0 {
        bail!("failed to create epoll fd: {}", io::Error::last_os_error());
    }
    // SAFETY: epoll_raw 刚创建成功，所有权转交 OwnedFd。
    let epoll_fd = unsafe { OwnedFd::from_raw_fd(epoll_raw) };

    // 关注可读、错误和挂断；data 里存 fd 号，方便调试时识别来源。
    let mut event = libc::epoll_event {
        events: u32::try_from(libc::EPOLLIN | libc::EPOLLERR | libc::EPOLLHUP)
            .context("invalid epoll event mask")?,
        u64: u64::try_from(sulog_fd.as_raw_fd()).context("invalid sulog fd")?,
    };
    // SAFETY: 两个 fd 均有效，event 在调用期间可写且生命周期覆盖调用。
    let ctl_ret = unsafe {
        libc::epoll_ctl(
            epoll_fd.as_raw_fd(),
            libc::EPOLL_CTL_ADD,
            sulog_fd.as_raw_fd(),
            &raw mut event,
        )
    };
    if ctl_ret < 0 {
        bail!(
            "failed to register sulog fd to epoll: {}",
            io::Error::last_os_error()
        );
    }

    log::info!("sulogd session started, boot_id={boot_id}, restart={restart_count}");
    write_session_marker(&mut writer, &boot_id, restart_count)?;

    let mut events = [libc::epoll_event { events: 0, u64: 0 }; 4];
    loop {
        // 只注册了一个 fd，4 个槽位足够；-1 表示无限等待，靠信号或关闭唤醒。
        // SAFETY: events 可写且长度与传入的 maxevents 一致。
        let ready = unsafe {
            libc::epoll_wait(
                epoll_fd.as_raw_fd(),
                events.as_mut_ptr(),
                i32::try_from(events.len()).context("too many epoll events")?,
                -1,
            )
        };
        if ready < 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            return Err(err).context("epoll_wait failed for sulogd");
        }

        let ready = usize::try_from(ready).context("invalid epoll ready count")?;
        for ready_event in &events[..ready] {
            let event_mask = ready_event.events;
            if event_mask & u32::try_from(libc::EPOLLIN).context("invalid EPOLLIN")? != 0 {
                match handle_readable(sulog_fd.as_raw_fd(), &mut writer)? {
                    ReadState::Drained => {}
                    ReadState::Closed => {
                        log::warn!("sulog fd closed");
                        return Ok(SessionExitReason::FdClosed);
                    }
                }
            }

            // ERR/HUP 也先排空一次，尽量把最后的事件写完再退出会话。
            let hup_mask =
                u32::try_from(libc::EPOLLERR | libc::EPOLLHUP).context("invalid EPOLLHUP mask")?;
            if event_mask & hup_mask != 0 {
                match handle_readable(sulog_fd.as_raw_fd(), &mut writer)? {
                    ReadState::Drained | ReadState::Closed => {}
                }
                log::warn!("sulog epoll hangup");
                return Ok(SessionExitReason::EpollHangup);
            }
        }
    }
}

/// 单实例守护循环：拿到锁后不断重建会话。
///
/// 内核只允许一个 sulog 读者，flock 锁再保证用户态也只有一个实例；已有实例
/// 持锁时直接返回成功，让重复拉起变成无操作。任何会话结束原因都只延时重启，
/// 不退出进程，因为内核侧可能在开机早期尚未就绪。
pub fn run_sulogd() -> Result<()> {
    let Some(_lock_guard) = SulogdLockGuard::acquire()? else {
        log::info!("sulogd lock is held, skipping start");
        return Ok(());
    };

    let mut restart_count = 0u64;
    loop {
        match run_sulog_session(restart_count) {
            Ok(SessionExitReason::FdClosed) => {
                log::warn!(
                    "restarting sulogd session after fd close in {}s",
                    SULOGD_RESTART_DELAY.as_secs()
                );
            }
            Ok(SessionExitReason::EpollHangup) => {
                log::warn!(
                    "restarting sulogd session after hangup in {}s",
                    SULOGD_RESTART_DELAY.as_secs()
                );
            }
            Err(err) => {
                log::warn!(
                    "sulogd session failed: {err:#}; restarting in {}s",
                    SULOGD_RESTART_DELAY.as_secs()
                );
            }
        }

        // 饱和递增，避免极端长时间运行时计数溢出。
        restart_count = restart_count.saturating_add(1);
        thread::sleep(SULOGD_RESTART_DELAY);
    }
}

/// 以守护进程方式重新 exec 自身，进入 `sulogd` 子命令。
///
/// `create_daemon` 在原始父进程返回 `false`（父进程已退出），在最终守护进程
/// 返回 `true`。用 `/proc/self/exe` 而不是固定路径，避免安装/升级替换二进制
/// 后执行到旧文件。exec 失败时 `_exit(1)`，绝不能继续返回调用方逻辑。
pub fn spawn_sulogd() -> Result<()> {
    if utils::create_daemon(true)? {
        let mut command = Command::new("/proc/self/exe");
        command
            .arg("sulogd")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .current_dir("/");

        let err = command.exec();
        log::error!("failed to exec sulogd: {err:#}");
        unsafe {
            // exec 已失败，继续运行会回到父进程的调用栈，必须立即退出。
            libc::_exit(1);
        }
    }
    Ok(())
}

/// 确保 sulogd 在运行；sulog 功能开启时由 feature 流程调用。
///
/// 实际工作全部交给 `spawn_sulogd`：重复调用是安全的，多余实例会在锁上失败
/// 并立即退出。
pub fn ensure_sulogd_running() -> Result<()> {
    spawn_sulogd()
}
