/*
 * supercall 权限校验回调集合。
 *
 * 这些函数由 supercall/dispatch.c 的 IOCTL 映射表在分发命令前调用，
 * 运行在发起 ioctl 的进程上下文中，因此一律基于 current_uid()/is_manager()
 * 判断，不涉及锁；返回 true 表示放行，false 由调用方转换为 -EPERM。
 */

#include <linux/types.h>

#include "supercall/internal.h"
#include "manager/manager_identity.h"
#include "policy/allowlist.h"

/*
 * 仅允许 KernelSU Manager 自身调用（例如读取/写入 app profile）。
 * 返回 true 表示当前进程通过 Manager 身份校验。
 */
bool only_manager(void)
{
    return is_manager();
}

/*
 * 仅允许 uid 0（真实 root）调用。
 * 注意这里比较的是 current_uid() 的有效 uid，不做 allowlist 查询。
 */
bool only_root(void)
{
    return current_uid().val == 0;
}

/*
 * root 或 Manager 均可调用，用于大多数只读/可管理的查询类命令。
 */
bool manager_or_root(void)
{
    return current_uid().val == 0 || is_manager();
}

/*
 * 无条件放行，仅用于版本查询、safemode 查询等不含敏感信息的命令。
 */
bool always_allow(void)
{
    return true;
}

/*
 * 授予 root（GRANT_ROOT）的专用校验：Manager 或已在 allowlist 中
 * 被授权 su 的 uid 才能通过。真正的 uid 校验语义在 allowlist 侧维护，
 * 此处只做查询，不修改状态。
 */
bool allowed_for_su(void)
{
    return is_manager() || ksu_is_allow_uid_for_current(current_uid().val);
}
