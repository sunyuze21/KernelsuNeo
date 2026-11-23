#ifndef __KSU_H_ADB_ROOT
#define __KSU_H_ADB_ROOT
#include <asm/ptrace.h>

// execve hook 入口：若目标文件是 adbd 且特性开启，则改写用户栈环境变量
// （LD_PRELOAD）并把 adbd 提升为 root。regs 为被 hook 的系统调用寄存器，
// 返回 0 表示继续原流程，负值为错误码。
long ksu_adb_root_handle_execve(struct pt_regs *regs);
// execveat hook 入口：语义同 ksu_adb_root_handle_execve()，但参数位置不同
// （文件名在 Parm2，envp 在 Parm4）。
long ksu_adb_root_handle_execveat(struct pt_regs *regs);

// 注册 adb_root feature handler；仅 init 阶段调用。
void ksu_adb_root_init(void);

// 注销 adb_root feature handler，并关闭 static key；仅 exit 阶段调用。
void ksu_adb_root_exit(void);

#endif
