/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 */

#ifndef __KSU_PATCH_MEMORY_H
#define __KSU_PATCH_MEMORY_H

#include <linux/types.h>
#include "linux/version.h"

// 按架构选择文本修改所需的指令解码/补丁头文件：AArch64 依赖内核的
// insn/patching 实现，x86_64 与 RISC-V 只需 pt_regs 等基础定义。
#ifdef __aarch64__
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 13, 0)
#include "asm/text-patching.h" // IWYU pragma: keep
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 14, 0)
#include "asm/patching.h" // IWYU pragma: keep
#else
#include "asm/insn.h" // IWYU pragma: keep
#endif
#elif defined(__x86_64__) || defined(__riscv)
#include <asm/ptrace.h>
#else
#error "Unsupported arch"
#endif

// ksu_patch_text() 的 flags 位：分别要求同步数据缓存和指令缓存。
// AArch64 改写代码后必须做 D-cache clean + I-cache invalidate，否则其他 CPU
// 可能继续执行旧指令；x86_64 由硬件维护一致性，对应实现为空操作。
#define KSU_PATCH_TEXT_FLUSH_DCACHE 1
#define KSU_PATCH_TEXT_FLUSH_ICACHE 2

// 遍历 init_mm 页表把内核虚拟地址翻译成物理地址，兼容大页/leaf 映射。
// 成功时返回物理地址并通过 *err 写 0；失败返回 0 并写 -ENOENT。
// 调用者必须先检查 *err，因为物理地址 0 本身也可能是合法值。
unsigned long phys_from_virt(unsigned long addr, int *err);
// 原子地把 [dst, dst+len) 的内核文本/只读数据替换为 src 内容。
// 内部通过 stop_machine 在所有在线 CPU 上同步，避免其他 CPU 看到半更新的
// 指令；flags 为上面的 KSU_PATCH_TEXT_FLUSH_* 组合。返回 0 或负错误码。
int ksu_patch_text(void *dst, void *src, size_t len, int flags);
// 在 [start, start+size) 中扫描目标架构的 call 指令（AArch64 BL、RISC-V
// JAL/AUIPC+JALR），返回第一条跳转到 target 的指令地址，未找到返回 NULL。
// x86_64 当前未实现，恒返回 NULL。
void *scan_call_to(void *start, size_t size, void *target);

#endif
