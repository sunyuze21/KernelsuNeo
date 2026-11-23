#include "infra/btf_parser.h"

/*
 * 通过内核 BTF 元数据解析结构体成员偏移。仅内建且开启 DEBUG_INFO_BTF 时编译，
 * 因为 bpf_get_btf_vmlinux/btf_find_by_name_kind 等符号并非所有内核都导出。
 */
#if defined(MODULE) && defined(CONFIG_DEBUG_INFO_BTF)
#include <linux/bpf.h>
#include <linux/btf.h>
#include <linux/err.h>
#include <linux/errno.h>
#include <linux/string.h>

#include "klog.h"

/*
 * 查找 vmlinux 中 structure 的 member 成员。所有边界都做严格校验：
 * 位域、未对齐、跨结构体、类型不符一律拒绝，防止调用方用错误偏移读写内核
 * 对象。返回 0 成功并填 field；-ENOENT 未找到；-EINVAL 布局不匹配。
 */
int ksu_btf_field(const char *structure, const char *member, u32 kind, u32 size, struct ksu_btf_field *field)
{
    const struct btf *btf;
    const struct btf_type *type;
    const struct btf_member *m;
    s32 id;
    u32 i;

    /* vmlinux BTF 由内核在启动时生成；模块场景下可能不可用 */
    pr_info("BTF: acquiring vmlinux metadata for %s.%s\n", structure, member);
    btf = bpf_get_btf_vmlinux();
    if (IS_ERR_OR_NULL(btf)) {
        pr_err("BTF: vmlinux metadata unavailable (%ld)\n", btf ? PTR_ERR(btf) : -ENOENT);
        return btf ? PTR_ERR(btf) : -ENOENT;
    }

    /* 按名字和 kind 取结构体类型 ID；同名 typedef/union 不会被误选 */
    pr_info("BTF: looking up struct %s\n", structure);
    id = btf_find_by_name_kind(btf, structure, BTF_KIND_STRUCT);
    if (id < 0)
        return id;
    type = btf_type_by_id(btf, id);
    if (!type)
        return -EINVAL;

    /* 遍历成员，name_off 是字符串表偏移，需转成 C 字符串再比较 */
    for_each_member (i, type, m) {
        const struct btf_type *member_type;
        const char *name = btf_name_by_offset(btf, m->name_off);
        u32 bits, bytes, bitfield_size;

        if (!name)
            return -EINVAL;
        if (strcmp(name, member))
            continue;
        bits = m->offset;
        bitfield_size = 0;
        /* 带 kflag 的结构体把位域宽度编码在 offset 高位，必须拆开 */
        if (BTF_INFO_KFLAG(type->info)) {
            bitfield_size = BTF_MEMBER_BITFIELD_SIZE(bits);
            bits = BTF_MEMBER_BIT_OFFSET(bits);
        }
        bytes = bits / 8;
        /* 拒绝位域、非字节对齐、非 size 整数倍以及越界成员 */
        if (bitfield_size || !size || (bits & 7) || bytes % size || bytes > type->size || size > type->size - bytes)
            return -EINVAL;
        member_type = btf_type_skip_modifiers(btf, m->type, NULL);
        if (!member_type || BTF_INFO_KIND(member_type->info) != kind)
            return -EINVAL;
        /* 整数成员还要求位宽/编码完全一致，避免取到 bool/char */
        if (kind == BTF_KIND_INT) {
            u32 info = *(const u32 *)(member_type + 1);

            if (member_type->size != size || BTF_INT_OFFSET(info) || BTF_INT_BITS(info) != size * 8 ||
                (BTF_INT_ENCODING(info) & (BTF_INT_CHAR | BTF_INT_BOOL)))
                return -EINVAL;
        } else if (kind != BTF_KIND_PTR || size != sizeof(void *)) {
            return -EINVAL;
        }
        /* 回填结果；struct_size 供调用方做版本兼容判断 */
        field->offset = bytes;
        field->struct_size = type->size;
        pr_info("BTF: resolved %s.%s at %u, struct size %u\n", structure, member, bytes, type->size);
        return 0;
    }
    return -ENOENT;
}
#endif
