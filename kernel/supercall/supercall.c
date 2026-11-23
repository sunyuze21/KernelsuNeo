/*
 * [ksu_driver] anon-inode 的创建与 fd 安装。
 *
 * 用户态通过 reboot(2) 魔数触发 kprobe，在 task_work 中把 ioctl fd 安装到
 * 当前进程；fd 携带权限位，用于区分普通驱动 fd 与 su session fd。
 */
#include <linux/anon_inodes.h>
#include <linux/err.h>
#include <linux/fdtable.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/kprobes.h>
#include <linux/pid.h>
#include <linux/slab.h>
#include <linux/syscalls.h>
#include <linux/task_work.h>
#include <linux/uaccess.h>
#include <linux/version.h>

#include "uapi/supercall.h"
#include "supercall/internal.h"
#include "arch.h"
#include "util.h"
#include "klog.h" // IWYU pragma: keep

/* context 权限位：表示该 fd 是在 su 启动流程中安装的 session fd */
#define KSU_DRIVER_PERMISSION_SU_SESSION (1UL << 0)

/*
 * anon inode 的 private_data，随 fd 生命周期存在（release 时 kfree）。
 * permissions 保存安装时授予的权限位，ioctl 分发时据此决定是否放宽校验。
 */
struct ksu_driver_context {
    unsigned long permissions;
};

/*
 * reboot kprobe 与安装 fd 之间的中转结构。
 * 因为 kprobe pre_handler 处于不可睡眠上下文（且 reboot 持有相关锁），
 * 不能直接 fd_install，只能把用户态输出指针暂存，稍后在 task_work 中完成安装。
 */
struct ksu_install_fd_tw {
    /* task_work 回调头，必须位于结构体首部以便 container_of 还原 */
    struct callback_head cb;
    /* 用户态 int 指针，用于回写安装好的 fd 编号 */
    int __user *outp;
};

/* anon inode 释放回调：释放 ksu_driver_context，fd 关闭时调用一次 */
static int anon_ksu_release(struct inode *inode, struct file *filp)
{
    kfree(filp->private_data);
    pr_info("ksu fd released\n");
    return 0;
}

/* ioctl 入口，把 unsigned long 参数转为用户指针后交给统一分发器 */
static long anon_ksu_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    return ksu_supercall_handle_ioctl(filp, cmd, (void __user *)arg);
}

/*
 * anon inode 的 file_operations。
 * compat_ioctl 复用同一实现：UAPI 结构体全部使用定长整型，32/64 位布局一致。
 */
static const struct file_operations anon_ksu_fops = {
    .owner = THIS_MODULE,
    .unlocked_ioctl = anon_ksu_ioctl,
    .compat_ioctl = anon_ksu_ioctl,
    .release = anon_ksu_release,
};

/*
 * 创建并安装一个 [ksu_driver] fd。
 * @fd_flags: get_unused_fd_flags 的标志，通常为 O_CLOEXEC
 * @permissions: 存入 context 的权限位
 * 返回 fd 编号或负 errno；失败路径保证 context/fd 均已回收。
 */
static int ksu_install_fd_with_permissions(unsigned int fd_flags, unsigned long permissions)
{
    struct ksu_driver_context *context;
    struct file *filp;
    const char *name;
    int fd;

    // 每个 fd 独立分配 context，release 回调负责释放，不能共享
    context = kzalloc(sizeof(*context), GFP_KERNEL);
    if (!context)
        return -ENOMEM;

    context->permissions = permissions;
    // 名称仅用于 /proc/<pid>/fd 展示，便于区分 su session
    name = permissions & KSU_DRIVER_PERMISSION_SU_SESSION ? "[ksu_driver_su]" : "[ksu_driver]";

    // 先占位 fd 号，再创建 file，避免 fd 表相关失败时泄漏 file
    fd = get_unused_fd_flags(fd_flags);
    if (fd < 0) {
        pr_err("ksu_install_fd: failed to get unused fd\n");
        kfree(context);
        return fd;
    }

    filp = anon_inode_getfile(name, &anon_ksu_fops, context, O_RDWR);
    if (IS_ERR(filp)) {
        pr_err("ksu_install_fd: failed to create anon inode file\n");
        put_unused_fd(fd);
        kfree(context);
        return PTR_ERR(filp);
    }

    // fd_install 之后 file 归 fd 表所有，不能再直接 put
    fd_install(fd, filp);
    pr_info("ksu fd installed: %d for pid %d\n", fd, current->pid);
    return fd;
}

