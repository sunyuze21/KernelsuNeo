//
// Created by weishu on 2022/12/9.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <climits>
#include <sys/syscall.h>
#include <cerrno>
#include "ksu.h"

// 惰性缓存的 [ksu_driver] anon-inode fd；成功后进程内复用，避免每次 supercall 都遍历 /proc/self/fd。
static int fd = -1;

// 在 /proc/self/fd 中按符号链接目标名查找驱动 fd；找不到返回 -1。
// 之所以要扫描，是因为驱动 fd 由 init/zygote 继承而来，Manager 无法重新 open。
static inline int scan_driver_fd() {
    // 驱动 anon-inode 的固定名字，内核侧创建时使用同一字符串。
    const char *kName = "[ksu_driver]";
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) {
        return -1;
    }

    int found = -1;
    struct dirent *de;
    // path 只拼接 fd 编号；target 保存 readlink 结果，需预留 PATH_MAX 以防截断。
    char path[64];
    char target[PATH_MAX];

    while ((de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.') {
            continue;
        }

        char *endptr = NULL;
        long fd_long = strtol(de->d_name, &endptr, 10);
        // 只接受纯十进制 fd 名，跳过 ".", ".." 及非数字条目。
        if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
            continue;
        }

        snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) {
            continue;
        }
        target[n] = '\0';

        // 只比较 basename，避免路径前缀差异导致漏匹配。
        const char *base = strrchr(target, '/');
        base = base ? base + 1 : target;

        if (strstr(base, kName)) {
            found = (int)fd_long;
            break;
        }
    }

    closedir(dir);
    return found;
}

// 统一的 supercall ioctl 封装：首次调用时扫描驱动 fd，之后直接复用。
// static_assert 限制最多一个额外参数，确保与 KSU_IOCTL_* 的命令结构体布局一致。
template<typename... Args>
static int ksuctl(unsigned long op, Args &&... args) {

    if (fd < 0) {
        fd = scan_driver_fd();
    }

    static_assert(sizeof...(Args) <= 1, "ioctl expects at most one extra argument");

    return ioctl(fd, op, std::forward<Args>(args)...);
}

// 缓存 GET_INFO 结果：版本/标志在进程生命周期内不会变化，避免重复 ioctl。
// 注意该结构体字段由内核填写，必须零初始化后再传入。
static struct ksu_get_info_cmd g_version {};

// 读取内核信息；新命令失败时回退到 legacy 命令，并把 uapi_version 标记为 0。
struct ksu_get_info_cmd get_info() {
    if (!g_version.version) {
        if (ksuctl(KSU_IOCTL_GET_INFO, &g_version) < 0) {
            ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &g_version);
            g_version.uapi_version = 0;
        }
    }
    return g_version;
}

// 返回内核 UAPI 版本；0 表示旧内核未提供该字段。
uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

// 返回 Manager 侧 UAPI 版本常量，与内核版本比较可判断是否支持新特性。
uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

// 返回内核模块版本号；调用方需自行兼容 0（未知/旧内核）的情况。
uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

// 读取 allowlist；cmd 需由调用方分配并清零，内核按 count 回填并给出 total_count。
bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

// 查询安全模式；命令失败时 cmd 保持零值，即按非安全模式处理。
bool is_safe_mode() {
    // 必须零初始化，内核只填写已知字段，未填写字段不能带垃圾值。
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

// 判断 LKM 模式；新内核读 flags，旧内核走 legacy_get_info 的 second 字段。
bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return (legacy_get_info().second & KSU_GET_INFO_FLAG_LKM) != 0;
}

// 判断是否为捆绑安装的 LKM；必须同时具备 LKM 与 BUNDLED 两个标志。
bool is_lkm_bundled() {
    auto info = get_info();
    return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0 &&
           (info.flags & KSU_GET_INFO_FLAG_BUNDLED) != 0;
}

// 判断 late-load 模式；旧内核无此标志，统一返回 false。
bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

// 判断当前进程是否被识别为 Manager；旧内核以 legacy 版本号大于 0 作为等价条件。
bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0;
    }
    return legacy_get_info().first > 0;
}

// 判断是否为 PR 构建；旧内核无该标志，返回 false。
bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

// 询问内核指定 UID 是否应卸载模块；命令失败时按 false 处理，避免误卸载。
bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

// 写入 app profile；命令结构体内嵌完整 profile，必须整体拷贝后一次下发。
bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

// 读取 app profile；返回 0 表示命中，非 0 时 profile 可能已被内核改写为默认值。
int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

// 开关 Su compat；把布尔值转换为 uapi 使用的 0/1。
bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

// 查询 Su compat；命令失败或内核声明不支持时返回 false。
bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!(cmd.supported & KSU_FEATURE_FLAG_SUPPORTED)) {
        return false;
    }
    return cmd.value != 0;
}

// 通用特性读取：out_value/out_supported 允许为 nullptr，便于只关心其一。
// 返回 false 表示 ioctl 失败，此时出参不会被写入。
static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported & KSU_FEATURE_FLAG_SUPPORTED;
    return true;
}

// 通用特性写入：value 按 uapi 的 uint64 传递，布尔特性用 0/1 表示。
static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

// 开关内核 umount 特性；实际是否生效还取决于内核是否支持该 feature。
bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

// 查询内核 umount 特性；命令失败或不支持均视为关闭。
bool is_kernel_umount_enabled() {
    // value/supported 必须先初始化，get_feature 失败时保持默认值。
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

// 开关 SELinux 隐藏；返回 0 成功，失败返回负 errno，便于 JNI 层把错误传给 Kotlin。
int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

// 查询 SELinux 隐藏特性；命令失败或不支持均视为关闭。
bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}
