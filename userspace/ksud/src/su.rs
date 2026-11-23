//! 兼容 Magisk 的 `su` 实现。
//!
//! 应用执行 `/system/bin/su` 时，内核的 sucompat 钩子会把 exec 重定向到 ksud，
//! 并令 `argv[0] == "su"`。此时内核已经判定调用者有权限并应用了对应 root
//! 配置档案；本模块只负责解析熟悉的 su 选项并 exec 目标 shell。
//!
//! `grant_root` 是 `ksud debug su` 使用的简化路径。

use crate::{
    defs, ksucalls,
    utils::{self, umask},
};
use anyhow::{Context, Ok, Result, anyhow, bail};
use getopts::Options;
use libc::c_int;
use log::error;
use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::{cmp::Ordering, env, io};
use std::{
    ffi::{CStr, CString},
    process::Command,
};

use crate::ksucalls::get_wrapped_fd;
use rustix::{
    process::getuid,
    thread::{Gid, Uid, set_thread_res_gid, set_thread_res_uid},
};

/// `ksud debug su`：请求内核授予 root，然后 exec 一个 shell。
///
/// `global_mnt` 为真时 shell 运行在 init 的挂载命名空间中，等价于 `su -M`。
pub fn grant_root(global_mnt: bool) -> Result<()> {
    crate::ksucalls::grant_root()?;

    let mut command = Command::new("sh");
    let command = unsafe {
        command.pre_exec(move || {
            if global_mnt {
                let _ = utils::switch_mnt_ns(1);
            }
            Result::Ok(())
        })
    };
    // 把 /data/adb/ksu/bin 加入 PATH
    add_path_to_env(defs::BINARY_DIR)?;
    Err(command.exec().into())
}

/// 打印 `su` 用法摘要。
///
/// 直接写到标准输出，不返回错误；供 `-h`/参数解析失败时使用。
fn print_usage(program: &str, opts: &Options) {
    let brief = format!("KernelSU\n\nUsage: {program} [options] [-] [user [argument...]]");
    print!("{}", opts.usage(&brief));
}

/// 切换到指定的 uid/gid 和附加组。
///
/// 这里进程是单线程的，因此使用线程级 rustix 调用；setres* 会同时修改三个
/// uid，确保 shell 无法重新获得 root。
fn set_identity(uid: u32, gid: u32, groups: &[u32]) -> Result<()> {
    rustix::thread::set_thread_groups(
        groups
            .iter()
            .map(|g| Gid::from_raw(*g))
            .collect::<Vec<_>>()
            .as_ref(),
    )
    .with_context(|| format!("setgroups {groups:?}"))?;
    let gid = Gid::from_raw(gid);
    let uid = Uid::from_raw(uid);
    set_thread_res_gid(gid, gid, gid).with_context(|| format!("setresgid {gid}"))?;
    set_thread_res_uid(uid, uid, uid).with_context(|| format!("setresuid {uid}"))?;
    Ok(())
}

/// 解析 `su` 的用户参数：passwd 中的用户名或数字 uid。
fn resolve_uid(user: &str) -> Result<u32> {
    let c_user = CString::new(user).with_context(|| format!("Invalid user: {user}"))?;
    let pw = unsafe { libc::getpwnam(c_user.as_ptr()).as_ref() };

    if let Some(pw) = pw {
        return Ok(pw.pw_uid);
    }

    user.parse::<u32>()
        .map_err(|_| anyhow::anyhow!("Unknown user: {user}"))
}

/// 把当前线程的 SELinux 标签切换到 `context`。
///
/// 通过写入 `/proc/thread-self/attr/current` 实现，只影响本线程；调用者必须
/// 已有切换目标域的权限（通常是 root 域），否则内核返回 EACCES/EPERM。
/// 切换发生在 exec 之前，因此目标 shell 会继承该标签。
fn set_selinux_context(context: &str) -> Result<()> {
    std::fs::write("/proc/thread-self/attr/current", context)?;
    Ok(())
}

/// 用内核提供的同号包装 fd 替换标准输入输出 fd。
///
/// root 配置档案可能禁止对原终端调用 `isatty`；内核包装 fd 能绕过该限制，
/// 为 shell 恢复可用的 tty。
fn wrap_tty(fd: c_int) {
    let inner_fn = move || -> Result<()> {
        if unsafe { libc::isatty(fd) != 1 }
            && io::Error::last_os_error().raw_os_error() != Some(libc::EACCES)
        {
            return Ok(());
        }

        // 此时 root 档案已经生效，它的 SELinux 域在查询原终端时可能
        // 返回 EACCES。这种情况下改为检查包装后的 fd，因为该描述符
        // 正是用来绕过这一限制的。
        let new_fd = get_wrapped_fd(fd).context("get_wrapped_fd")?;
        if unsafe { libc::isatty(new_fd) != 1 } {
            unsafe { libc::close(new_fd) };
            return Ok(());
        }
        let dup_result = unsafe { libc::dup2(new_fd, fd) };
        let dup_errno = unsafe { *libc::__errno() };
        unsafe { libc::close(new_fd) };
        if dup_result == -1 {
            bail!("dup {new_fd} -> {fd} errno: {dup_errno}");
        }
        Ok(())
    };

    if let Err(e) = inner_fn() {
        error!("wrap tty {fd}: {e:?}");
    }
}

