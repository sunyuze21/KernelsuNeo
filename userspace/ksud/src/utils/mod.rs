//! 通用小工具的分层入口。
//!
//! 这里只保留模块导出和跨子模块共用的宏；具体实现按职责拆分：
//!
//! - [`fs`]：目录/文件/可执行文件的创建与校验；
//! - [`install`]：把 ksud 安装到设备，以及完整卸载；
//! - [`process`]：命名空间、cgroup、守护进程化和进程组脱离；
//! - [`property`]：Android 属性读取、安全模式判断。

mod fs;
mod install;
mod process;
mod property;

pub use fs::*;
pub use install::*;
pub use process::*;
pub use property::*;

/// 在编译期选择调试版或发布版取值。
#[macro_export]
macro_rules! debug_select {
    ($debug:expr, $release:expr) => {{
        #[cfg(debug_assertions)]
        {
            $debug
        }
        #[cfg(not(debug_assertions))]
        {
            $release
        }
    }};
}
