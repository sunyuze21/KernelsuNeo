
#include <asm/ptrace.h>
#include <linux/namei.h>
#include <linux/path.h>
#include <linux/printk.h>
#include <linux/types.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/ptrace.h>
#include <linux/static_key.h>
#include <linux/slab.h>

#include "adb_root.h"
#include "arch.h"
#include "policy/feature.h"
#include "selinux/selinux.h"

#include "klog.h" // IWYU pragma: keep

// adb_root 特性开关的 static key：关闭时 execve 路径只剩一次跳转开销，
// 开启后走完整 adbd 检测与提权流程。由 feature set 路径 enable/disable。
DEFINE_STATIC_KEY_FALSE(ksu_adb_root);

// 判断 filename_user 是否以 "/adbd" 结尾（即 adbd 可执行文件）。
// 返回值：1 匹配，0 不匹配，负值为 copy_from_user 类错误。
// 注意：filename_user 来自用户态，必须用 untagged_addr 去掉 MTE 标签后再读。
static long is_exec_adbd(const char __user *filename_user)
{
    static const char kAdbd[] = "/adbd";
    static const size_t kAdbdLen = sizeof(kAdbd) - 1;
    // should be bigger than `/apex/com.android.adbd/bin/adbd`
    // 栈上缓冲区，保存用户态路径的前缀；strncpy_from_user 最多写满整个数组。
    char buf[40];
    // 去掉 MTE 标签后的用户态文件名指针，之后所有用户内存访问都用它。
    char __user *fn;
    long ret;
    fn = (char __user *)untagged_addr((unsigned long)filename_user);
    memset(buf, 0, sizeof(buf));

    ret = strncpy_from_user(buf, fn, sizeof(buf));
    if (ret < 0) {
        pr_warn("Access filename when adb_root_handle_execve failed: %ld\n", ret);
        return ret;
    }

    // strncpy_from_user may copy `sizeof(buf)` bytes
    if (ret < kAdbdLen || ret >= sizeof(buf) || memcmp(buf + ret - kAdbdLen, kAdbd, kAdbdLen + 1) != 0) {
        return 0;
    }

    return 1;
}

// 检查 libadbroot.so 是否存在（提权所需的 LD_PRELOAD 库由 ksud 安装）。
// 返回：1 存在；0/-ENOENT 表示未安装（跳过提权）；其他负值为访问错误。
// 仅做路径解析，path_put 释放引用，不读取文件内容。
static long is_libadbroot_ok()
{
    static const char kLibAdbRoot[] = "/data/adb/ksu/lib/libadbroot.so";
    struct path path;
    long ret = kern_path(kLibAdbRoot, 0, &path);
    if (ret < 0) {
        if (ret == -ENOENT) {
            pr_err("libadbroot.so not exists, skip adb root. Please run `ksud install`\n");
            ret = 0;
        } else {
            pr_err("access libadbroot.so failed: %ld, skip adb root\n", ret);
        }
        return ret;
    } else {
        ret = 1;
    }
    path_put(&path);
    return ret;
}

