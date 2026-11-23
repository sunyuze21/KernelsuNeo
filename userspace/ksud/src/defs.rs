//! ksud 的版本信息，以及与内核、模块、Manager 应用共享的固定路径。
//!
//! `android` 模块中的内容只在设备上有意义；主机构建只使用这里的版本常量。

/// 仅在 Android 目标上编译的路径与超时常量集合。
#[cfg(target_os = "android")]
mod android {
    use const_format::concatcp;
    use std::time::Duration;

    // 一个启动阶段等待模块脚本的最长时间；超时后后续脚本不再等待直接启动
    // （参见 `ScriptWait::Until`）。
    /// 单个启动阶段等待脚本的上限；超时只影响等待，不终止已启动的脚本。
    pub const BOOT_STAGE_TIMEOUT: Duration = Duration::from_secs(35);
    // 热安装（无需重启）模块安装脚本的执行上限。
    /// 热安装安装脚本的最长执行时间，防止安装流程卡住开机。
    pub const HOT_INSTALL_SCRIPT_TIMEOUT: Duration = Duration::from_secs(30);
    // 模拟软重启辅助程序的最长等待时间。
    /// 模拟软重启时等待辅助程序完成的超时。
    pub const EMULATED_SOFT_REBOOT_TIMEOUT: Duration = Duration::from_secs(5);
    // 等待 /dev/waitsys 一类同步机制的超时时间。
    /// 等待 waitsys 设备就绪的超时。
    pub const WAITSYS_READY_TIMEOUT: Duration = Duration::from_secs(2);
    /// 等待 waitsys 停止的超时。
    pub const WAITSYS_STOP_TIMEOUT: Duration = Duration::from_secs(5);
    // 收集 `logcat`/`dmesg` 启动日志时 `timeout -s 9` 的时长。
    /// 启动日志采集进程的存活时长，按 `timeout` 接受的时间字符串格式书写。
    pub const BOOTLOG_TIMEOUT: &str = "30s";

    /// Android 的 adb 数据根目录，所有持久化状态都位于其下。
    pub const ADB_DIR: &str = "/data/adb/";
    // ksud 私有状态目录（二进制、日志、配置档案、备份）。
    /// ksud 私有状态根目录；包含后续所有 `WORKING_DIR` 派生路径。
    pub const WORKING_DIR: &str = concatcp!(ADB_DIR, "ksu/");
    // 安装时从 APK 解压出的 busybox 及辅助二进制。
    /// 解压后的辅助二进制目录；模块通过 PATH 调用这里的工具。
    pub const BINARY_DIR: &str = concatcp!(WORKING_DIR, "bin/");
    // 共享库（目前是 adb root 辅助库）。
    /// 运行时共享库目录，目前存放 adb root 辅助库。
    pub const LIBRARY_DIR: &str = concatcp!(WORKING_DIR, "lib/");
    /// 启动日志与运行日志目录。
    pub const LOG_DIR: &str = concatcp!(WORKING_DIR, "log/");
    // 防止启动多个 sulogd 实例的锁文件。
    /// sulogd 单实例锁文件路径。
    pub const SULOGD_LOCK_PATH: &str = concatcp!(WORKING_DIR, "sulogd.lock");

    // 各应用的 root 配置档案及其 SELinux 规则模板。
    /// 每个应用 root 配置档案的根目录。
    pub const PROFILE_DIR: &str = concatcp!(WORKING_DIR, "profile/");
    /// 每个包名对应的已应用 sepolicy 规则目录。
    pub const PROFILE_SELINUX_DIR: &str = concatcp!(PROFILE_DIR, "selinux/");
    /// Profile 模板存储目录，按模板 id 命名。
    pub const PROFILE_TEMPLATE_DIR: &str = concatcp!(PROFILE_DIR, "templates/");

