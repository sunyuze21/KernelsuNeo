#ifndef __KSU_H_PREINIT
#define __KSU_H_PREINIT

#include <linux/types.h>
#include <asm/ptrace.h>

/*
 * preinit：在 PID 1 执行 "/system/bin/init selinux_setup" 时，用内核内嵌的
 * 静态 runner 顶替这次 execve，让模块有机会在 SELinux 策略加载前执行
 * insmod/exec/write 等动作。runner 只在这次 execve 生效一次。
 */
#ifndef KSU_NO_PREINIT
/* 初始化阶段调用：把 blob.S 内嵌的 runner 复制进一个未链接的 shmem 文件。
 * 必须在 init 段被释放前完成，失败只打印日志并让 preinit 失效，不影响启动。 */
void ksu_preinit_init(void);
/* 模块卸载时调用：等价于 ksu_preinit_release()，避免 shmem 文件泄漏。 */
void ksu_preinit_exit(void);

// 替换 PID 1 的 "/system/bin/init selinux_setup" execve 为 runner。
// 成功发起系统调用时返回 true，结果写入 *ret；未命中/资源不足时返回 false，
// 由调用方继续原始路径。filename_user 可能被改写为 /proc/self/fd/N。
// Swap the runner in for PID 1's "/system/bin/init selinux_setup" execve.
// Returns true when it issued the syscall itself; the result is in *ret.
bool ksu_preinit_handle_execve(const char __user **filename_user, const char __user *const __user *argv_user,
                               int orig_nr, struct pt_regs *regs, long *ret);

// 若始终没有看到 selinux_setup（例如设备走了不同启动路径），丢弃暂存的
// runner 以释放 shmem 文件；second_stage 与 zygote 处也会调用。
// Drop the staged runner if selinux_setup was never seen.
void ksu_preinit_release(void);

// 查询模块参数 preinit=0 是否禁用了 preinit；仅表示“已编译且未禁用”。
// Whether the runner is built in and not disabled with preinit=0.
bool ksu_preinit_enabled_param(void);
#else
/* KSU_NO_PREINIT 构建下的空实现：接口保持可用，但永远不介入 execve。 */
static inline void ksu_preinit_init(void)
{
}
static inline void ksu_preinit_exit(void)
{
}
/* 未启用 preinit 时恒为 false，调用方无需分支判断。 */
static inline bool ksu_preinit_handle_execve(const char __user **filename_user,
                                             const char __user *const __user *argv_user, int orig_nr,
                                             struct pt_regs *regs, long *ret)
{
    return false;
}
/* 无暂存资源，空操作。 */
static inline void ksu_preinit_release(void)
{
}
/* 构建时已关闭 preinit，报告未启用。 */
static inline bool ksu_preinit_enabled_param(void)
{
    return false;
}
#endif

#endif // __KSU_H_PREINIT
