//! 与 Magisk 兼容的 `resetprop` 实现。
//!
//! 既作为 `resetprop` multicall 名称暴露，也支持 `ksud resetprop`，同时被内部用于加载模块的
//! `system.prop`。真正的属性读写由 `prop_rs_android` crate 完成（直接访问属性区并向
//! property_service 发请求）；本模块只负责 Magisk 的参数解析、优先级规则和退出码。

use anyhow::{Context, Result, bail};
use clap::Parser;
use clap::error::ErrorKind;
use log::info;
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::fmt;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::time::Duration;

/// 用于表示 `--wait` 超时的错误。
///
/// 单独定义成一种类型，是为了让 `resetprop_main` 能把它映射为退出码 2：Magisk 脚本约定
/// “等待了但条件始终未满足”与真正的失败（退出码 1）不同。
#[derive(Debug)]
struct WaitTimeoutError {
    name: String,
}

impl fmt::Display for WaitTimeoutError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "timeout waiting for {}", self.name)
    }
}

impl std::error::Error for WaitTimeoutError {}

/// 兼容 Magisk 的 Android 系统属性工具。
///
/// 参数集合刻意与 Magisk 的 `resetprop` 保持一致，使现有模块脚本可以继续工作；大部分布尔开关
/// 会原样传给 `ResetProp`。
#[derive(Parser)]
#[command(
    name = "resetprop",
    version,
    about = "Magisk-compatible system property tool",
    disable_help_subcommand = true,
    after_help = "Arguments:\n  NAME   Property name.\n  VALUE  Property value (for set or wait-for-value)."
)]
#[allow(clippy::struct_excessive_bools)]
struct Args {
    /// 跳过 property_service（强制直接进行 mmap 操作）。
    #[arg(short = 'n', long = "skip-svc")]
    skip_svc: bool,

    /// 同时操作持久化属性存储（persist.* 文件）。
    #[arg(short = 'p', long = "persistent")]
    persistent: bool,

    /// 只从存储中读取持久化属性。
    #[arg(short = 'P')]
    persist_only: bool,

    /// 删除指定名称的属性。
    #[arg(short = 'd', long = "delete")]
    delete: bool,

    /// 输出详细日志。
    #[arg(short = 'v', long = "verbose")]
    verbose: bool,

    /// 等待属性出现，或从给定值变为另一个值。
    #[arg(short = 'w', long = "wait")]
    wait: bool,

    /// `--wait` 的超时时间（秒，默认永远等待）。
    #[arg(long = "timeout", value_parser = parse_timeout)]
    timeout: Option<Duration>,

    /// 从 FILE 读取并设置属性。
    #[arg(short = 'f', long = "file")]
    file: Option<String>,

    /// 按 SELinux context 名重建属性区；未给出名字时重建所有属性区。
    #[arg(short = 'c', long = "rebuild", alias = "compact")]
    rebuild: bool,

    /// 列出属性时显示 SELinux context；若同时使用 -c，则重建包含属性 NAME 的那个属性区。
    #[arg(short = 'Z')]
    show_context: bool,

    /// 强制重建所有属性区，应与 `-c` 一起使用。未设置该标志时，只重建异常的属性区。
    #[arg(long = "force")]
    force: bool,

    #[arg(
        allow_hyphen_values = true,
        trailing_var_arg = true,
        num_args = 0..=2,
        hide = true,
    )]
    arguments: Vec<String>,
}

/// 解析以秒为单位（可带小数）的超时时间。
fn parse_timeout(s: &str) -> Result<Duration> {
    let timeout: f64 = s.parse()?;
    Ok(Duration::try_from_secs_f64(timeout)?)
}

impl Args {
    /// 第一个位置参数：属性名、context 名或 `NAME`，具体含义取决于模式。
    fn name(&self) -> Option<&String> {
        self.arguments.first()
    }

    /// 第二个位置参数，只有 set 和 wait-for-value 模式会用到。
    fn value(&self) -> Option<&String> {
        self.arguments.get(1)
    }
}

/// CLI 入口，正常路径不会返回。
///
/// 退出码与 Magisk 一致：成功为 0，`--wait` 超时为 2，其余错误为 1。
pub fn resetprop_main(args: &[String]) -> ! {
    if let Err(err) = run_from_args(args) {
        let code = if err.downcast_ref::<WaitTimeoutError>().is_some() {
            2
        } else {
            1
        };
        eprintln!("resetprop: {err:#}");
        std::process::exit(code);
    }
    std::process::exit(0);
}