/// `argv[0] == "su"` 时的入口：解析选项并 exec shell。
///
/// 关键在参数归一化。getopts 不支持 GNU 风格的选项合并（`-cn`、`-mm`），
/// 也不支持 `su user -c cmd` 这种顺序，因此解析前会先重写原始 argv。
#[allow(clippy::similar_names)]
pub fn root_shell() -> Result<()> {
    // 内核已经应用了所选的 root 档案。

    // su 会话的 driver fd 会刻意在 exec 进入 ksud 后继续存在。
    // 在处理任何参数之前先认领它，并恢复 FD_CLOEXEC，防止它
    // 泄漏到目标 shell，包括在禁用 fd 包装的情况下。
    ksucalls::claim_inherited_driver_fd().context("claim inherited KernelSU driver fd")?;

    let env_args: Vec<String> = env::args().collect();
    let program = env_args[0].clone();
    let mut executable: Option<String> = None;
    let mut exec_args: Option<Vec<String>> = None;
    // 定位 `-c` 以及第一个位置参数（即可能的登录用户）。
    // `su [options] [-] [user [argument...]]` 允许用户出现在 `-c` 前后，
    // 下面会把两种形式都归一化。
    let first_option_c = env_args
        .iter()
        .position(|arg| arg == "-c")
        .unwrap_or(usize::MAX);
    let first_non_option = env_args
        .windows(3)
        .position(|arg| {
            !arg[1].starts_with('-')
                && !arg[2].starts_with('-')
                && !(arg[0].starts_with("-g")
                    || arg[0].starts_with("-G")
                    || arg[0].starts_with("-s")
                    || arg[0].starts_with("-Z")
                    || arg[0] == "--group"
                    || arg[0] == "--supp-group="
                    || arg[0] == "--shell="
                    || arg[0] == "--context=")
        })
        .map_or(usize::MAX, |idx| idx + 1);
    // 不带 -c 的 `user command...` 直接 exec `command`；带 -c 且后面还有
    // 参数时，把这些参数重新拼成一个 shell 命令字符串。
    let args = match first_non_option.cmp(&first_option_c) {
        Ordering::Equal => env_args,
        Ordering::Less => {
            executable = Some(env_args[first_non_option + 1].clone());
            exec_args = Some(env_args[first_non_option + 2..].to_vec());
            env_args[..=first_non_option].to_vec()
        }
        Ordering::Greater => {
            let rest = env_args[first_option_c + 1..].to_vec();
            let mut new_args = env_args[..first_option_c].to_vec();
            new_args.push("-c".to_string());
            if !rest.is_empty() {
                new_args.push(rest.join(" "));
            }
            new_args
        }
    };

    let mut opts = Options::new();
    opts.optopt(
        "c",
        "command",
        "pass COMMAND to the invoked shell",
        "COMMAND",
    );
    opts.optflag("h", "help", "display this help message and exit");
    opts.optflag("l", "login", "pretend the shell to be a login shell");
    opts.optflag(
        "p",
        "preserve-environment",
        "preserve the entire environment",
    );
    opts.optopt(
        "s",
        "shell",
        "use SHELL instead of the default /system/bin/sh",
        "SHELL",
    );
    opts.optflag("v", "version", "display version number and exit");
    opts.optflag("V", "", "display version code and exit");
    opts.optflag(
        "M",
        "mount-master",
        "force run in the global mount namespace",
    );
    opts.optopt("g", "group", "Specify the primary group", "GROUP");
    opts.optmulti(
        "G",
        "supp-group",
        "Specify a supplementary group. The first specified supplementary group is also used as a primary group if the option -g is not specified.",
        "GROUP",
    );
    opts.optflag("W", "no-wrapper", "don't use ksu fd wrapper");
    opts.optflag(
        "",
        "ksu-no-new-privs",
        "Prevent this process (and its children) from privilege re-escalation via KernelSU",
    );
    opts.optopt("Z", "context", "Specify the SELinux context", "CONTEXT");

    // 把 -cn 替换为 -z，把 -mm 替换为 -M，以兼容 getopt_long
    let args = args
        .into_iter()
        .map(|e| {
            if e == "-mm" {
                "-M".to_string()
            } else if e == "-cn" {
                "-z".to_string()
            } else {
                e
            }
        })
        .collect::<Vec<String>>();

    let matches = match opts.parse(&args[1..]) {
        Result::Ok(m) => m,
        Err(f) => {
            println!("{f}");
            print_usage(&program, &opts);
            std::process::exit(-1);
        }
    };

    if matches.opt_present("h") {
        print_usage(&program, &opts);
        return Ok(());
    }

    if matches.opt_present("v") {
        println!("{}:KernelSU", defs::VERSION_NAME);
        return Ok(());
    }

    if matches.opt_present("V") {
        println!("{}", defs::VERSION_CODE);
        return Ok(());
    }

    let shell = matches
        .opt_str("s")
        .unwrap_or_else(|| "/system/bin/sh".to_string());
    let mut is_login = matches.opt_present("l");
    let preserve_env = matches.opt_present("p");
    let mount_master = matches.opt_present("M");
    let use_fd_wrapper = !matches.opt_present("W");
    let ksu_no_new_privs = matches.opt_present("ksu-no-new-privs");
    let selinux_context = matches.opt_str("Z");

    let groups = matches
        .opt_strs("G")
        .into_iter()
        .map(|g| g.parse::<u32>().map_err(|_| anyhow!("Invalid GID: {g}")))
        .collect::<Result<Vec<_>, _>>()?;

    // 如果提供了 -g，就使用它。
    let mut gid = matches
        .opt_str("g")
        .map(|g| g.parse::<u32>().map_err(|_| anyhow!("Invalid GID: {g}")))
        .transpose()?;

    // 否则使用 groups 中的第一个 gid。
    if gid.is_none() && !groups.is_empty() {
        gid = Some(groups[0]);
    }

    // 已确保 -c 是最后一个选项且已包含完整命令，无需再次构造
    let args = exec_args.unwrap_or_else(|| {
        matches
            .opt_str("c")
            .map_or_else(Vec::new, |cmd| vec!["-c".to_string(), cmd])
    });

    // 第一个位置参数为单独的 "-" 时表示登录 shell。
    let mut free_idx = 0;
    if !matches.free.is_empty() && matches.free[free_idx] == "-" {
        is_login = true;
        free_idx += 1;
    }

    let identity_requested = free_idx < matches.free.len() || gid.is_some() || !groups.is_empty();

    // 未指定用户时使用当前 uid，这部分已由内核完成！
    let uid = if free_idx < matches.free.len() {
        resolve_uid(&matches.free[free_idx])?
    } else {
        getuid().as_raw()
    };

    // 未提供 gid 时使用 uid。
    let gid = gid.unwrap_or(uid);
    let executable = executable.as_ref().unwrap_or(&shell);
    // https://github.com/topjohnwu/Magisk/blob/master/native/src/su/su_daemon.cpp#L408
    let arg0 = if is_login { "-" } else { executable };

    let mut command = Command::new(executable);

    if !preserve_env {
        // Magisk 保留继承的环境而不调用 env_clear()，shell 依赖这一行为；
        // 这里只覆盖登录身份相关的变量。
        let pw = unsafe { libc::getpwuid(uid).as_ref() };

        if let Some(pw) = pw {
            let home = unsafe { CStr::from_ptr(pw.pw_dir) };
            let pw_name = unsafe { CStr::from_ptr(pw.pw_name) };

            let home = home.to_string_lossy();
            let pw_name = pw_name.to_string_lossy();

            command
                .env("HOME", home.as_ref())
                .env("USER", pw_name.as_ref())
                .env("LOGNAME", pw_name.as_ref())
                .env("SHELL", &shell);
        }
    }

    // 把 /data/adb/ksu/bin 加入 PATH
    add_path_to_env(defs::BINARY_DIR)?;

    // KSURC_PATH 存在且未设置 ENV 时，把 ENV 设为 KSURC_PATH
    if PathBuf::from(defs::KSURC_PATH).exists() && env::var("ENV").is_err() {
        command.env("ENV", defs::KSURC_PATH);
    }

    if ksu_no_new_privs {
        ksucalls::set_ksu_no_new_privs().context("set KSU_NO_NEW_PRIVS")?;
    }

    // 脱离 cgroup 并成为会话首进程会让部分 root shell 永久卡住，
    // 因此这里刻意不启用 process_group(0)。
    // command = command.process_group(0);

    command.args(args).arg0(arg0);
    umask(0o22);
    utils::switch_cgroups();

    // 切换到全局挂载命名空间
    if mount_master {
        let _ = utils::switch_mnt_ns(1);
    }

    if use_fd_wrapper {
        wrap_tty(0);
        wrap_tty(1);
        wrap_tty(2);
    }

    if identity_requested {
        set_identity(uid, gid, &groups)?;
    }
    if let Some(context) = selinux_context.as_deref() {
        set_selinux_context(context).with_context(|| format!("setcontext {context}"))?;
    }
    Err(command.exec().into())
}

/// 把 `path` 追加到 `PATH`（针对单次 shell 初始化，简单去重即可）。
fn add_path_to_env(path: &str) -> Result<()> {
    let mut paths =
        env::var_os("PATH").map_or(Vec::new(), |val| env::split_paths(&val).collect::<Vec<_>>());
    let new_path = PathBuf::from(path.trim_end_matches('/'));
    paths.push(new_path);
    let new_path_env = env::join_paths(paths)?;
    unsafe { env::set_var("PATH", new_path_env) };
    Ok(())
}
