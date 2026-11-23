//! KernelSU 内核接口（supercall）的用户态封装。
//!
//! 内核暴露一个匿名 inode（`anon_inode:[ksu_driver]`），其 fd 接受一组 ioctl。
//! ksud 通过扫描 `/proc/self/fd` 找到该 fd；若未继承，则用特殊的 `reboot(2)`
//! 调用请求内核创建一个。所有上层操作最终都经 `ksuctl` 下发。
#![allow(clippy::unreadable_literal)]
use anyhow::{Result, bail};

use crate::ksu_uapi;
use std::cell::Cell;
use std::fs;
use std::io;
use std::os::fd::RawFd;
use std::sync::OnceLock;

// sigsys 处理器
//
// 部分内核对安装系统调用套了 seccomp。过滤器拒绝时线程收到的是 SIGSYS 而不是
// 普通 errno。下面两个线程局部标志让 `init_driver_fd` 能区分“被 seccomp 拦截”
// 与其他失败，同时避免引入全局状态。
std::thread_local! {
    /// 标记当前线程正在执行可能被 seccomp 拦截的驱动调用。
    #[allow(clippy::missing_const_for_thread_local)]
    static DRIVER_CALL_IN_FLIGHT: Cell<bool> = const { Cell::new(false) };
    /// 记录驱动调用期间是否真的收到过 seccomp 触发的 SIGSYS。
    #[allow(clippy::missing_const_for_thread_local)]
    static SIGSYS_OCCURRED: Cell<bool> = const { Cell::new(false) };
}

/// seccomp 过滤器拒绝系统调用时 `siginfo.si_code` 的固定取值。
///
/// 只有该值出现才说明 SIGSYS 来自 seccomp，而不是用户自己 `kill` 出来的。
// 由 seccomp 过滤器触发 SIGSYS 时 `si_code` 的取值。
const SYS_SECCOMP: libc::c_int = 1;

/// 标记正在进行的驱动调用，使期间收到的 SIGSYS 能归因于 KernelSU 而不是
/// 无关代码。
fn with_driver_call<F, R>(call: F) -> R
where
    F: FnOnce() -> R,
{
    DRIVER_CALL_IN_FLIGHT.with(|in_flight| in_flight.set(true));
    let result = call();
    DRIVER_CALL_IN_FLIGHT.with(|in_flight| in_flight.set(false));
    result
}

/// 读取并清零 `SIGSYS_OCCURRED`，把“刚才被 seccomp 拦过”的事实取走一次。
///
/// 使用替换语义，避免同一事件在下一次驱动调用中被重复报告。
fn take_sigsys_occurred() -> bool {
    SIGSYS_OCCURRED.with(|occurred| occurred.replace(false))
}

/// SIGSYS 处理器：把被中断系统调用的返回值改写为 `-EPERM`，并记录驱动安装
/// 被拦截的事实。
///
/// 不同架构的寄存器布局不同，因此下面按架构分块处理。
extern "C" fn sigsys_handler(
    _sig: libc::c_int,
    info: *mut libc::siginfo_t,
    ctx: *mut libc::c_void,
) {
    unsafe {
        if info.is_null() || ctx.is_null() || (*info).si_code != SYS_SECCOMP {
            return;
        }
        if DRIVER_CALL_IN_FLIGHT.with(Cell::get) {
            SIGSYS_OCCURRED.with(|occurred| occurred.set(true));
        }

        #[cfg(not(target_arch = "riscv64"))]
        let ucontext = ctx.cast::<libc::ucontext_t>();
        #[cfg(target_arch = "aarch64")]
        {
            (*ucontext).uc_mcontext.regs[0] = (-libc::EPERM) as u64;
        }
        #[cfg(target_arch = "x86_64")]
        {
            let rax = libc::REG_RAX as usize;
            (*ucontext).uc_mcontext.gregs[rax] = i64::from(-libc::EPERM);
        }
        #[cfg(target_arch = "riscv64")]
        {
            let ucontext = ctx.cast::<ksu_uapi::ucontext_t>();
            (*ucontext).uc_mcontext.__gregs[ksu_uapi::REG_A0 as usize] =
                (-libc::EPERM) as libc::c_ulong;
        }
    }
}

