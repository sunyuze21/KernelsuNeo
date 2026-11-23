//! Android 属性读取与依赖属性的判断。

use crate::ksucalls;
use std::ffi::{CStr, CString, c_char, c_void};

// 直接绑定 bionic 的属性 API。若每次读取都调用 getprop(1)，启动早期会频繁
// fork 进程，代价过高。
/// bionic 属性读取回调签名。
///
/// `cookie` 由调用方透传，回调必须同步完成读取；属性值指针只在回调期间有效。
type PropertyReadCallback = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char, u32);

unsafe extern "C" {
    /// 在属性区中查找属性，返回内部 property_info 指针；不存在时返回空指针。
    fn __system_property_find(name: *const c_char) -> *const c_void;
    /// 通过回调读出属性名/值/序号；`property_info` 必须来自 `__system_property_find`。
    fn __system_property_read_callback(
        property_info: *const c_void,
        callback: PropertyReadCallback,
        cookie: *mut c_void,
    );
}

/// 由 `__system_property_read_callback` 同步回调；cookie 是 `getprop` 持有的
/// `*mut Option<String>`。
unsafe extern "C" fn property_read_callback(
    cookie: *mut c_void,
    _name: *const c_char,
    value: *const c_char,
    _serial: u32,
) {
    if cookie.is_null() || value.is_null() {
        return;
    }

    let result = unsafe { &mut *cookie.cast::<Option<String>>() };
    let value = unsafe { CStr::from_ptr(value) };
    *result = Some(value.to_string_lossy().into_owned());
}

/// 读取 Android 系统属性；属性不存在时返回 `None`。
pub fn getprop(name: &str) -> Option<String> {
    // 属性名必须无内嵌 NUL；转换失败直接当作不存在
    let name = CString::new(name).ok()?;
    // property_info 指向 bionic 内部属性区，仅在下一次属性重载前有效
    let property_info = unsafe { __system_property_find(name.as_ptr()) };
    if property_info.is_null() {
        return None;
    }

    let mut value = None;
    unsafe {
        __system_property_read_callback(
            property_info,
            property_read_callback,
            std::ptr::addr_of_mut!(value).cast(),
        );
    }
    value
}

/// 安全模式可能来自 Android 属性（用户开机时按键设置），也可能来自 LKM
/// 延迟加载机制持久化的内核标志。
pub fn is_safe_mode() -> bool {
    let safe_mode = getprop("persist.sys.safemode")
        .as_ref()
        .is_some_and(|prop| prop == "1")
        || getprop("ro.sys.safemode")
            .as_ref()
            .is_some_and(|prop| prop == "1");
    log::info!("safemode: {safe_mode}");
    if safe_mode {
        return true;
    }
    let kernel_safe_mode = ksucalls::check_kernel_safemode();
    log::info!("kernel_safemode: {kernel_safe_mode}");
    kernel_safe_mode
}

/// 判断设备上是否存在 Magisk。
///
/// 仅用于提示兼容性/冲突，不参与权限判定。
pub fn has_magisk() -> bool {
    which::which("magisk").is_ok()
}
