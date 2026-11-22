#ifndef __KSU_RWPROCMEM_H
#define __KSU_RWPROCMEM_H

#include <linux/errno.h>
#include <linux/types.h>

// rwProcMem 裁剪版：只保留把目标进程提升为 root 的能力。
// 目前只在 arm64 上可用：其内部依赖 arm64 PTE/task_struct 布局与 cred 偏移探测。
#ifdef CONFIG_ARM64

int ksu_rwprocmem_init(void);
void ksu_rwprocmem_exit(void);

/**
 * ksu_rwprocmem_set_root() - 把指定进程的 cred 提升为 root
 * @pid: 目标进程 pid
 *
 * 返回 0 或负 errno。
 */
int ksu_rwprocmem_set_root(pid_t pid);

#else

static inline int ksu_rwprocmem_init(void)
{
	return 0;
}

static inline void ksu_rwprocmem_exit(void) {}

static inline int ksu_rwprocmem_set_root(pid_t pid)
{
	(void)pid;
	return -EOPNOTSUPP;
}

#endif /* CONFIG_ARM64 */

#endif /* __KSU_RWPROCMEM_H */