/// 为 SIGSYS 安装 `sigsys_handler`。失败只记日志：没有 seccomp 过滤器的内核
/// 根本不会走到这条路径。
pub fn setup_sigsys_handler() {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_flags = libc::SA_SIGINFO;
        sa.sa_sigaction = sigsys_handler as *const () as usize;
        libc::sigemptyset(std::ptr::addr_of_mut!(sa.sa_mask));
        if libc::sigaction(libc::SIGSYS, std::ptr::addr_of!(sa), std::ptr::null_mut()) != 0 {
            let error = std::io::Error::last_os_error();
            log::warn!("Failed to set SIGSYS handler: {error}");
        }
    }
}

/// 普通驱动 fd 经 `readlink(2)` 读到的目标名，用于在 `/proc/self/fd` 中识别它。
// 驱动 fd 经 readlink(2) 读到的目标名。
const DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver]";
/// 从 `su` 会话继承的驱动 fd 目标名；它携带调用者凭据，优先级高于全局 fd。
// 从 `su` 会话继承的驱动 fd；它携带调用者的凭据/状态，优先于全局 fd。
const SU_DRIVER_FD_NAME: &str = "anon_inode:[ksu_driver_su]";

/// 进程级驱动 fd 缓存。
///
/// 该 fd 由内核匿名 inode 支撑，生命周期等同进程；一旦缓存便永不再关闭，
/// 以免其他代码路径持有同一 fd 时出现悬空。
// 全局驱动 fd 缓存。该 fd 属于整个进程，且从不关闭。
static DRIVER_FD: OnceLock<RawFd> = OnceLock::new();
/// 内核信息（版本/标志/特性/UAPI）缓存。
///
/// 这些值在一次开机内不会变化，缓存可避免每次查询都触发 ioctl。
// 内核信息在一次启动内不会变化，缓存起来避免每次调用都发 ioctl。
static INFO_CACHE: OnceLock<ksu_uapi::ksu_get_info_cmd> = OnceLock::new();

/// 在 `/proc/self/fd` 中查找已有的驱动 fd。
///
/// 一旦发现 `ksu_driver_su` 立即返回；否则记住第一个 `ksu_driver` fd，
/// 扫描结束后返回。
fn scan_driver_fd() -> io::Result<Option<RawFd>> {
    let fd_dir = fs::read_dir("/proc/self/fd")?;
    let mut driver_fd = None;

    for entry in fd_dir.flatten() {
        if let Ok(fd_num) = entry.file_name().to_string_lossy().parse::<i32>() {
            let link_path = format!("/proc/self/fd/{fd_num}");
            if let Ok(target) = fs::read_link(&link_path) {
                let target_str = target.to_string_lossy();
                if target_str == SU_DRIVER_FD_NAME {
                    return Ok(Some(fd_num));
                }
                if target_str == DRIVER_FD_NAME {
                    driver_fd = Some(fd_num);
                }
            }
        }
    }

    Ok(driver_fd)
}

/// 接管内核在 `su` exec 时传递下来的驱动 fd。
///
/// 必须在解析参数之前执行，以便在 fd 仍打开时完成缓存；
/// `OnceLock` 保证该操作幂等。
pub fn claim_inherited_driver_fd() -> io::Result<()> {
    if DRIVER_FD.get().is_none()
        && let Some(fd) = scan_driver_fd()?
    {
        let _ = DRIVER_FD.set(fd);
    }
    Ok(())
}

/// 解析出驱动 fd；若进程没有继承，则通过特殊的 reboot 系统调用安装一个。
fn init_driver_fd() -> Option<RawFd> {
    let fd = scan_driver_fd().ok().flatten();
    if fd.is_none() {
        let mut fd = -1;
        // `reboot(MAGIC1, MAGIC2, 0, &fd)` 是安装握手；内核通过最后一个参数
        // 返回新的匿名 inode fd。
        with_driver_call(|| unsafe {
            libc::syscall(
                libc::SYS_reboot,
                ksu_uapi::KSU_INSTALL_MAGIC1,
                ksu_uapi::KSU_INSTALL_MAGIC2,
                0,
                &mut fd,
            )
        });
        if take_sigsys_occurred() {
            eprintln!("KernelSU driver install syscall was blocked by seccomp");
            log::error!("KernelSU driver install syscall was blocked by seccomp");
        }
        if fd >= 0 { Some(fd) } else { None }
    } else {
        fd
    }
}

