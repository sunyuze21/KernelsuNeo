//! `ksud` 的命令行入口。
//!
//! 大多数子命令只是转发，真正的实现在对应模块中。少数入口比较特殊：内核或
//! 模块脚本会用不同的 `argv[0]`（`su`、`resetprop`）调用 ksud，因此必须在
//! clap 解析参数之前先分流处理。

use anyhow::{Context, Ok, Result};
use clap::Parser;
use std::path::PathBuf;

use crate::boot_patch::{BootPatchArgs, BootRestoreArgs};
use crate::lkm_image::BootPatchV2Args;
use crate::module::regenerate_preinit;
use crate::module_integrity_check::{disable_integrity, enable_integrity};
use crate::{
    apk_sign, assets, debug, defs, init_event, ksu_uapi, ksucalls, module, module_config, sulog,
    utils,
};
use android_logger::Config;
use log::{LevelFilter, error, info};

/// KernelSU 用户空间命令行工具
#[derive(Parser, Debug)]
#[command(author, version = defs::FULL_VERSION, about, long_about = None)]
struct Args {
    #[command(subcommand)]
    command: Commands,
}

#[derive(clap::Subcommand, Debug)]
enum Commands {
    /// 管理 KernelSU 模块
    Module {
        #[command(subcommand)]
        command: Module,
    },

    /// 触发 `post-fs-data` 事件
    PostFsData,

    /// 触发 `service` 事件
    Services,

    /// 运行 sulog 读取守护进程。非面向用户，请使用 `ksud debug sulogd` 启动守护进程。
    #[command(hide = true)]
    Sulogd,

    /// 触发 `boot-complete` 事件
    BootCompleted,

    /// 加载 kernelsu.ko 并执行延迟加载阶段的脚本
    LateLoad {
        /// 使用 adb root 执行延迟加载，以便通过 Magica 越狱
        #[arg(long, default_missing_value = "5555", num_args = 0..=1)]
        magica: Option<u16>,

        /// 加载 kernelsu.ko 时传入 allow_shell=1
        #[arg(long)]
        allow_shell: bool,

        /// magica 延迟加载后恢复 adb 属性
        #[arg(long)]
        post_magica: bool,

        /// 指定内核 KMI 版本，而不是自动探测
        #[arg(long)]
        kmi: Option<String>,

        /// manager 包名
        #[arg(long, default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        package_name: String,
    },

    /// 模拟系统重启
    SoftReboot,

    /// 在可访问 kallsyms 的情况下加载内核模块
    Insmod {
        /// 内核模块路径
        module: PathBuf,
        /// 模块加载参数（例如 key=val key2=val2）
        #[arg(trailing_var_arg = true, allow_hyphen_values = true, num_args = 0..)]
        params: Vec<String>,
    },

    /// 将 KernelSU 用户空间组件安装到系统
    Install {
        #[arg(long, default_value = None)]
        libadbroot: Option<PathBuf>,

        #[arg(long, default_value = None)]
        data_path: Option<PathBuf>,
    },

    /// 卸载 KernelSU 内核模块（仅 LKM）
    Unload,

    /// 卸载 KernelSU 模块及其自身（仅 LKM）
    Uninstall {
        #[arg(long, default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        package_name: String,
    },

    /// SELinux 策略修补工具
    Sepolicy {
        #[command(subcommand)]
        command: Sepolicy,
    },

    /// 管理 App Profile
    Profile {
        #[command(subcommand)]
        command: Profile,
    },

    /// 管理内核特性
    Feature {
        #[command(subcommand)]
        command: Feature,
    },

    /// 修补 boot 或 init_boot 镜像以应用 KernelSU
    BootPatch(BootPatchArgs),

    /// 恢复被 KernelSU 修补过的 boot 或 init_boot 镜像
    BootRestore(BootRestoreArgs),

    /// 将 KernelSU 修补进 boot 镜像
    ///
    /// 始终操作 boot 镜像，绝不会选择 init_boot 或 vendor_boot。
    BootPatchV2(BootPatchV2Args),

    /// 显示 boot 信息
    BootInfo {
        #[command(subcommand)]
        command: BootInfo,
    },
    /// 供开发者使用
    Debug {
        #[command(subcommand)]
        command: Debug,
    },
    /// 内核接口
    Kernel {
        #[command(subcommand)]
        command: Kernel,
    },