    // root shell 启动时可选加载的 rc 文件（兼容 Magisk）。
    /// 交互式 root shell 启动时加载的兼容脚本，沿用 Magisk 的约定。
    pub const KSURC_PATH: &str = concatcp!(WORKING_DIR, ".ksurc");
    // ksud 二进制文件的标准位置，脚本默认从这里调用。
    /// ksud 自身的标准安装路径。
    pub const DAEMON_PATH: &str = concatcp!(ADB_DIR, "ksud");
    /// adb root 辅助共享库的安装路径。
    pub const LIBADBROOT_PATH: &str = concatcp!(LIBRARY_DIR, "libadbroot.so");

    // 方便模块和 `su` 通过 PATH 直接调用 `ksud` 的软链接。
    /// BINARY_DIR 中指向 DAEMON_PATH 的软链接，使 `ksud` 可通过 PATH 命中。
    pub const DAEMON_LINK_PATH: &str = concatcp!(BINARY_DIR, "ksud");

    // 已安装模块、待下次启动应用的暂存更新，以及唯一活动 metamodule 的
    // 软链接目标。
    /// 已安装模块目录；每个子目录是一个模块 id。
    pub const MODULE_DIR: &str = concatcp!(ADB_DIR, "modules/");
    /// 热安装/升级的暂存目录，post-fs-data 时合并进 MODULE_DIR。
    pub const MODULE_UPDATE_DIR: &str = concatcp!(ADB_DIR, "modules_update/");
    /// 指向当前唯一活动 metamodule 的软链接。
    pub const METAMODULE_DIR: &str = concatcp!(ADB_DIR, "metamodule/");
    // 被替换的模块目录会暂存于此，直到下次开机
    /// 被替换的旧模块目录暂存区，直到下次开机再清理。
    pub const HOT_INSTALL_TRASH_DIR: &str = concatcp!(WORKING_DIR, "hot_install_trash/");

    // 优先顺序：/metadata/vold（root:root 0700）隐藏性最好，且不会
    // 增加 /metadata 的 nlink；否则用 /metadata/watchdog（与 Magisk 一致）；再否则用 /metadata。
    /// preinit 状态的首选隐藏位置（vold 元数据目录）。
    pub const PREINIT_DIR_VOLD: &str = "/metadata/vold/ksu/";
    /// preinit 状态的次选位置，与 Magisk 的 watchdog 目录保持一致。
    pub const PREINIT_DIR_WATCHDOG: &str = "/metadata/watchdog/ksu/";
    /// preinit 状态在 /metadata 根下的兜底位置。
    pub const PREINIT_DIR_DEFAULT: &str = "/metadata/ksu/";
    /// /metadata 下普通文件的 SELinux 标签。
    pub const METADATA_FILE_CON: &str = "u:object_r:metadata_file:s0";
    /// vold 元数据目录中文件的 SELinux 标签。
    pub const VOLD_METADATA_FILE_CON: &str = "u:object_r:vold_metadata_file:s0";
    /// 由 ksud 生成、供内核读取的模块 rc 文件名。
    pub const MODULES_RC_FILE: &str = "modules.rc";
    /// 写入 modules.rc 前使用的临时文件名，保证替换原子性。
    pub const MODULES_RC_TMP_FILE: &str = ".modules.rc.tmp";
    // 模块 preinit 目录会同步到 PREINIT_DIR_*/preinit/<id>/，由内核的 runner 执行
    /// preinit 目录下按模块 id 组织的子目录名。
    pub const PREINIT_SUBDIR: &str = "preinit";
    /// preinit 目录的清单文件，记录同步过的模块集合。
    pub const PREINIT_MANIFEST_FILE: &str = ".manifest";
    /// 记录 preinit 连续启动失败次数的文件。
    pub const PREINIT_BOOTCOUNT_FILE: &str = ".preinit_bootcount";
    /// 供 CLI/工具读取的 preinit 状态摘要路径。
    pub const PREINIT_STATUS_PATH: &str = concatcp!(WORKING_DIR, "preinit_status");