/// 在缓存的驱动 fd 上执行一次 supercall ioctl。
///
/// `arg` 指向某个 `ksu_uapi` 命令结构体。返回值为负时转换为携带 errno 的
/// `anyhow` 错误。
fn ksuctl<T>(request: u32, arg: *mut T) -> Result<i32> {
    let fd = *DRIVER_FD.get_or_init(|| init_driver_fd().unwrap_or(-1));
    if fd < 0 {
        bail!("could not retrieve kernelsu driver fd")
    }
    unsafe {
        // fd 由全局 `DRIVER_FD` 持有且不会关闭；`arg` 要么指向调用期间存活的命令结构体，
        // 要么是无参命令的空指针，因此这里裸指针的使用是安全的。
        let ret = libc::ioctl(fd as libc::c_int, request as i32, arg);
        if ret < 0 {
            bail!("ksuctl failed: {}", io::Error::last_os_error())
        }
        Ok(ret)
    }
}

// API 实现
/// 获取（并缓存）内核的版本/标志/特性信息块。
///
/// 旧内核只实现 legacy ioctl，因此新请求失败时回退到旧接口。
pub fn get_info() -> ksu_uapi::ksu_get_info_cmd {
    *INFO_CACHE.get_or_init(|| {
        let mut cmd = ksu_uapi::ksu_get_info_cmd {
            version: 0,
            flags: 0,
            features: 0,
            uapi_version: 0,
        };
        if ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut cmd).is_err() {
            let _ = ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO_LEGACY, &raw mut cmd);
        }
        cmd
    })
}

/// 返回内核模块版本号（来自缓存的 info 块）。
pub fn get_version() -> i32 {
    get_info().version as i32
}

/// 内核是否以 late-load（不刷写 boot、运行期加载）模式运行。
pub fn is_late_load() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LATE_LOAD != 0
}

/// 内核是否以可加载模块（LKM/`.ko`）形式加载，而非编进内核镜像。
pub fn is_lkm() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LKM != 0
}

/// 内核是否在开机时执行模块 preinit 动作。
pub fn is_preinit_supported() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_PREINIT != 0
}

/// 返回本 ksud 编译时使用的 UAPI 版本号，供与内核侧版本比对。
pub const fn uapi_version() -> u32 {
    ksu_uapi::KERNEL_SU_UAPI_VERSION
}

/// 返回可读的运行模式字符串，用于日志与诊断。
///
/// 优先级：late-load > lkm > built-in；三者互斥，由内核 info flags 决定。
pub fn runtime_mode() -> &'static str {
    if is_late_load() {
        "late-load"
    } else if is_lkm() {
        "lkm"
    } else {
        "built-in"
    }
}

/// 校验内核与用户态 UAPI 版本一致。
///
/// 版本不一致意味着 ioctl 结构体布局可能不同，继续下发会有越界/误解码风险，
/// 因此直接报错要求用户更新 KernelSU。
pub fn ensure_uapi_version_matched() -> anyhow::Result<()> {
    let kernel_uapi = get_info().uapi_version;
    let userspace_uapi = uapi_version();
    if kernel_uapi != userspace_uapi {
        bail!(
            "UAPI version mismatch: kernel={kernel_uapi}, ksud={userspace_uapi}. Please update KernelSU!"
        );
    }
    Ok(())
}

/// 向内核声明当前 `su` 调用已获授权（grant root）。
///
/// 无参数；错误原样传播自 ioctl。
pub fn grant_root() -> Result<()> {
    ksuctl(ksu_uapi::KSU_IOCTL_GRANT_ROOT, std::ptr::null_mut::<u8>())?;
    Ok(())
}

/// 把指定进程提升为 root（内核侧仅允许 root 调用）。
pub fn set_process_root(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_process_root_cmd { pid, flags: 0 };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_PROCESS_ROOT, &raw mut cmd)?;
    Ok(())
}

