//
// Created by weishu on 2022/12/9.
//

#ifndef KERNELSU_KSU_H
#define KERNELSU_KSU_H

#include <cstdint>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <utility>

#include "uapi/ksu.h"

// 查询内核侧支持的 UAPI 版本；老内核或未实现该命令时返回 0。
uint32_t get_kernel_uapi_version();

// 查询 Manager 编译时使用的 UAPI 版本，用于与内核版本比较是否兼容。
uint32_t get_manager_uapi_version();

// 获取内核模块版本号；失败返回 0，调用方可回退到 legacy_get_info。
uint32_t get_version();

// 判断指定 UID 是否需要卸载模块（按 app profile 的 umount_modules 策略）。
bool uid_should_umount(int uid);

// 内核是否处于安全模式（通常因启动异常触发，仅加载必要功能）。
bool is_safe_mode();

// 是否为 LKM（可加载内核模块）模式；老内核通过 legacy 通道读取标志。
bool is_lkm_mode();

// LKM 是否为内置捆绑安装（随 Manager 一起刷入），影响升级与卸载提示。
bool is_lkm_bundled();

// 是否为 late-load 模式；该模式下部分功能在启动后才可用。
bool is_late_load_mode();

// 当前进程是否被内核识别为 Manager（决定能否调用管理类 supercall）。
bool is_manager();

// 是否为 PR 构建（预发布版本），用于 UI 提示与更新通道选择。
bool is_pr_build();

// 包名键类型：长度受 uapi 限制，写入时必须保证以 '\0' 结尾。
using p_key_t = char[KSU_MAX_PACKAGE_NAME];

// 写入指定应用的 profile（root/非 root 策略），成功返回 true。
bool set_app_profile(const app_profile *profile);

// 读取指定应用的 profile；返回 0 表示命中，非 0 时调用方应使用默认策略。
int get_app_profile(app_profile *profile);

// Su compat
// 开关 /system/bin/su 到 ksud 的兼容重定向，保持旧版 root 调用方式可用。
bool set_su_enabled(bool enabled);

// 查询 Su compat 是否开启；内核不支持该特性时返回 false。
bool is_su_enabled();

// Kernel umount
// 开关由内核执行的模块卸载策略，避免卸载逻辑被用户态绕过。
bool set_kernel_umount_enabled(bool enabled);

// 查询内核 umount 是否开启；特性不受支持时按关闭处理。
bool is_kernel_umount_enabled();

// SELinux hide
// 开关 SELinux 隐藏；返回 0 成功，失败返回负 errno，调用方可区分“不支持”。
int set_selinux_hide_enabled(bool enabled);

// 查询 SELinux 隐藏是否开启；特性不受支持时返回 false。
bool is_selinux_hide_enabled();

// 批量读取 allowlist；cmd 由调用方按 UAPI 布局分配并初始化。
bool get_allow_list(struct ksu_new_get_allow_list_cmd *);

// 旧内核兼容通道：用 prctl 魔法号读取版本与标志，仅在新 ioctl 不可用时调用。
inline std::pair<int, int> legacy_get_info() {
    int32_t version = -1;
    int32_t flags = 0;
    // prctl 的 result 出参由内核写入状态，这里不需要，仅用于满足调用签名。
    int32_t result = 0;
    prctl(0xDEADBEEF, 2, &version, &flags, &result);
    return {version, flags};
}

#endif //KERNELSU_KSU_H
