//! ksud 的版本信息，以及与内核、模块、Manager 应用共享的固定路径。
//!
//! `android` 模块中的内容只在设备上有意义；主机构建只使用这里的版本常量。

#[cfg(target_os = "android")]
mod android {
    use const_format::concatcp;
    use std::time::Duration;

    // 一个启动阶段等待模块脚本的最长时间；超时后后续脚本不再等待直接启动
    // （参见 `ScriptWait::Until`）。
    pub const BOOT_STAGE_TIMEOUT: Duration = Duration::from_secs(35);
    // 热安装（无需重启）模块安装脚本的执行上限。
    pub const HOT_INSTALL_SCRIPT_TIMEOUT: Duration = Duration::from_secs(30);
    // 模拟软重启辅助程序的最长等待时间。
    pub const EMULATED_SOFT_REBOOT_TIMEOUT: Duration = Duration::from_secs(5);
    // 等待 /dev/waitsys 一类同步机制的超时时间。
    pub const WAITSYS_READY_TIMEOUT: Duration = Duration::from_secs(2);
    pub const WAITSYS_STOP_TIMEOUT: Duration = Duration::from_secs(5);
    // 收集 `logcat`/`dmesg` 启动日志时 `timeout -s 9` 的时长。
    pub const BOOTLOG_TIMEOUT: &str = "30s";

    pub const ADB_DIR: &str = "/data/adb/";
    // ksud 私有状态目录（二进制、日志、配置档案、备份）。
    pub const WORKING_DIR: &str = concatcp!(ADB_DIR, "ksu/");
    // 安装时从 APK 解压出的 busybox 及辅助二进制。
    pub const BINARY_DIR: &str = concatcp!(WORKING_DIR, "bin/");
    // 共享库（目前是 adb root 辅助库）。
    pub const LIBRARY_DIR: &str = concatcp!(WORKING_DIR, "lib/");
    pub const LOG_DIR: &str = concatcp!(WORKING_DIR, "log/");
    // 防止启动多个 sulogd 实例的锁文件。
    pub const SULOGD_LOCK_PATH: &str = concatcp!(WORKING_DIR, "sulogd.lock");

    // 各应用的 root 配置档案及其 SELinux 规则模板。
    pub const PROFILE_DIR: &str = concatcp!(WORKING_DIR, "profile/");
    pub const PROFILE_SELINUX_DIR: &str = concatcp!(PROFILE_DIR, "selinux/");
    pub const PROFILE_TEMPLATE_DIR: &str = concatcp!(PROFILE_DIR, "templates/");

    // root shell 启动时可选加载的 rc 文件（兼容 Magisk）。
    pub const KSURC_PATH: &str = concatcp!(WORKING_DIR, ".ksurc");
    // ksud 二进制文件的标准位置，脚本默认从这里调用。
    pub const DAEMON_PATH: &str = concatcp!(ADB_DIR, "ksud");
    pub const LIBADBROOT_PATH: &str = concatcp!(LIBRARY_DIR, "libadbroot.so");

    // 方便模块和 `su` 通过 PATH 直接调用 `ksud` 的软链接。
    pub const DAEMON_LINK_PATH: &str = concatcp!(BINARY_DIR, "ksud");

    // 已安装模块、待下次启动应用的暂存更新，以及唯一活动 metamodule 的
    // 软链接目标。
    pub const MODULE_DIR: &str = concatcp!(ADB_DIR, "modules/");
    pub const MODULE_UPDATE_DIR: &str = concatcp!(ADB_DIR, "modules_update/");
    pub const METAMODULE_DIR: &str = concatcp!(ADB_DIR, "metamodule/");
    // 被替换的模块目录会暂存于此，直到下次开机
    pub const HOT_INSTALL_TRASH_DIR: &str = concatcp!(WORKING_DIR, "hot_install_trash/");