// 在用户态栈上追加 LD_PRELOAD/LD_LIBRARY_PATH 两项环境变量，并改写
// *envp_p 指向新的 envp 数组，使 adbd 加载 libadbroot.so。
// regs 提供当前用户栈指针；envp_p 指向系统调用参数中的 envp 指针槽。
// 返回 0 成功，-EFAULT/-ENOMEM 失败（失败时用户栈可能已被部分改写）。
static long setup_ld_preload(struct pt_regs *regs, unsigned long *envp_p)
{
    static const char kLdPreload[] = "LD_PRELOAD=/data/adb/ksu/lib/libadbroot.so";
    static const char kLdLibraryPath[] = "LD_LIBRARY_PATH=/data/adb/ksu/lib";
    static const size_t kReadEnvBatch = 16;
    static const size_t kPtrSize = sizeof(unsigned long);
    // 当前用户栈顶指针，逐步向下（低地址）为新增字符串和指针数组腾空间。
    unsigned long stackp = user_stack_pointer(regs);
    // envp：用户态 envp 指针数组地址；*_p：两个新增环境变量字符串在用户栈上的地址。
    unsigned long envp, ld_preload_p, ld_library_path_p;
    // 从用户态分批读出 envp 后在核内保存的临时副本（可能被 krealloc 扩容）。
    unsigned long *tmp_env_p = NULL, *tmp_env_p2 = NULL;
    // env_count：已读到的环境变量个数；total_size：新指针数组总字节数。
    size_t env_count = 0, total_size;
    long ret;

    // envp 指针同样来自用户态，去掉 MTE 标签后才能安全解引用。
    envp = (char __user **)untagged_addr((unsigned long)*envp_p);

    // 把字符串写到用户栈上的新位置；copy_to_user 失败说明栈不可写，
    // 此时不修改 *envp_p，直接返回 -EFAULT。
    ld_preload_p = stackp = ALIGN_DOWN(stackp - sizeof(kLdPreload), 8);
    ret = copy_to_user(ld_preload_p, kLdPreload, sizeof(kLdPreload));
    if (ret != 0) {
        pr_warn("write ld_preload when adb_root_handle_execve failed: %ld\n", ret);
        return -EFAULT;
    }

    ld_library_path_p = stackp = ALIGN_DOWN(stackp - sizeof(kLdLibraryPath), 8);
    ret = copy_to_user(ld_library_path_p, kLdLibraryPath, sizeof(kLdLibraryPath));
    if (ret != 0) {
        pr_warn("write ld_library_path when adb_root_handle_execve failed: %ld\n", ret);
        return -EFAULT;
    }

    // 分批读取用户态 envp 直到遇到 NULL 结尾。不能假设 envp 连续可读，
    // 因此每次 copy_from_user，并按实际读到的字节数计算有效指针数。
    for (;;) {
        tmp_env_p2 = krealloc(tmp_env_p, (env_count + kReadEnvBatch + 2) * kPtrSize, GFP_KERNEL);
        if (tmp_env_p2 == NULL) {
            pr_err("alloc tmp env failed\n");
            ret = -ENOMEM;
            goto out_release_env_p;
        }
        tmp_env_p = tmp_env_p2;
        ret = copy_from_user(&tmp_env_p[env_count], envp + env_count * kPtrSize, kReadEnvBatch * kPtrSize);
        if (ret < 0) {
            pr_warn("Access envp when adb_root_handle_execve failed: %ld\n", ret);
            ret = -EFAULT;
            goto out_release_env_p;
        }
        size_t read_count = kReadEnvBatch * kPtrSize - ret;
        size_t max_new_env_count = read_count / kPtrSize, new_env_count = 0;
        bool meet_zero = false;
        for (; new_env_count < max_new_env_count; new_env_count++) {
            if (!tmp_env_p[new_env_count + env_count]) {
                meet_zero = true;
                break;
            }
        }
        if (!meet_zero) {
            if (read_count % kPtrSize != 0) {
                pr_err("unaligned envp array!\n");
                ret = -EFAULT;
                goto out_release_env_p;
            } else if (ret != 0) {
                pr_err("truncated envp array!\n");
                ret = -EFAULT;
                goto out_release_env_p;
            }
        }
        env_count += new_env_count;
        if (meet_zero)
            break;
    }

    // We should have allocated enough memory
    // TODO: handle existing LD_PRELOAD
    // 追加两项我们自己的环境变量指针并以 NULL 结尾；数组空间在循环中
    // 已多预留了 kReadEnvBatch+2 个槽位，这里不会越界。
    tmp_env_p[env_count++] = ld_preload_p;
    tmp_env_p[env_count++] = ld_library_path_p;
    tmp_env_p[env_count++] = 0;
    total_size = env_count * kPtrSize;

    stackp -= total_size;
    ret = copy_to_user(stackp, tmp_env_p, total_size);
    if (ret != 0) {
        pr_err("copy new env failed: %ld\n", ret);
        ret = -EFAULT;
        goto out_release_env_p;
    }

    *envp_p = stackp;
    ret = 0;

out_release_env_p:
    if (tmp_env_p) {
        kfree(tmp_env_p);
    }

    return ret;
}