/// resetprop multicall 与子命令的入口。
///
/// `args` 应包含 argv[0]（程序名）。
fn run_from_args(args: &[String]) -> Result<()> {
    let cli = match Args::try_parse_from(args) {
        Ok(cli) => cli,
        Err(err) => {
            if matches!(
                err.kind(),
                ErrorKind::DisplayHelp | ErrorKind::DisplayVersion
            ) {
                err.print()?;
                return Ok(());
            }
            return Err(anyhow::anyhow!("{err}"));
        }
    };

    // 解析成功后才初始化，保证 --help/--version 不会触碰属性区。
    sys_prop::init().context("Failed to initialize system property API")?;

    let rp = ResetProp {
        skip_svc: cli.skip_svc,
        persistent: cli.persistent,
        persist_only: cli.persist_only,
        verbose: cli.verbose,
        show_context: cli.show_context,
        // 重建操作走下面的专用方法，而不是靠这个结构体开关。
        rebuild: false,
    };

    // 校验：最多只能有一种特殊模式
    // 这几种特殊模式对位置参数的解释各不相同，同时使用会产生歧义。
    let special_modes = u8::from(cli.wait) + u8::from(cli.delete) + u8::from(cli.file.is_some());
    if special_modes > 1 {
        bail!("multiple operation modes detected");
    }

    // -c 只能单独使用，或紧跟在 delete 之后（压缩 delete 留下的空洞）；与 set/wait/file 组合会
    // 悄悄忽略其中一个请求。
    if cli.rebuild && !(special_modes == 0 || cli.delete) {
        bail!("Only -d can be used with -c");
    }

    // -w：等待模式
    if cli.wait {
        let name = cli.name().context("--wait requires a property name")?;
        let ok = rp
            .wait(
                name,
                cli.value().map(std::string::String::as_str),
                cli.timeout,
            )
            .context("wait failed")?;
        if !ok {
            // 这不是查询失败：用专门的错误表示超时，让调用方以退出码 2 结束。
            return Err(WaitTimeoutError {
                name: name.to_owned(),
            }
            .into());
        }
        return Ok(());
    }

    // -f：从文件加载
    if let Some(path) = &cli.file {
        let file = File::open(path).with_context(|| format!("Failed to open {path}"))?;
        let reader = BufReader::new(file);
        if rp
            .load_props(reader.lines())
            .context("Failed to load properties from file")?
        {
            // 返回 true 说明属性区变得异常（碎片化）；值已经写入，但建议之后执行一次 `-c` 重建。
            eprintln!("resetprop: warning: rebuild is needed!");
        }
        return Ok(());
    }

    // -d：删除
    if cli.delete {
        let name = cli.name().context("--delete requires a property name")?;
        let deleted = rp.delete(name).context("delete failed")?;
        if !deleted {
            bail!("{name} not found");
        }
        if !cli.rebuild {
            return Ok(());
        }
    }

    if cli.rebuild {
        if let Some(name) = cli.name() {
            // 没有 -Z/-d 时，位置参数本身就是 context 名；带上这两个标志时它是属性名，
            // 需要通过该属性当前的 context 找到要压缩的区域。
            let ctx = if cli.show_context || cli.delete {
                sys_prop::get_context(name)?
            } else {
                name.to_owned()
            };
            rp.rebuild(&ctx)?;
        } else if !rp.rebuild_all(cli.force)? {
            eprintln!("Something wrong happened, see log for detail.");
            std::process::exit(1);
        }
        return Ok(());
    }

    let name = cli.name();
    let value = cli.value();

    match (name, value) {
        // resetprop name value（设置）
        (Some(name), Some(value)) => {
            if rp
                .set(name, value)
                .with_context(|| format!("Failed to set {name}"))?
            {
                // 值已经写入，但属性区需要压缩才能保持高效。
                eprintln!("resetprop: warning: rebuild is needed!");
            }
        }

        // resetprop name（读取）
        (Some(name), None) => match rp.get(name) {
            Some(val) => println!("{val}"),
            None => bail!("{name} not found"),
        },

        // resetprop（列出全部）
        (None, None) => {
            let props = rp.list_all().context("Failed to list properties")?;
            for (name, value) in &props {
                println!("[{name}]: [{value}]");
            }
        }

        // resetprop <无 name> <value> — 非法
        (None, Some(_)) => {
            bail!("property name is required");
        }
    }

    Ok(())
}

/// 使用内部 resetprop API 加载 system.prop 文件。
///
/// 等价于 `resetprop -n --file <path>`。
pub fn load_system_prop_file(path: &Path) -> Result<()> {
    sys_prop::init().context("Failed to initialize system property API")?;

    let rp = ResetProp {
        // -n / skip-svc：直接写属性区，而不是请求 property_service，这样模块才能覆盖那些
        // service 会拒绝该调用方修改的属性。
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    };

    let file = File::open(path).with_context(|| format!("Failed to open {}", path.display()))?;
    let reader = BufReader::new(file);
    if rp
        .load_props(reader.lines())
        .with_context(|| format!("Failed to load properties from {}", path.display()))?
    {
        log::warn!(
            "warning: after loaded prop file from {}, rebuild is needed!",
            path.display()
        );
    }

    info!("Loaded system.prop from {}", path.display());
    Ok(())
}
