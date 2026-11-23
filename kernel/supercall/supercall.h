/*
 * supercall 子系统内部头文件（对外可见部分）。
 *
 * 定义 IOCTL 处理函数/权限校验函数指针类型、命令映射表结构，
 * 以及 fd 安装与 init/exit 的入口声明。
 */
#ifndef __KSU_H_SUPERCALL
#define __KSU_H_SUPERCALL

#include <linux/fs.h>
#include <linux/types.h>
#include <linux/uaccess.h>

// IOCTL handler types
/*
 * IOCTL 命令处理函数类型。
 * arg 直接来自用户态 ioctl 的第三个参数（未经验证的 __user 指针），
 * 处理函数内部必须先 copy_from_user 校验，返回 0 或负 errno。
 */
typedef int (*ksu_ioctl_handler_t)(void __user *arg);
/*
 * 权限校验函数类型：在 handler 之前调用，无参数，基于 current 判断，
 * 返回 false 时 dispatch 直接返回 -EPERM。
 */
typedef bool (*ksu_perm_check_t)(void);

// IOCTL command mapping
/*
 * 单条 IOCTL 命令的映射表项。
 * 该表为静态 const 数组，以 handler == NULL 作为哨兵结束遍历。
 */
struct ksu_ioctl_cmd_map {
    /* 命令号，必须与 uapi/supercall.h 中的 KSU_IOCTL_* 定义一致 */
    unsigned int cmd;
    /* 仅用于启动时 dump 名称，便于排查 */
    const char *name;
    /* 实际处理函数，参数为用户态指针 */
    ksu_ioctl_handler_t handler;
    ksu_perm_check_t perm_check; // Permission check function
    /* 为 true 时，持有 su session fd 的调用方可绕过 perm_check（用于 su 启动早期） */
    bool allow_su_session;
};

// Install KSU fd to current process
/*
 * 为当前进程安装一个普通 [ksu_driver] anon-inode fd，带 O_CLOEXEC。
 * 返回 fd 或负 errno。
 */
int ksu_install_fd(void);
// Install a KSU fd that authorizes operations required while starting su.
/*
 * 安装带 KSU_DRIVER_PERMISSION_SU_SESSION 权限位的 fd。
 * 该 fd 必须在 exec 进入 ksud 之后安装，避免被 exec 关闭或被子进程继承后滥用。
 */
int ksu_install_su_fd(void);
/*
 * 判断给定 file 是否为 su session fd（读取 private_data 中的权限位）。
 * 调用方需保证 filp 非空且仍是本模块创建的 anon inode。
 */
bool ksu_is_su_session_fd(const struct file *filp);

/*
 * 注册 reboot kprobe 并 dump 命令表，仅在模块/内核 init 阶段调用一次。
 */
void ksu_supercalls_init(void);
/*
 * 注销 kprobe 并释放 umount 列表等全局状态，仅在 exit 阶段调用。
 */
void ksu_supercalls_exit(void);
#endif // __KSU_H_SUPERCALL
