#include <linux/err.h>
#include <linux/fcntl.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/moduleparam.h>
#include <linux/namei.h>
#include <linux/sched.h>
#include <linux/shmem_fs.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/version.h>

#include "klog.h" // IWYU pragma: keep
#include "util.h"
#include "hook/syscall_hook.h"
#include "preinit/preinit.h"

/* PID 1 第一阶段 exec 的目标路径，只有精确匹配才会被替换。 */
#define INIT_PATH "/system/bin/init"

// runner 是静态 ELF，由 preinit/blob.S 嵌入 .init.rodata。
// 两个链接符号只在 init 阶段有效，必须在那之前复制到 shmem。
/* blob 起始地址（加载地址即数据首字节） */
extern const char ksu_preinit_runner_start[];
/* blob 结束地址，长度 = end - start */
extern const char ksu_preinit_runner_end[];

/* 模块参数 preinit=0 可在启动时整体关闭该机制，便于排障。 */
static bool ksu_preinit_enabled = true;
module_param_named(preinit, ksu_preinit_enabled, bool, 0);

// Owned here until PID 1 executes it; the init section holding the blob is
// gone by then, so it lives in an unlinked shmem file.
/* 指向已写入 runner 的 shmem 文件；非 NULL 表示"待替换一次 execve"。
 * 通过 xchg 原子认领，保证只有一次 execve 会被替换。 */
static struct file *ksu_preinit_file;

/* 实现见上：申请 shmem、写入 blob，并保存文件引用。任一步失败都只降级，
 * 因为 preinit 属于可选增强，不能阻塞正常启动。 */
void __init ksu_preinit_init(void)
{
    /* runner 字节数 */
    size_t size = ksu_preinit_runner_end - ksu_preinit_runner_start;
    struct file *file;
    loff_t pos = 0;
    ssize_t written;

    if (!ksu_preinit_enabled) {
        pr_info("preinit: disabled\n");
        return;
    }
    /* 6.19 起 shmem_file_setup 要求显式 vm_flags，EMPTY_VMA_FLAGS 等价旧行为 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 19, 0)
    file = shmem_file_setup("ksu_preinit", size, EMPTY_VMA_FLAGS);
#else
    file = shmem_file_setup("ksu_preinit", size, 0);
#endif
    if (IS_ERR(file)) {
        pr_err("preinit: shmem_file_setup failed: %ld\n", PTR_ERR(file));
        return;
    }

    /* 内核态写入 blob；必须一次写满，短写视为失败（pos 由 kernel_write 推进） */
    written = kernel_write(file, ksu_preinit_runner_start, size, &pos);
    if (written != size) {
        pr_err("preinit: write runner failed: %zd\n", written);
        fput(file);
        return;
    }

    /* 发布给 execve 钩子；此后归 ksu_preinit_file 所有 */
    ksu_preinit_file = file;
    pr_info("preinit: runner staged (%zu bytes)\n", size);
}

/* 返回模块参数状态：false 表示用户用 preinit=0 关闭，或构建时未启用。 */
bool ksu_preinit_enabled_param(void)
{
    return ksu_preinit_enabled;
}

/* 用 xchg 认领并清空暂存文件，保证与 execve 钩子并发时只有一个持有者；
 * 若从未被消费则 fput 释放 shmem。可在任意进程上下文重复调用。 */
void ksu_preinit_release(void)
{
    struct file *file = xchg(&ksu_preinit_file, NULL);

    if (file) {
        pr_info("preinit: selinux_setup not seen, dropping runner\n");
        fput(file);
    }
}

/* 模块退出路径：释放可能尚未被消费的 runner。 */
void ksu_preinit_exit(void)
{
    ksu_preinit_release();
}

/* 判断 argv[1] 是否为 "selinux_setup"：只读用户态指针，故用 get_user /
 * strncpy_from_user；任何缺页或指针异常都按"不匹配"处理，避免误替换。 */