/// 向内核上报一个开机事件（内部辅助）。
///
/// 失败被吞掉：事件上报是尽力而为的进度通知，丢失不应中断开机流程。
fn report_event(event: u32) {
    // 事件类型由 ksu_uapi::EVENT_* 定义，随 ioctl 结构体一并下发
    let mut cmd = ksu_uapi::ksu_report_event_cmd { event };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd);
}

/// 上报 post-fs-data 阶段完成，让内核推进内部开机状态机。
pub fn report_post_fs_data() {
    report_event(ksu_uapi::EVENT_POST_FS_DATA);
}

/// 上报 services 阶段，并返回内核是否认为设备已启动完成。
///
/// ioctl 返回 1 表示 boot completed，其余为否；错误向上传播。
pub fn report_services() -> Result<bool> {
    // 内核在收到 services 事件时顺带告知启动是否完成
    let mut cmd = ksu_uapi::ksu_report_event_cmd {
        event: ksu_uapi::EVENT_SERVICES,
    };
    Ok(ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd)? == 1)
}

/// 上报 boot-completed 阶段，告知内核开机流程结束。
pub fn report_boot_complete() {
    report_event(ksu_uapi::EVENT_BOOT_COMPLETED);
}

/// 上报模块已挂载（meta-overlayfs 等），用于内核侧状态跟踪。
pub fn report_module_mounted() {
    report_event(ksu_uapi::EVENT_MODULE_MOUNTED);
}

/// 查询内核是否处于安全模式。
///
/// ioctl 失败时按“非安全模式”处理，避免因查询失败而阻塞正常启动。
pub fn check_kernel_safemode() -> bool {
    // in_safe_mode 由内核写入，0 表示否
    let mut cmd = ksu_uapi::ksu_check_safemode_cmd { in_safe_mode: 0 };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_CHECK_SAFEMODE, &raw mut cmd);
    cmd.in_safe_mode != 0
}

/// 向内核下发一段 sepolicy 规则。
///
/// `payload` 必须是由调用方保证有效的只读缓冲区，`payload_len` 为其字节长度；
/// 指针以 `u64` 传入 UAPI 结构体，由内核负责在调用期间拷入，调用返回后即可释放。
pub fn set_sepolicy(payload: *const u8, payload_len: u64) -> Result<i32> {
    // data 承载用户态地址，内核在 ioctl 上下文内同步读取
    let mut cmd = crate::ksu_uapi::ksu_set_sepolicy_cmd {
        data_len: payload_len,
        data: payload as u64,
    };

    ksuctl(ksu_uapi::KSU_IOCTL_SET_SEPOLICY, &raw mut cmd)
}

/// `KSU_IOCTL_GET_FEATURE` 解码后的结果。
#[derive(Debug, Clone, Copy, Default)]
pub struct FeatureState {
    /// 特性的当前值；含义由 feature_id 定义（布尔特性通常 0/1）。
    pub value: u64,
    /// 内核是否实现该特性；旧内核可能不认识该 id。
    pub supported: bool,
    /// 由启动配置（ksu_config）强制指定，内核拒绝修改
    pub forced: bool,
}

/// 从内核获取特性值及支持状态
pub fn get_feature(feature_id: u32) -> Result<FeatureState> {
    let mut cmd = ksu_uapi::ksu_get_feature_cmd {
        feature_id,
        value: 0,
        supported: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_FEATURE, &raw mut cmd)?;
    let flags = u32::from(cmd.supported);
    Ok(FeatureState {
        value: cmd.value,
        // 旧内核用 1 表示 supported，与标志位取值一致
        supported: flags & ksu_uapi::KSU_FEATURE_FLAG_SUPPORTED != 0,
        forced: flags & ksu_uapi::KSU_FEATURE_FLAG_FORCED != 0,
    })
}

/// 在内核中设置特性值
pub fn set_feature(feature_id: u32, value: u64) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_feature_cmd { feature_id, value };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_FEATURE, &raw mut cmd)?;
    Ok(())
}

/// 把 `fd` 交给内核，换回一个包装后的驱动 fd。
///
/// 包装 fd 由内核持有引用，调用方仍需自行管理原始 `fd` 的生命周期；
/// 返回的 fd 归调用者所有。
pub fn get_wrapped_fd(fd: RawFd) -> Result<RawFd> {
    // fd 以 u32 传入 UAPI 结构体，负数在调用前就应被上层排除
    let mut cmd = ksu_uapi::ksu_get_wrapper_fd_cmd {
        fd: fd as u32,
        flags: 0,
    };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_WRAPPER_FD, &raw mut cmd)?;
    Ok(result)
}