    /// 模块内 Web UI 资源子目录名。
    pub const MODULE_WEB_DIR: &str = "webroot";
    /// 模块 action 脚本文件名，供 Manager 触发。
    pub const MODULE_ACTION_SH: &str = "action.sh";
    /// 存在于模块目录时表示该模块被禁用。
    pub const DISABLE_FILE_NAME: &str = "disable";
    /// 存在于模块目录时表示该模块等待更新。
    pub const UPDATE_FILE_NAME: &str = "update";
    /// 存在于模块目录时表示该模块等待卸载。
    pub const REMOVE_FILE_NAME: &str = "remove";
    /// 模块提供的 initrc 片段目录名。
    pub const MODULE_INIT_RC_DIR: &str = "initrc";
    /// 模块提供的 preinit 脚本目录名。
    pub const MODULE_PREINIT_DIR: &str = "preinit";
    /// 模块 preinit 配置文件文件名。
    pub const PREINIT_CONF: &str = "preinit.conf";
    // 当 customize.sh 请求热安装时，由 installer.sh 写入
    /// 热安装标记文件名，安装器与 ksud 据此切换安装流程。
    pub const HOT_INSTALL_MARKER: &str = ".ksu_hot_install";

    // 模块配置系统：每个模块 id 一个目录，分为持久配置和每次启动的临时配置
    // （临时配置优先）。
    /// 模块配置根目录，每个模块 id 对应一个子目录。
    pub const MODULE_CONFIG_DIR: &str = concatcp!(WORKING_DIR, "module_configs/");
    /// 模块持久配置文件名，重启后仍然生效。
    pub const PERSIST_CONFIG_NAME: &str = "persist.config";
    /// 模块临时配置文件名，每次 post-fs-data 清空。
    pub const TEMP_CONFIG_NAME: &str = "tmp.config";

    // metamodule 可提供的钩子脚本，ksud 会在普通模块前后调用。
    /// metamodule 的挂载钩子脚本名。
    pub const METAMODULE_MOUNT_SCRIPT: &str = "metamount.sh";
    /// metamodule 在模块安装前执行的钩子脚本名。
    pub const METAMODULE_METAINSTALL_SCRIPT: &str = "metainstall.sh";
    /// metamodule 在模块卸载前执行的钩子脚本名。
    pub const METAMODULE_METAUNINSTALL_SCRIPT: &str = "metauninstall.sh";

    // BootRestore / 卸载时使用的原厂启动镜像备份。
    /// 原厂启动镜像备份所在目录，与 WORKING_DIR 相同。
    pub const KSU_BACKUP_DIR: &str = WORKING_DIR;
    /// 原厂镜像备份文件名前缀，后接分区名或槽位。
    pub const KSU_BACKUP_FILE_PREFIX: &str = "ksu_backup_";
    /// 记录原厂镜像校验和的文件名，用于检测镜像是否被替换。
    pub const BACKUP_FILENAME: &str = "stock_image.sha1";
    // 安装器暂存在 /data 下的备份，安装时移动到上述目录。
    /// 安装器在 /data 下使用的临时备份目录名。
    pub const KSU_TEMP_BACKUP_DIR_NAME: &str = "boot_backup";

    /// 默认 Manager 包名，由构建参数注入；CLI 默认值与调试命令共用。
    pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");
}

// 由 build.rs 从 Gradle 构建参数注入，确保二进制与 Manager 版本一致。
/// Manager 构建号，用于兼容性判断；主机构建中可能未使用。
#[allow(unused)]
pub const VERSION_CODE: &str = env!("VERSION_CODE");
/// 面向用户展示的版本名，由构建参数注入。
pub const VERSION_NAME: &str = env!("VERSION_NAME");
// `--version` 的输出；附带 UAPI 版本，方便在反馈中直接看出用户态与内核
// 是否匹配。
/// 完整版本字符串；附加 UAPI 版本号，便于判断用户态与内核协议是否匹配。
#[cfg(target_os = "android")]
pub const FULL_VERSION: &str = const_format::formatcp!(
    "{VERSION_NAME} (uapi: {})",
    crate::ksu_uapi::KERNEL_SU_UAPI_VERSION
);

#[cfg(target_os = "android")]
pub use android::*;