// adb_root 的公共处理逻辑：依次确认目标是 adbd、libadbroot.so 已安装、
// 环境变量改写成功，最后把当前 adbd 提升到 root。返回 0 继续执行，
// 负值表示环境变量改写失败（此时不应继续 exec）。
static long do_ksu_adb_root_handle_execve(const char __user *filename_user, struct pt_regs *regs, unsigned long *envp_p)
{
    if (likely(is_exec_adbd(filename_user) != 1)) {
        return 0;
    }

    if (unlikely(is_libadbroot_ok() != 1)) {
        return 0;
    }

    long ret = setup_ld_preload(regs, envp_p);
    if (ret) {
        return ret;
    }

    pr_info("escape to root for adb\n");
    escape_to_root_for_adb_root();
    return 0;
}

// execve 系统调用 hook：filename 在 Parm1，envp 在 Parm3。
// static key 未启用时直接返回 0（零开销快路径）。
long ksu_adb_root_handle_execve(struct pt_regs *regs)
{
    if (static_branch_unlikely(&ksu_adb_root)) {
        return do_ksu_adb_root_handle_execve((const char __user *)PT_REGS_SYSCALL_PARM1(regs), regs,
                                             (unsigned long *)&PT_REGS_PARM3(regs));
    }
    return 0;
}

// execveat 系统调用 hook：filename 在 Parm2，envp 在 Parm4。
// 解析出的 filename 已由调用方做 untagged 处理或在此统一处理。
long ksu_adb_root_handle_execveat(struct pt_regs *regs)
{
    if (static_branch_unlikely(&ksu_adb_root)) {
        return do_ksu_adb_root_handle_execve((const char __user *)PT_REGS_PARM2(regs), regs,
                                             (unsigned long *)&PT_REGS_SYSCALL_PARM4(regs));
    }
    return 0;
}

// 读取 adb_root 特性状态（static key 是否启用），写入 *value（0/1）。
static int kernel_adb_root_feature_get(u64 *value)
{
    *value = static_key_enabled(&ksu_adb_root) ? 1 : 0;
    return 0;
}

// 开关 adb_root 特性：通过 static key enable/disable 控制 execve hook
// 是否进入提权逻辑；static key 更新自身是安全的，但要求调用上下文允许睡眠。
static int kernel_adb_root_feature_set(u64 value)
{
    bool enable = value != 0;
    if (enable) {
        static_key_enable(&ksu_adb_root.key);
    } else {
        static_key_disable(&ksu_adb_root.key);
    }
    pr_info("adb_root: set to %d\n", enable);
    return 0;
}

// adb_root 特性描述，供 supercall 按 feature_id 分发 get/set。
static const struct ksu_feature_handler ksu_adb_root_handler = {
    .feature_id = KSU_FEATURE_ADB_ROOT,
    .name = "adb_root",
    .get_handler = kernel_adb_root_feature_get,
    .set_handler = kernel_adb_root_feature_set,
};

// 模块初始化时注册 adb_root 特性处理器；注册失败仅告警。
void __init ksu_adb_root_init(void)
{
    if (ksu_register_feature_handler(&ksu_adb_root_handler)) {
        pr_err("Failed to register adb_root feature handler\n");
    }
}

// 模块退出时注销 adb_root 特性处理器；static key 状态由特性开关自行维护。
void __exit ksu_adb_root_exit(void)
{
    ksu_unregister_feature_handler(KSU_FEATURE_ADB_ROOT);
}