/// 获取内核 sulog 通道的 fd，用于读取内核侧日志。
///
/// 返回的 fd 归调用者所有，需要自行关闭。
pub fn get_sulog_fd() -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_sulog_fd_cmd { flags: 0 };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_SULOG_FD, &raw mut cmd)?;
    Ok(result)
}

/// 获取进程的标记状态（pid=0 时返回已标记总数）
pub fn mark_get(pid: i32) -> Result<u32> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_GET,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(cmd.result)
}

/// 标记一个进程（pid=0 时标记所有进程）
pub fn mark_set(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_MARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// 取消标记一个进程（pid=0 时取消所有进程的标记）
pub fn mark_unset(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_UNMARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// 刷新所有运行中进程的标记
pub fn mark_refresh() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_REFRESH,
        pid: 0,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// 删除给指定挂载点创建的 ext4 sysfs 兼容节点。
///
/// 路径以 NUL 结尾的 C 字符串传给内核；`CString::new` 会拒绝内含 NUL 的路径。
pub fn nuke_ext4_sysfs(mnt: &str) -> anyhow::Result<()> {
    // CString 保证解引用生命周期覆盖 ioctl 调用期间
    let c_mnt = std::ffi::CString::new(mnt)?;
    let mut cmd = ksu_uapi::ksu_nuke_ext4_sysfs_cmd {
        arg: c_mnt.as_ptr() as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_NUKE_EXT4_SYSFS, &raw mut cmd)?;
    Ok(())
}

/// 清空 umount 列表中的所有条目
pub fn umount_list_wipe() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: 0,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_WIPE,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// 向 umount 列表添加挂载点
pub fn umount_list_add(path: &str, flags: u32) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags,
        mode: ksu_uapi::KSU_UMOUNT_ADD,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// 从 umount 列表删除挂载点
pub fn umount_list_del(path: &str) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_DEL,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// 将当前进程的进程组设为 init_group（pgid = 0）
pub fn set_init_pgrp() -> Result<()> {
    ksuctl(
        ksu_uapi::KSU_IOCTL_SET_INIT_PGRP,
        std::ptr::null_mut::<u8>(),
    )?;
    Ok(())
}

/// 查询内核符号地址，无论是否导出。
///
/// 每个名字对应一个结果，符号不存在时为 `None`。名字/地址数组以用户态指针
/// 传入，由 ioctl 负责拷入拷出。
pub fn lookup_symbols(names: &[&str]) -> Result<Vec<Option<u64>>> {
    let mut result = Vec::with_capacity(names.len());
    // ioctl 一次能处理的批量有上限，超出时拆分请求。
    for chunk in names.chunks(ksu_uapi::KSU_LOOKUP_SYMBOLS_MAX as usize) {
        let c_names = chunk
            .iter()
            .map(|name| std::ffi::CString::new(*name))
            .collect::<Result<Vec<_>, _>>()?;
        let ptrs: Vec<u64> = c_names.iter().map(|name| name.as_ptr() as u64).collect();
        let mut addrs = vec![0u64; chunk.len()];
        let mut cmd = ksu_uapi::ksu_lookup_symbols_cmd {
            names: ptrs.as_ptr() as u64,
            addrs: addrs.as_mut_ptr() as u64,
            count: chunk.len() as u32,
            flags: 0,
        };
        ksuctl(ksu_uapi::KSU_IOCTL_LOOKUP_SYMBOLS, &raw mut cmd)?;
        result.extend(addrs.into_iter().map(|addr| (addr != 0).then_some(addr)));
    }
    Ok(result)
}

/// 使当前进程（及其子进程）无法再通过 KernelSU 提权。
/// 对应 `su --ksu-no-new-privs`。
pub fn set_ksu_no_new_privs() -> anyhow::Result<()> {
    let result = ksuctl(
        ksu_uapi::KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT,
        std::ptr::null_mut::<u8>(),
    )?;
    if result != 0 {
        bail!("unexpected result: {result}");
    }
    Ok(())
}
