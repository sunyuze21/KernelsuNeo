// SPDX-License-Identifier: GPL-2.0
/*
 * 裁剪版 rwProcMem：只保留“把目标进程提升为 root”的能力。
 *
 * 原始实现来自 rwProcMem33。这里不再创建 /proc 节点，而是由 KernelSU 的
 * supercall 入口直接调用 ksu_rwprocmem_set_root()。
 */

#include <linux/errno.h>
#include <linux/kernel.h>
#include <linux/pid.h>
#include <linux/sched.h>
#include <linux/string.h>
#include <linux/types.h>

#include "ksu_rwprocmem.h"
#include "ver_control.h"
#include "proc_root.h"

/*
 * 用当前任务临时改一个唯一的 comm，校准 task->cred 偏移。
 * 结果保存在 proc_root_auto_offset.h 的静态变量里，后续 set_root 复用。
 */
static bool rwprocmem_calibrate_cred_offset(void)
{
	char saved_comm[TASK_COMM_LEN];
	bool ok;

	if (g_init_real_cred_offset_success) {
		return true;
	}

	memcpy(saved_comm, current->comm, TASK_COMM_LEN);
	strscpy(current->comm, "rwpm-calib", TASK_COMM_LEN);

	ok = init_proc_root_offset(current->comm) == 0;

	memcpy(current->comm, saved_comm, TASK_COMM_LEN);
	return ok;
}

int ksu_rwprocmem_set_root(pid_t pid)
{
	struct pid *proc_pid_struct;
	int ret;

	if (pid <= 0) {
		return -EINVAL;
	}

	if (!rwprocmem_calibrate_cred_offset()) {
		return -ENOENT;
	}

	proc_pid_struct = find_get_pid(pid);
	if (!proc_pid_struct) {
		return -ESRCH;
	}

	ret = set_process_root(proc_pid_struct);
	put_pid(proc_pid_struct);
	return ret;
}

int ksu_rwprocmem_init(void)
{
	if (!rwprocmem_calibrate_cred_offset()) {
		pr_warn("rwProcMem: cred offset calibration failed\n");
	}
	return 0;
}

void ksu_rwprocmem_exit(void) {}
