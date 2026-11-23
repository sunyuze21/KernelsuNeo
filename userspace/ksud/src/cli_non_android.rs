//! 非 Android（宿主机）构建下 ksud 的命令行入口。
//!
//! 宿主机只需要离线处理 boot 镜像的流程，因此这里仅暴露 boot 镜像
//! 修补/恢复、APK 签名提取和内置 KMI 列表这几条子命令。Android 构建
//! 使用 `cli.rs` 中的完整 CLI。

use anyhow::Result;
use clap::Parser;

use crate::boot_patch::{BootPatchArgs, BootRestoreArgs};
use crate::lkm_image::BootPatchV2Args;
use crate::{apk_sign, defs};

/// 非 Android 平台的 KernelSU 命令行工具
#[derive(Parser, Debug)]
#[command(author, version = defs::VERSION_NAME, about, long_about = None)]
struct Args {
    /// 解析后的子命令，仅是宿主机可用的离线镜像处理能力。
    #[command(subcommand)]
    command: Commands,
}

/// 宿主机子命令集合；刻意保持精简，避免暴露仅设备可用的功能。
#[derive(clap::Subcommand, Debug)]
enum Commands {
    /// 修补 boot 或 init_boot 镜像以应用 KernelSU
    BootPatch(BootPatchArgs),

    /// 恢复被 KernelSU 修补过的 boot 或 init_boot 镜像
    BootRestore(BootRestoreArgs),

    /// 将 KernelSU 修补进 boot 镜像
    ///
    /// 始终操作 boot 镜像，绝不会选择 init_boot 或 vendor_boot。
    BootPatchV2(BootPatchV2Args),

    /// 获取 apk 大小和哈希
    GetSign {
        /// apk 路径
        apk: String,
    },

    /// 显示支持的 KMI 版本
    SupportedKmis,
}

/// 解析宿主机 CLI 并分发到对应子命令。
///
/// 这里而不是 `main` 中初始化 `env_logger`，以免影响使用其他日志后端的
/// Android 构建。执行前记录解析出的命令，便于复现构建日志；失败时既写
/// 日志又返回错误，因为调用 ksud 的脚本看的是退出状态而非日志内容。
pub fn run() -> Result<()> {
    env_logger::init();

    let cli = Args::parse();

    log::info!("command: {:?}", cli.command);

    let result = match cli.command {
        Commands::GetSign { apk } => {
            // sign.0 是 APK 字节数，sign.1 是签名证书哈希；大小用十六进制
            // 输出，与其他工具的输出习惯保持一致。
            let sign = apk_sign::get_apk_signature(&apk)?;
            println!("size: {:#x}, hash: {}", sign.0, sign.1);
            Ok(())
        }

        Commands::BootPatch(boot_patch) => crate::boot_patch::patch(boot_patch),

        Commands::BootRestore(boot_restore) => crate::boot_patch::restore(boot_restore),

        Commands::BootPatchV2(patch) => crate::lkm_image::patch_boot(&patch),

        Commands::SupportedKmis => {
            // 每行一个名字，方便 diff 和 grep。
            let kmi = crate::assets::list_supported_kmi();
            for kmi in &kmi {
                println!("{kmi}");
            }
            Ok(())
        }
    };

    if let Err(e) = &result {
        log::error!("Error: {e:?}");
    }
    result
}
