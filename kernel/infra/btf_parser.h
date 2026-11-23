#ifndef __KSU_BTF_PARSER_H
#define __KSU_BTF_PARSER_H

#include <linux/types.h>

/*
 * BTF 辅助接口：仅在内建模块（MODULE）且内核开启 CONFIG_DEBUG_INFO_BTF 时
 * 可用。用于在运行时从 vmlinux 的 BTF 元数据中取得结构体成员的偏移，
 * 从而避免依赖内核版本变化的结构体布局（如 SELinux inode security）。
 */
#if defined(MODULE) && defined(CONFIG_DEBUG_INFO_BTF)

/* 解析结果：成员在结构体中的字节偏移，以及该结构体的编译期大小。 */
struct ksu_btf_field {
    /* 成员相对结构体起始的字节偏移，保证按 size 对齐且不是位域。 */
    u32 offset;
    /* 目标结构体的 BTF 记录大小，用于校验调用方与内核布局一致。 */
    u32 struct_size;
};

/*
 * 在 vmlinux BTF 中查找 structure.member，校验其类型与大小后回填 field。
 * kind 为期望的 BTF_KIND_*（BTF_KIND_INT 或 BTF_KIND_PTR），size 为期望
 * 的成员大小；返回 0 成功，负数 errno（-ENOENT 表示找不到，-EINVAL 表示
 * 类型/布局不符）。调用上下文：可睡眠，内部会访问 BTF 元数据。
 */
int ksu_btf_field(const char *structure, const char *member, u32 kind, u32 size, struct ksu_btf_field *field);
#endif
#endif