    // 优先顺序：/metadata/vold（root:root 0700）隐藏性最好，且不会
    // 增加 /metadata 的 nlink；否则用 /metadata/watchdog（与 Magisk 一致）；再否则用 /metadata。
    pub const PREINIT_DIR_VOLD: &str = "/metadata/vold/ksu/";
    pub const PREINIT_DIR_WATCHDOG: &str = "/metadata/watchdog/ksu/";
    pub const PREINIT_DIR_DEFAULT: &str = "/metadata/ksu/";
    pub const METADATA_FILE_CON: &str = "u:object_r:metadata_file:s0";
    pub const VOLD_METADATA_FILE_CON: &str = "u:object_r:vold_metadata_file:s0";
    pub const MODULES_RC_FILE: &str = "modules.rc";
    pub const MODULES_RC_TMP_FILE: &str = ".modules.rc.tmp";
    // 模块 preinit 目录会同步到 PREINIT_DIR_*/preinit/<id>/，由内核的 runner 执行
    pub const PREINIT_SUBDIR: &str = "preinit";
    pub const PREINIT_MANIFEST_FILE: &str = ".manifest";
    pub const PREINIT_BOOTCOUNT_FILE: &str = ".preinit_bootcount";
    pub const PREINIT_STATUS_PATH: &str = concatcp!(WORKING_DIR, "preinit_status");

    pub const MODULE_WEB_DIR: &str = "webroot";
    pub const MODULE_ACTION_SH: &str = "action.sh";
    pub const DISABLE_FILE_NAME: &str = "disable";
    pub const UPDATE_FILE_NAME: &str = "update";
    pub const REMOVE_FILE_NAME: &str = "remove";
    pub const MODULE_INIT_RC_DIR: &str = "initrc";
    pub const MODULE_PREINIT_DIR: &str = "preinit";
    pub const PREINIT_CONF: &str = "preinit.conf";
    // 当 customize.sh 请求热安装时，由 installer.sh 写入
    pub const HOT_INSTALL_MARKER: &str = ".ksu_hot_install";

    // 模块配置系统：每个模块 id 一个目录，分为持久配置和每次启动的临时配置
    // （临时配置优先）。
    pub const MODULE_CONFIG_DIR: &str = concatcp!(WORKING_DIR, "module_configs/");
    pub const PERSIST_CONFIG_NAME: &str = "persist.config";
    pub const TEMP_CONFIG_NAME: &str = "tmp.config";

    // metamodule 可提供的钩子脚本，ksud 会在普通模块前后调用。
    pub const METAMODULE_MOUNT_SCRIPT: &str = "metamount.sh";
    pub const METAMODULE_METAINSTALL_SCRIPT: &str = "metainstall.sh";
    pub const METAMODULE_METAUNINSTALL_SCRIPT: &str = "metauninstall.sh";

    // BootRestore / 卸载时使用的原厂启动镜像备份。
    pub const KSU_BACKUP_DIR: &str = WORKING_DIR;
    pub const KSU_BACKUP_FILE_PREFIX: &str = "ksu_backup_";
    pub const BACKUP_FILENAME: &str = "stock_image.sha1";
    // 安装器暂存在 /data 下的备份，安装时移动到上述目录。
    pub const KSU_TEMP_BACKUP_DIR_NAME: &str = "boot_backup";

    pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");
}

// 由 build.rs 从 Gradle 构建参数注入，确保二进制与 Manager 版本一致。
#[allow(unused)]
pub const VERSION_CODE: &str = env!("VERSION_CODE");
pub const VERSION_NAME: &str = env!("VERSION_NAME");
// `--version` 的输出；附带 UAPI 版本，方便在反馈中直接看出用户态与内核
// 是否匹配。
#[cfg(target_os = "android")]
pub const FULL_VERSION: &str = const_format::formatcp!(
    "{VERSION_NAME} (uapi: {})",
    crate::ksu_uapi::KERNEL_SU_UAPI_VERSION
);

#[cfg(target_os = "android")]
pub use android::*;