/* 安装普通驱动 fd（无额外权限位），使用 O_CLOEXEC 防止 exec 泄漏 */
int ksu_install_fd(void)
{
    return ksu_install_fd_with_permissions(O_CLOEXEC, 0);
}

/*
 * 安装 su session fd：带 KSU_DRIVER_PERMISSION_SU_SESSION，可绕过部分
 * perm_check（参见 dispatch.c 的 allow_su_session 表项）。
 */
int ksu_install_su_fd(void)
{
    // This descriptor must be installed after the exec into ksud.
    return ksu_install_fd_with_permissions(O_CLOEXEC, KSU_DRIVER_PERMISSION_SU_SESSION);
}

/*
 * 查询 filp 是否为 su session fd。
 * private_data 只在释放后失效，因此必须在 fd 仍被引用时调用。
 */
bool ksu_is_su_session_fd(const struct file *filp)
{
    const struct ksu_driver_context *context = filp->private_data;

    return context && (context->permissions & KSU_DRIVER_PERMISSION_SU_SESSION);
}

/*
 * task_work 回调：在返回用户态前的安全上下文中真正安装 fd，
 * 并把 fd 号写回用户态；写回失败时关闭 fd 以免泄漏。
 */
static void ksu_install_fd_tw_func(struct callback_head *cb)
{
    struct ksu_install_fd_tw *tw = container_of(cb, struct ksu_install_fd_tw, cb);
    int fd = ksu_install_fd();

    pr_info("[%d] install ksu fd: %d\n", current->pid, fd);
    if (copy_to_user(tw->outp, &fd, sizeof(fd))) {
        // 用户指针不可写时主动关闭，避免 fd 挂在进程上无法释放
        pr_err("install ksu fd reply err\n");
        ksu_close_fd(fd);
    }

    kfree(tw);
}

/*
 * reboot(2) 的 kprobe pre_handler。
 * 通过魔数识别 KernelSU 的安装请求：magic1 = 系统调用参数1，magic2 = 参数2，
 * 参数4 被解释为用户态 int 指针。这里只分配并排队 task_work，不做 copy_to_user。
 * 返回值恒为 0，表示不干预 reboot 系统调用本身。
 */
static int reboot_handler_pre(struct kprobe *p, struct pt_regs *regs)
{
    // 兼容不同架构/内核的 syscall wrapper，取出真实寄存器视图
    struct pt_regs *real_regs = PT_REAL_REGS(regs);
    int magic1 = (int)PT_REGS_SYSCALL_PARM1(real_regs);
    int magic2 = (int)PT_REGS_PARM2(real_regs);

    if (magic1 == KSU_INSTALL_MAGIC1 && magic2 == KSU_INSTALL_MAGIC2) {
        struct ksu_install_fd_tw *tw;
        // reboot 的第 4 个参数被复用为 fd 输出指针
        unsigned long arg4 = (unsigned long)PT_REGS_SYSCALL_PARM4(real_regs);

        // kprobe 上下文不能睡眠，只能 GFP_ATOMIC
        tw = kzalloc(sizeof(*tw), GFP_ATOMIC);
        if (!tw)
            return 0;

        tw->outp = (int __user *)arg4;
        tw->cb.func = ksu_install_fd_tw_func;

        if (task_work_add(current, &tw->cb, TWA_RESUME)) {
            kfree(tw);
            pr_warn("install fd add task_work failed\n");
        }
    }

    return 0;
}

/* reboot 符号上的 kprobe，symbol_name 由 arch.h 按内核版本/架构选择 */
static struct kprobe reboot_kp = {
    .symbol_name = REBOOT_SYMBOL,
    .pre_handler = reboot_handler_pre,
};

/* 模块/内核初始化：打印命令表并注册 reboot kprobe；kprobe 失败不阻止加载 */
void __init ksu_supercalls_init(void)
{
    int rc;

    ksu_supercall_dump_commands();

    rc = register_kprobe(&reboot_kp);
    if (rc) {
        pr_err("reboot kprobe failed: %d\n", rc);
    } else {
        pr_info("reboot kprobe registered successfully\n");
    }
}

/* 卸载：注销 kprobe 并清理 mount_list 等全局状态 */
void __exit ksu_supercalls_exit(void)
{
    unregister_kprobe(&reboot_kp);
    ksu_supercall_cleanup_state();
}
