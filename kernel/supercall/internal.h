/*
 * supercall 内部共享声明。
 *
 * 权限校验函数（perm.c 实现）、ioctl 分发入口以及 init/exit 辅助函数，
 * 仅供 kernel/supercall/ 与同目录调用者使用，不导出 UAPI。
 */
#ifndef __KSU_H_SUPERCALL_INTERNAL
#define __KSU_H_SUPERCALL_INTERNAL

#include <linux/fs.h>
#include <linux/types.h>
#include <linux/uaccess.h>

/* 仅允许 Manager 调用，见 perm.c */
bool only_manager(void);
/* 仅允许 uid 0 调用，见 perm.c */
bool only_root(void);
/* root 或 Manager 均可调用，见 perm.c */
bool manager_or_root(void);
/* 无条件放行，见 perm.c */
bool always_allow(void);
/* Manager 或 allowlist 中已授权 su 的 uid，见 perm.c */
bool allowed_for_su(void);

/*
 * 统一 ioctl 入口：filp 为 [ksu_driver] anon inode，cmd 为命令号，
 * argp 为用户态参数指针。内部按 ksu_ioctl_handlers 表完成权限校验与分发，
 * 未知命令返回 -ENOTTY，权限不足返回 -EPERM。
 */
long ksu_supercall_handle_ioctl(const struct file *filp, unsigned int cmd, void __user *argp);
/* 启动时打印全部已注册 IOCTL 命令号，纯粹用于调试 */
void ksu_supercall_dump_commands(void);
/* 释放 mount_list 等进程级全局状态，避免模块卸载后内存泄漏 */
void ksu_supercall_cleanup_state(void);

#endif // __KSU_H_SUPERCALL_INTERNAL