    /// Resetprop - 兼容 Magisk 的系统属性工具
    #[command(disable_help_flag = true)]
    Resetprop {
        /// 传给 resetprop 的参数
        #[arg(trailing_var_arg = true, allow_hyphen_values = true, num_args = 0..)]
        args: Vec<String>,
    },

    /// 管理 initrc 注入
    Initrc {
        #[command(subcommand)]
        command: Initrc,
    },
}

#[derive(clap::Subcommand, Debug)]
enum BootInfo {
    /// 显示当前 KMI 版本
    CurrentKmi,

    /// 显示支持的 KMI 版本
    SupportedKmis,

    /// 检查设备是否支持 A/B
    IsAbDevice,

    /// 显示自动选择的 boot 分区名
    DefaultPartition,

    /// 列出当前槽位或经 OTA 切换后的槽位可用的分区
    AvailablePartitions,

    /// 显示当前槽位或经 OTA 切换后的槽位的槽位后缀
    SlotSuffix {
        /// 切换到另一个槽位
        #[arg(short = 'u', long, default_value = "false")]
        ota: bool,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Debug {
    /// 设置 manager 应用，需要内核启用 CONFIG_KSU_DEBUG。
    SetManager {
        /// manager 包名
        #[arg(default_value_t = String::from(defs::DEFAULT_PACKAGE_NAME))]
        apk: String,
    },

    /// 获取 apk 大小和哈希
    GetSign {
        /// apk 路径
        apk: String,
    },

    /// Root 命令行
    Su {
        /// 切换到全局挂载命名空间
        #[arg(short, long, default_value = "false")]
        global_mnt: bool,
    },

    /// 获取内核版本
    Version,

    /// 用于测试
    Test,

    /// 将内嵌二进制提取到指定路径
    ExtractBinary {
        /// 二进制名称（例如 busybox、resetprop、bootctl）
        name: String,
        /// 目标文件路径
        path: PathBuf,
    },

    /// 进程标记管理
    Mark {
        #[command(subcommand)]
        command: MarkCommand,
    },

    /// 手动启动 sulogd 守护进程
    Sulogd,

    /// 获取内核信息
    Info,

    /// 打印默认包名
    Package,
}

#[derive(clap::Subcommand, Debug)]
enum MarkCommand {
    /// 获取某个进程（或全部进程）的标记状态
    Get {
        /// 目标 pid（为 0 时表示总计数）
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// 标记一个进程
    Mark {
        /// 目标 pid（为 0 时表示所有进程）
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// 取消标记一个进程
    Unmark {
        /// 目标 pid（为 0 时表示所有进程）
        #[arg(default_value = "0")]
        pid: i32,
    },

    /// 刷新所有运行中进程的标记
    Refresh,
}

#[derive(clap::Subcommand, Debug)]
enum Sepolicy {
    /// 修补 sepolicy
    Patch {
        /// sepolicy 语句
        sepolicy: String,
    },

    /// 从文件应用 sepolicy
    Apply {
        /// sepolicy 文件路径
        file: String,
    },

    /// 检查 sepolicy 语句是否受支持或有效
    Check {
        /// sepolicy 语句
        sepolicy: String,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Module {
    /// 安装模块 <ZIP>
    Install {
        /// 模块 zip 文件路径
        zip: String,
    },

    /// 撤销模块的卸载标记 <id>
    UndoUninstall {
        /// 模块 id
        id: String,
    },

    /// 卸载模块 <id>
    Uninstall {
        /// 模块 id
        id: String,
    },

    /// 启用模块 <id>
    Enable {
        /// 模块 id
        id: String,
    },

    /// 禁用模块 <id>
    Disable {
        // 模块 id
        id: String,
    },

    /// 运行模块 <id> 的 action
    Action {
        // 模块 id
        id: String,
    },

    /// 列出所有模块
    List,

    /// 在 SELinux 策略加载之前运行的 preinit 动作
    Preinit {
        #[command(subcommand)]
        command: PreinitCmd,
    },

    /// 管理模块配置
    Config {
        /// 目标内部模块名（解析为 internal.<name>）
        #[arg(long)]
        internal: Option<String>,
        #[command(subcommand)]
        command: ModuleConfigCmd,
    },
    /// 管理是否验证模块签名
    Integrity {
        #[command(subcommand)]
        command: IntegrityStatus,
    },
}

#[derive(clap::Subcommand, Debug)]
enum IntegrityStatus {
    /// 启用模块签名校验
    Enable,
    /// 关闭模块签名校验
    Disable,
}
#[derive(clap::Subcommand, Debug)]
enum PreinitCmd {
    /// 显示下次开机时各模块的 preinit 状态
    Status,
    /// 在因连续启动失败而禁用 preinit 后重新启用
    Reset,
}

#[derive(clap::Subcommand, Debug)]
enum ModuleConfigCmd {
    /// 获取配置值
    Get {
        /// 配置键
        key: String,
    },

    /// 设置配置值
    Set {
        /// 配置键
        key: String,
        /// 配置值（省略则从 stdin 读取）
        value: Option<String>,
        /// 从 stdin 读取值（未提供 value 时的默认行为）
        #[arg(long)]
        stdin: bool,
        /// 使用临时配置（重启后清空）
        #[arg(short, long)]
        temp: bool,
    },

    /// 列出所有配置项
    List,

    /// 删除配置项
    Delete {
        /// 配置键
        key: String,
        /// 从临时配置中删除
        #[arg(short, long)]
        temp: bool,
    },

    /// 清空所有配置项
    Clear {
        /// 清空临时配置
        #[arg(short, long)]
        temp: bool,
    },
}

#[derive(clap::Subcommand, Debug)]
enum Profile {
    /// 获取 <package-name> 的 root profile selinux 策略
    GetSepolicy {
        /// 包名
        package: String,
    },

    /// 将 <package-name> 的 root profile selinux 策略设置为 <profile>
    SetSepolicy {
        /// 包名
        package: String,
        /// 策略语句
        policy: String,
    },

    /// 获取 <id> 的模板
    GetTemplate {
        /// 模板 id
        id: String,
    },

    /// 将 <id> 的模板设置为 <template string>
    SetTemplate {
        /// 模板 id
        id: String,
        /// 模板字符串
        template: String,
    },

    /// 删除 <id> 的模板
    DeleteTemplate {
        /// 模板 id
        id: String,
    },

    /// 列出所有模板
    ListTemplates,
}

#[derive(clap::Subcommand, Debug)]
enum Feature {
    /// 获取特性值及支持状态
    Get {
        /// 特性 ID 或名称（su_compat, kernel_umount, sulog, adb_root, selinux_hide, shell_su_adb_only）
        id: String,
        /// 从配置文件读取
        #[arg(long, default_value_t = false)]
        config: bool,
    },

    /// 设置特性值
    Set {
        /// 特性 ID 或名称
        id: String,
        /// 特性值（0=禁用，1=启用）
        value: u64,
    },

    /// 列出所有可用特性
    List,

    /// 检查特性状态（supported/unsupported/managed/forced）
    Check {
        /// 特性 ID 或名称（su_compat, kernel_umount, sulog, adb_root, selinux_hide, shell_su_adb_only）
        id: String,
    },

    /// 从文件加载配置并应用到内核
    Load,

    /// 将当前内核特性状态保存到文件
    Save,
}

#[derive(clap::Subcommand, Debug)]
enum Kernel {
    /// 清除 ext4 sysfs
    NukeExt4Sysfs {
        /// 挂载点
        mnt: String,
    },
    /// 管理 umount 列表
    Umount {
        #[command(subcommand)]
        command: UmountOp,
    },
    /// 通知模块已挂载
    NotifyModuleMounted,
}

#[derive(clap::Subcommand, Debug)]
enum UmountOp {
    /// 将挂载点添加到 umount 列表
    Add {
        /// 挂载点路径
        mnt: String,
        /// umount 标志（默认 0，MNT_DETACH: 2）
        #[arg(short, long, default_value = "0")]
        flags: u32,
    },
    /// 从 umount 列表中删除挂载点
    Del {
        /// 挂载点路径
        mnt: String,
    },
    /// 清空 umount 列表中的所有条目
    Wipe,
}

#[derive(clap::Subcommand, Debug)]
enum Initrc {
    /// 重新生成 preinit rc 文件和 preinit 目录
    Refresh,
}

pub fn run() -> Result<()> {
    // 日志写入 logcat，标签为 `KernelSU`。调试构建使用 Trace 级别，
    // 排查模块问题无需重新编译。
    android_logger::init_once(
        Config::default()
            .with_max_level(crate::debug_select!(LevelFilter::Trace, LevelFilter::Info))
            .with_tag("KernelSU"),
    );

    // 内核驱动可能通过 seccomp 过滤器安装，使安装系统调用变成 SIGSYS。
    // 必须在任何驱动访问之前安装好信号处理器。
    ksucalls::setup_sigsys_handler();

    // 内核以 argv[0] = "su" 执行 su 并将其替换为本程序，因此要在 clap
    // 解析 `su` 参数之前按 argv[0] 分流。
    let arg0 = std::env::args().next().unwrap_or_default();
    if arg0 == "su" || arg0.ends_with("/su") {
        return crate::su::root_shell();
    }

    // resetprop 同样是本二进制的软链接/副本，用于兼容 Magisk。
    if arg0.ends_with("resetprop") {
        let all_args: Vec<String> = std::env::args().collect();
        crate::resetprop::resetprop_main(&all_args)
    }

    let cli = Args::parse();

    log::info!("command: {:?}", cli.command);

    let result = match cli.command {
        Commands::PostFsData => init_event::on_post_fs_data(),
        Commands::BootCompleted => {
            init_event::on_boot_completed();
            Ok(())
        }

        Commands::SoftReboot => crate::soft_reboot::soft_reboot(),

        Commands::Insmod { module, params } => debug::insmod(&module, &params),

        Commands::Module { command } => {
            // 模块操作必须在 init 的（全局）挂载命名空间中执行，
            // 这样看到的模块目录才是真实目录，而不是某个应用的视图。
            utils::switch_mnt_ns(1)?;
            match command {
                Module::Install { zip } => module::install_module(&zip),
                Module::UndoUninstall { id } => module::undo_uninstall_module(&id),
                Module::Uninstall { id } => module::uninstall_module(&id),
                Module::Enable { id } => module::enable_module(&id),
                Module::Disable { id } => module::disable_module(&id),
                Module::Action { id } => module::run_action(&id),
                Module::List => module::list_modules(),
                Module::Preinit { command } => match command {
                    PreinitCmd::Status => {
                        crate::preinit::print_status();
                        Ok(())
                    }
                    PreinitCmd::Reset => crate::preinit::reset_bootcount(),
                },
                Module::Integrity { command } => match command {
                    IntegrityStatus::Enable => {
                        enable_integrity()?;
                        println!("Module signature verification enabled");
                        Ok(())
                    }
                    IntegrityStatus::Disable => {
                        disable_integrity()?;
                        println!("Module signature verification disabled");
                        Ok(())
                    }
                },
                Module::Config { internal, command } => {
                    // 模块脚本的环境变量中带有 `KSU_MODULE`；
                    // `--internal` 用于 ksud 自有的伪模块。
                    let module_id = match internal {
                        Some(internal_name) => format!("internal.{internal_name}"),
                        None => std::env::var("KSU_MODULE").map_err(|_| {
                            anyhow::anyhow!(
                                "This command must be run in the context of a module or passed --internal <name>"
                            )
                        })?,
                    };
                    crate::module::validate_module_id(&module_id)?;

                    match command {
                        ModuleConfigCmd::Get { key } => {
                            // 使用 merge_configs 以遵循优先级（temp 覆盖 persist）
                            let config = module_config::merge_configs(&module_id)?;
                            match config.get(&key) {
                                Some(value) => {
                                    println!("{value}");
                                    Ok(())
                                }
                                None => anyhow::bail!("Key '{key}' not found"),
                            }
                        }
                        ModuleConfigCmd::Set {
                            key,
                            value,
                            stdin,
                            temp,
                        } => {
                            // 在 CLI 层校验 key，以便提供更好的用户体验
                            module_config::validate_config_key(&key)?;

                            // 从 stdin 或参数读取值
                            let value_str = match value {
                                Some(v) if !stdin => v,
                                _ => {
                                    // 从 stdin 读取
                                    use std::io::Read;
                                    let mut buffer = String::new();
                                    std::io::stdin()
                                        .read_to_string(&mut buffer)
                                        .context("Failed to read from stdin")?;
                                    buffer
                                }
                            };

                            // 校验值
                            module_config::validate_config_value(&value_str)?;

                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::set_config_value(
                                &module_id,
                                &key,
                                &value_str,
                                config_type,
                            )
                        }
                        ModuleConfigCmd::List => {
                            let config = module_config::merge_configs(&module_id)?;
                            if config.is_empty() {
                                println!("No config entries found");
                            } else {
                                for (key, value) in config {
                                    println!("{key}={value}");
                                }
                            }
                            Ok(())
                        }
                        ModuleConfigCmd::Delete { key, temp } => {
                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::delete_config_value(&module_id, &key, config_type)
                        }
                        ModuleConfigCmd::Clear { temp } => {
                            let config_type = if temp {
                                module_config::ConfigType::Temp
                            } else {
                                module_config::ConfigType::Persist
                            };
                            module_config::clear_config(&module_id, config_type)
                        }
                    }
                }
            }
        }
        Commands::Install {
            libadbroot,
            data_path,
        } => utils::install(libadbroot, data_path),
        Commands::Unload => crate::unload::unload(),
        Commands::Uninstall { package_name } => utils::uninstall(&package_name),
        Commands::Sepolicy { command } => match command {
            Sepolicy::Patch { sepolicy } => crate::sepolicy::live_patch(&sepolicy),
            Sepolicy::Apply { file } => crate::sepolicy::apply_file(file),
            Sepolicy::Check { sepolicy } => crate::sepolicy::check_rule(&sepolicy),
        },
        Commands::LateLoad {
            magica,
            allow_shell,
            post_magica,
            kmi,
            package_name,
        } => {
            // `--magica` 走基于 adb root 的延迟加载路径，
            // 与下面正常的 LKM 加载流程相互独立。
            if let Some(port) = magica {
                return crate::magica::run(port, &package_name, allow_shell).map_err(|e| {
                    error!("Error running magica: {e}");
                    e
                });
            }
            let result = crate::late_load::run(&package_name, kmi, allow_shell);
            if post_magica {
                info!("Restoring adb properties (post-magica cleanup)...");
                if let Err(e) = crate::magica::disable_adb_root() {
                    error!("disable adb root failed: {e}");
                }
            }
            result
        }
        Commands::Services => {
            // 即使内核部分加载失败（例如从非 KernelSU 内核启动），init 仍可能
            // 触发 `service`；此时应正常退出，避免每次开机都报错。
            if ksucalls::get_version() <= 0 {
                info!("KernelSU not available, exiting services");
                std::process::exit(0);
            }
            init_event::on_services();
            Ok(())
        }
        Commands::Sulogd => sulog::run_sulogd(),
        Commands::Profile { command } => match command {
            Profile::GetSepolicy { package } => crate::profile::get_sepolicy(&package),
            Profile::SetSepolicy { package, policy } => {
                crate::profile::set_sepolicy(&package, policy)
            }
            Profile::GetTemplate { id } => crate::profile::get_template(&id),
            Profile::SetTemplate { id, template } => crate::profile::set_template(&id, template),
            Profile::DeleteTemplate { id } => crate::profile::delete_template(&id),
            Profile::ListTemplates => crate::profile::list_templates(),
        },

        Commands::Feature { command } => match command {
            Feature::Get { id, config } => {
                if config {
                    crate::feature::get_feature_config(&id)
                } else {
                    crate::feature::get_feature(&id)
                }
            }
            Feature::Set { id, value } => crate::feature::set_feature(&id, value),
            Feature::List => {
                crate::feature::list_features();
                Ok(())
            }
            Feature::Check { id } => crate::feature::check_feature(&id),
            Feature::Load => crate::feature::load_config_and_apply(),
            Feature::Save => crate::feature::save_config(),
        },

        Commands::Debug { command } => match command {
            Debug::SetManager { apk } => debug::set_manager(&apk),
            Debug::GetSign { apk } => {
                let sign = apk_sign::get_apk_signature(&apk)?;
                println!("size: {:#x}, hash: {}", sign.0, sign.1);
                Ok(())
            }
            Debug::Version => {
                println!("Kernel Version: {}", ksucalls::get_version());
                Ok(())
            }
            Debug::Su { global_mnt } => crate::su::grant_root(global_mnt),
            Debug::Test => assets::ensure_binaries(false),
            Debug::ExtractBinary { name, path } => {
                let data = assets::get_asset_data(&name)?;
                utils::ensure_binary(&path, &data, false)
            }
            Debug::Mark { command } => match command {
                MarkCommand::Get { pid } => debug::mark_get(pid),
                MarkCommand::Mark { pid } => debug::mark_set(pid),
                MarkCommand::Unmark { pid } => debug::mark_unset(pid),
                MarkCommand::Refresh => debug::mark_refresh(),
            },
            Debug::Sulogd => sulog::ensure_sulogd_running(),
            Debug::Info => {
                let info = ksucalls::get_info();
                println!("version: {}", info.version);
                println!("flags: 0x{:x}", info.flags);
                println!("uapi_version: {}", info.uapi_version);
                println!("features: 0x{:x}", info.features);
                println!("lkm: {}", ksucalls::is_lkm());
                println!(
                    "bundled: {}",
                    (info.flags & ksu_uapi::KSU_GET_INFO_FLAG_BUNDLED) != 0
                );
                println!("late_load: {}", ksucalls::is_late_load());
                println!("runtime_mode: {}", ksucalls::runtime_mode());
                println!(
                    "pr_build: {}",
                    (info.flags & ksu_uapi::KSU_GET_INFO_FLAG_PR_BUILD) != 0
                );
                Ok(())
            }
            Debug::Package => {
                println!("{}", defs::DEFAULT_PACKAGE_NAME);
                Ok(())
            }
        },

        Commands::BootPatch(boot_patch) => crate::boot_patch::patch(boot_patch),

        // BootInfo 输出的是供 Manager 和安装脚本解析的机器可读内容。
        // 这里直接返回，避免失败后再打印下面通用的 "Error:" 日志行污染 stdout。
        Commands::BootInfo { command } => {
            return match command {
                BootInfo::CurrentKmi => {
                    let kmi = crate::boot_patch::get_current_kmi()?;
                    println!("{kmi}");
                    Ok(())
                }
                BootInfo::SupportedKmis => {
                    let kmi = crate::assets::list_supported_kmi();
                    for kmi in &kmi {
                        println!("{kmi}");
                    }
                    Ok(())
                }
                BootInfo::IsAbDevice => {
                    let val = crate::utils::getprop("ro.build.ab_update")
                        .unwrap_or_else(|| String::from("false"));
                    let is_ab = val.trim().to_lowercase() == "true";
                    println!("{}", if is_ab { "true" } else { "false" });
                    Ok(())
                }
                BootInfo::DefaultPartition => {
                    let kmi =
                        crate::boot_patch::get_current_kmi().unwrap_or_else(|_| String::new());
                    let name = crate::boot_patch::choose_boot_partition(&kmi, false, &None);
                    println!("{name}");
                    Ok(())
                }
                BootInfo::SlotSuffix { ota } => {
                    let suffix = crate::boot_patch::get_slot_suffix(ota);
                    println!("{suffix}");
                    Ok(())
                }
                BootInfo::AvailablePartitions => {
                    let parts = crate::boot_patch::list_available_partitions();
                    for p in &parts {
                        println!("{p}");
                    }
                    Ok(())
                }
            };
        }
        Commands::BootRestore(boot_restore) => crate::boot_patch::restore(boot_restore),
        Commands::BootPatchV2(patch) => crate::lkm_image::patch_boot(&patch),
        Commands::Resetprop { args } => {
            let mut full_args = vec!["resetprop".to_string()];
            full_args.extend(args);
            crate::resetprop::resetprop_main(&full_args)
        }

        Commands::Kernel { command } => match command {
            Kernel::NukeExt4Sysfs { mnt } => ksucalls::nuke_ext4_sysfs(&mnt),
            Kernel::Umount { command } => match command {
                UmountOp::Add { mnt, flags } => ksucalls::umount_list_add(&mnt, flags),
                UmountOp::Del { mnt } => ksucalls::umount_list_del(&mnt),
                UmountOp::Wipe => ksucalls::umount_list_wipe(),
            },
            Kernel::NotifyModuleMounted => {
                ksucalls::report_module_mounted();
                Ok(())
            }
        },
        Commands::Initrc { command } => match command {
            Initrc::Refresh => regenerate_preinit(),
        },
    };

    // 错误同时写入 logcat；main() 会把 anyhow 错误链打印到 stderr，
    // 因此这里不再重复 "Error:" 前缀。模块脚本失败后通常只能依赖 logcat
    // 留下线索。
    if let Err(e) = &result {
        log::error!("{e:?}");
    }
    result
}