static bool is_selinux_setup(const char __user *const __user *argv_user)
{
    static const char expected[] = "selinux_setup";
    const char __user *arg;
    char buf[sizeof(expected) + 1];

    /* argv 可能为 NULL（内核线程 exec），也允许 argv[1] 为空 */
    if (!argv_user || get_user(arg, argv_user + 1) || !arg)
        return false;

    memset(buf, 0, sizeof(buf));
    if (strncpy_from_user(buf, arg, sizeof(buf) - 1) < 0)
        return false;

    return !strcmp(buf, expected);
}

/* execve 前置钩子：命中 PID 1 的 selinux_setup 时，把 filename 改成
 * /proc/self/fd/N 指向的 runner 并直接发起系统调用。
 * 调用上下文：syscall 钩子，持有当前任务、可能处于原子上下文，不能睡眠等待
 * 用户态；失败路径必须恢复原 filename 以便回退到真实 init。 */
bool ksu_preinit_handle_execve(const char __user **filename_user, const char __user *const __user *argv_user,
                               int orig_nr, struct pt_regs *regs, long *ret)
{
    const char __user *orig_filename;
    char path[sizeof(INIT_PATH) + 1];
    char fd_path[32];
    struct file *file;
    struct path p;
    int fd, len;

    /* 快速路径：未暂存 runner（已消费或初始化失败）直接放行 */
    if (likely(!READ_ONCE(ksu_preinit_file)))
        return false;

    if (!filename_user || !*filename_user)
        return false;

    /* 读取 execve 的路径；untagged_addr 兼容 arm64 TBI 标签指针 */
    memset(path, 0, sizeof(path));
    if (strncpy_from_user(path, (const char __user *)untagged_addr((unsigned long)*filename_user), sizeof(path) - 1) <
        0)
        return false;

    if (strcmp(path, INIT_PATH) || !is_selinux_setup(argv_user))
        return false;

    // One shot: the runner's own exec of the real init must pass through.
    /* 一次性认领：runner 内部再 exec 真 init 时此处已为 NULL */
    file = xchg(&ksu_preinit_file, NULL);
    if (!file)
        return false;

    /* 用 O_CLOEXEC 的 fd 把文件暴露给 runner 自己，避免 fd 泄漏到 init */
    fd = get_unused_fd_flags(O_CLOEXEC);
    if (fd < 0) {
        pr_err("preinit: get fd failed: %d\n", fd);
        fput(file);
        return false;
    }
    // The fd owns the file from here on; close-on-exec frees it once the
    // runner has been loaded.
    fd_install(fd, file);

    /* 确认 /proc 已挂载且 fd 路径可达；不可达则放弃 preinit 正常启动 */
    len = snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    if (kern_path(fd_path, LOOKUP_FOLLOW, &p)) {
        pr_err("preinit: %s not reachable, booting without preinit\n", fd_path);
        ksu_close_fd(fd);
        return false;
    }
    path_put(&p);

    /* filename_user 指向 syscall 参数所在位置；改成内核栈上的 fd 路径。
     * userspace_stack_buffer 失败时回退原始指针，避免野指针。 */
    orig_filename = *filename_user;
    *filename_user = userspace_stack_buffer(fd_path, len + 1);
    if (!*filename_user) {
        *filename_user = orig_filename;
        ksu_close_fd(fd);
        return false;
    }

    /* 直接调用原始 execve 实现；若失败（如 runner 不可执行）恢复 filename
     * 并重试一次真实 init，保证启动不被 preinit 破坏。 */
    pr_info("preinit: exec runner via %s\n", fd_path);
    *ret = ksu_syscall_table[orig_nr](regs);
    if (*ret < 0) {
        pr_err("preinit: exec runner failed: %ld, booting without preinit\n", *ret);
        *filename_user = orig_filename;
        ksu_close_fd(fd);
        *ret = ksu_syscall_table[orig_nr](regs);
    }
    return true;
}
