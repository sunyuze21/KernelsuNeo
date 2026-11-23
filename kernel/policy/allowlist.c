#include <linux/rcupdate.h>
#include <linux/limits.h>
#include <linux/rculist.h>
#include <linux/mutex.h>
#include <linux/task_work.h>
#include <linux/capability.h>
#include <linux/compiler.h>
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/printk.h>
#include <linux/slab.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/compiler_types.h>
#include <linux/hashtable.h>
#include <linux/kref.h>

#include "klog.h" // IWYU pragma: keep
#include "ksu.h"
#include "runtime/ksud_boot.h"
#include "selinux/selinux.h"
#include "policy/allowlist.h"
#include "manager/manager_identity.h"
#include "infra/su_mount_ns.h"
#include "feature/shell_su_adb_only.h"

/* 持久化文件头魔数（' KSU'），用于拒绝非本模块生成的 .allowlist 文件 */
#define FILE_MAGIC 0x7f4b5355 // ' KSU', u32
/* 持久化文件格式版本；升级时需同步 migrate_profile() 与旧结构体尺寸假设 */
#define FILE_FORMAT_VERSION 4 // u32

/* 伪 uid 9999（NOBODY_UID）用于存放“默认非 root profile”这类无具体 uid 的条目 */
#define KSU_APP_PROFILE_PRESERVE_UID 9999 // NOBODY_UID
/* root profile 未指定 selinux domain 时使用的默认域，依赖 KERNEL_SU_DOMAIN 宏拼接 */
#define KSU_DEFAULT_SELINUX_DOMAIN "u:r:" KERNEL_SU_DOMAIN ":s0"

/* 保护 allow_list 哈希表写操作（增删改、序列化）的互斥锁；读路径依赖 RCU，不加此锁 */
static DEFINE_MUTEX(allowlist_mutex);

// default profiles, these may be used frequently, so we cache it
/* 默认 root profile 缓存：无自定义 profile 或 manager/shell 场景直接复用，避免每次分配 */
static struct root_profile default_root_profile;
/* 默认非 root profile 缓存：主要承载全局 umount_modules 默认策略 */
static struct non_root_profile default_non_root_profile;

/* 初始化上述默认 profile：root 默认 uid/gid 0、全 capability、继承命名空间；
 * 仅模块 init 时写入一次，之后只读，可被无锁共享
 */
static void __init init_default_profiles()
{
    /* 默认授予全部 capability（等效传统 root），不按最小权限收紧 */
    kernel_cap_t full_cap = CAP_FULL_SET;

    /* 默认以 uid/gid 0 运行 su 会话 */
    default_root_profile.uid = 0;
    default_root_profile.gid = 0;
    default_root_profile.groups_count = 1;
    default_root_profile.groups[0] = 0;
    memcpy(&default_root_profile.capabilities.effective, &full_cap,
           sizeof(default_root_profile.capabilities.effective));
    default_root_profile.namespaces = KSU_NS_INHERITED;
    strcpy(default_root_profile.selinux_domain, KSU_DEFAULT_SELINUX_DOMAIN);

    // This means that we will umount modules by default!
    default_non_root_profile.umount_modules = true;

    default_root_profile.flags = 0;
}

/* allowlist 哈希表的节点：一个 uid/伪 uid 对应一条 app_profile
 * 生命周期：写路径持 allowlist_mutex 分配/替换；读路径在 RCU 临界区内遍历，
 * 并通过 kref 取引用，最后一个引用释放后经 kfree_rcu 延迟回收
 */
struct perm_data {
    /* 哈希链表节点，以 profile.curr_uid 作为哈希 key，由 RCU 保护 */
    struct hlist_node list;
    /* RCU 延迟释放回调头，确保读者退出临界区后才真正 kfree */
    struct rcu_head rcu;
    /* 引用计数：并发读者持有期间防止节点被替换/删除后立即释放 */
    struct kref ref;
    /* 实际的 profile 数据；字段含义见 uapi/app_profile.h 及 app_profile.h */
    struct app_profile profile;
};

// protected by rcu
/* 哈希桶数量以 2^ALLOW_LIST_BITS 计；8 表示 256 个桶，需与内存开销权衡 */
#define ALLOW_LIST_BITS 8
/* allow_list 哈希表本身：读路径 RCU 无锁遍历，写路径需 allowlist_mutex */
static DEFINE_HASHTABLE(allow_list, ALLOW_LIST_BITS);
/* 当前哈希表节点数，仅写路径在持锁时更新；用于防止 u16 计数溢出 */
static u16 allow_list_count = 0;

/* allowlist 持久化路径；必须与 Manager/ksud 约定保持一致，勿随意迁移位置 */
#define KERNEL_SU_ALLOWLIST "/data/adb/ksu/.allowlist"

/* 前置声明：供加载/裁剪路径在定义之前请求异步持久化 */
void ksu_persistent_allow_list(void);

/* 按 uid 查找 profile：
 * - 必须在 rcu_read_lock() 保护下调用（hash_for_each_possible_rcu 要求）
 * - 返回的指针带 kref 引用，调用者用 ksu_put_app_profile() 释放
 * - 未命中返回 NULL
 */
struct app_profile *ksu_get_app_profile(uid_t uid)
{
    /* 待查找节点；RCU 遍历时会复用该指针 */
    struct perm_data *p = NULL;
    /* 是否在当前桶链表中命中 uid */
    bool found;

retry:
    /* 节点可能在被替换后释放，因此未取到引用时需要重新遍历 */
    found = false;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid) {
            // found it, override it with ours
            found = true;
            break;
        }
    }

    if (!found)
        return NULL;

    /* kref 已归零说明节点正在被删除，不能返回其指针，需重试查找 */
    if (!kref_get_unless_zero(&p->ref)) {
        goto retry;
    }

    return &p->profile;
}

/* 禁止 uid 小于 shell(2000) 且非 system(1000) 的系统服务进程获取 root，降低被滥用风险 */
static inline bool forbid_system_uid(uid_t uid)
{
    /* adb shell 的固定 UID，允许通过 allow_shell 策略单独放行 */
#define SHELL_UID 2000
    /* system_server 的固定 UID，需保留其既有特权语义 */
#define SYSTEM_UID 1000
    return uid < SHELL_UID && uid != SYSTEM_UID;
}

/* 校验外部传入的 profile 是否可信：
 * - key 必须以 NUL 结尾且不越界，防止持久化时泄漏/越界读取
 * - version 必须与当前内核侧结构体版本一致，否则字段布局不可解释
 * - allow_su 时进一步校验 groups 数量与 selinux domain 长度（策略关闭时跳过）
 * 返回 false 时调用者应拒绝写入，避免损坏 .allowlist 文件
 */
static bool profile_valid(struct app_profile *profile)
{
    if (!profile) {
        return false;
    }

    if (strnlen(profile->key, sizeof(profile->key)) >= sizeof(profile->key)) {
        pr_err("invalid app_profile key\n");
        return false;
    }

    if (profile->version != KSU_APP_PROFILE_VER) {
        pr_info("Unsupported profile version: %d\n", profile->version);
        return false;
    }

    if (profile->allow_su) {
#ifndef CONFIG_KSU_DISABLE_POLICY
        if (profile->rp_config.profile.groups_count > KSU_MAX_GROUPS) {
            pr_err("invalid groups_count in app_profile: %s\n", profile->key);
            return false;
        }

        static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);
        size_t len = strnlen(profile->rp_config.profile.selinux_domain, domain_len);

        if (len == 0 || len >= domain_len) {
            pr_err("invalid selinux_domain in app_profile: %s\n", profile->key);
            return false;
        }
#endif
    }

    return true;
}

/* kref 释放回调：在引用计数归零后调用，使用 kfree_rcu 等待 RCU 宽限期，避免仍在遍历的读者访问已释放内存 */
static void release_perm_data(struct kref *ref)
{
    /* 由内嵌的 ref 反推出整个 perm_data 节点 */
    struct perm_data *p = container_of(ref, struct perm_data, ref);
    kfree_rcu(p, rcu);
}

/* 递减节点引用计数，归零时触发 release_perm_data 延迟释放 */
static void put_perm_data(struct perm_data *data)
{
    kref_put(&data->ref, release_perm_data);
}

/* 新增或覆盖一条 app_profile（Manager/ksud 通过 supercall 调用）：
 * - 校验失败返回 -EINVAL
 * - 内存不足返回 -ENOMEM，条目数达到 u16 上限返回 -E2BIG
 * - 写路径持 allowlist_mutex；替换使用 hlist_replace_rcu，保证并发读者看到旧或新节点之一
 * - 伪 uid 9999 + key "$" 用于更新全局默认非 root 策略
 */
int ksu_set_app_profile(struct app_profile *profile)
{
    /* p 为被覆盖的旧节点，np 为新分配节点；result 为最终返回码 */
    struct perm_data *p, *np;
    int result = 0;

    if (!profile_valid(profile)) {
        pr_err("Failed to set app profile: invalid profile!\n");
        return -EINVAL;
    }

#ifdef CONFIG_KSU_DISABLE_POLICY
    /* 策略关闭时强制回落到内置默认 profile，忽略上层传入的自定义字段，避免策略被绕过 */
    if (profile->allow_su) {
        profile->rp_config.use_default = true;
        memset(profile->rp_config.template_name, 0, sizeof(profile->rp_config.template_name));
        memset(&profile->rp_config.profile, 0, sizeof(profile->rp_config.profile));
    } else {
        profile->nrp_config.use_default = true;
        memset(&profile->nrp_config.profile, 0, sizeof(profile->nrp_config.profile));
    }
#endif

    // only allow default non root profile
    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID && strcmp(profile->key, "$") != 0)) {
        return -EINVAL;
    }

    mutex_lock(&allowlist_mutex);

    /* 先按 curr_uid 查找是否已有条目；同 uid 不同包名只告警（包名可能因重装/改名变化） */
    hash_for_each_possible (allow_list, p, list, profile->curr_uid) {
        if (profile->curr_uid == p->profile.curr_uid) {
            if (strcmp(profile->key, p->profile.key) != 0) {
                pr_warn("ksu_set_app_profile: key changed: uid=%d orig=%s new=%s\n", profile->curr_uid, p->profile.key,
                        profile->key);
            }
            // found it, just override it all!
            /* 分配新节点整体替换旧节点：避免原地修改导致 RCU 读者看到半更新状态 */
            np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
            if (!np) {
                result = -ENOMEM;
                goto out_unlock;
            }
            kref_init(&np->ref);
            memcpy(&np->profile, profile, sizeof(*profile));
            hlist_replace_rcu(&p->list, &np->list);
            /* 旧节点可能仍被读者引用，这里只减引用计数，由 RCU 回调最终释放 */
            put_perm_data(p);
            goto out;
        }
    }

    /* allow_list_count 为 u16，达到上限后继续插入会回绕，必须提前拒绝 */
    if (unlikely(allow_list_count == U16_MAX)) {
        pr_err("too many app profile\n");
        result = -E2BIG;
        goto out_unlock;
    }

    // not found, alloc a new node!
    np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
    if (!np) {
        pr_err("ksu_set_app_profile alloc failed\n");
        result = -ENOMEM;
        goto out_unlock;
    }

    kref_init(&np->ref);
    memcpy(&np->profile, profile, sizeof(*profile));
    if (profile->allow_su) {
        pr_info("set root profile, key: %s, uid: %d, gid: %d, context: %s\n", profile->key, profile->curr_uid,
                profile->rp_config.profile.gid, profile->rp_config.profile.selinux_domain);
    } else {
        pr_info("set app profile, key: %s, uid: %d, umount modules: %d\n", profile->key, profile->curr_uid,
                profile->nrp_config.profile.umount_modules);
    }

    hash_add_rcu(allow_list, &np->list, np->profile.curr_uid);
    ++allow_list_count;

out:
    /* 成功路径统一返回 0；此处 result 覆盖前面可能的旧值，确保 success 语义明确 */
    result = 0;

    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID)) {
        // set default non root profile
        /* 伪 uid 条目不参与按 uid 查询，仅用于同步全局默认 umount 策略 */
        default_non_root_profile.umount_modules = profile->nrp_config.profile.umount_modules;
    }

out_unlock:
    mutex_unlock(&allowlist_mutex);
    return result;
}

/* 核心 root 授权查询：返回 true 表示 uid 被允许 root
 * 顺序为：系统 uid 黑名单 -> manager 特例 -> shell 策略 -> allowlist 哈希表
 * 哈希表遍历在 RCU 读锁内完成；此函数不校验“当前调用者”上下文
 */
bool __ksu_is_allow_uid(uid_t uid)
{
    struct perm_data *p;

    if (forbid_system_uid(uid)) {
        // do not bother going through the list if it's system
        return false;
    }

    if (unlikely(is_uid_manager(uid))) {
        // manager is always allowed!
        /* manager 必须始终有 root，否则无法管理授权列表，因此绕过 allowlist 检查 */
        return true;
    }

    if (unlikely(allow_shell) && uid == SHELL_UID) {
        /* 全局 shell 放行开关；仅当策略开启时生效，属高风险配置 */
        return true;
    }

    rcu_read_lock();
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && p->profile.allow_su) {
            rcu_read_unlock();
            return true;
        }
    }
    rcu_read_unlock();

    return false;
}

/* 仅反映持久授权状态：uid 0 时进一步要求处于 ksu domain；
 * 该函数不检查当前进程上下文，因此不能单独用于授权（见 __ksu_is_allow_uid_for_current）
 */
bool ksu_is_uid_granted_root(uid_t uid)
{
    if (unlikely(uid == 0)) {
        // already root, but only allow our domain.
        /* uid 0 已是 root，但仍限制为 ksu domain，防止其他域冒用授权结果 */
        return is_ksu_domain();
    }
    return __ksu_is_allow_uid(uid);
}

/* 面向“当前调用者”的授权入口：对 shell 额外要求来自 adbd 或 ksu domain，避免任意 shell 提权 */
bool __ksu_is_allow_uid_for_current(uid_t uid)
{
    if (unlikely(uid == SHELL_UID) && !ksu_shell_su_allowed_for_current()) {
        return false;
    }
    return ksu_is_uid_granted_root(uid);
}

/* 判断 uid 是否需要卸载模块：
 * - manager 自身永不卸载
 * - 无 profile 的普通应用使用全局默认策略
 * - 已授予 root 的应用不卸载
 * - 非 root profile 按 use_default 决定用默认值还是自定义值
 */
bool ksu_uid_should_umount(uid_t uid)
{
    /* profile 为查找到的节点数据，可能为 NULL；res 为最终决策结果 */
    struct app_profile *profile;
    bool res;
    if (likely(ksu_is_manager_appid_valid()) && unlikely(ksu_get_manager_appid() == uid % PER_USER_RANGE)) {
        // we should not umount on manager!
        /* 通过 uid % PER_USER_RANGE 比较 appid，兼容多用户下的 manager 安装实例 */
        return false;
    }
#ifdef CONFIG_KSU_DISABLE_POLICY
    /* 策略关闭时退化为“未授权即卸载”，不读取 profile */
    return !__ksu_is_allow_uid(uid);
#else
    rcu_read_lock();
    profile = ksu_get_app_profile(uid);
    if (!profile) {
        // no app profile found, it must be non root app
        res = default_non_root_profile.umount_modules;
    } else if (profile->allow_su) {
        // if found and it is granted to su, we shouldn't umount for it
        res = false;
    } else {
        // found an app profile
        if (profile->nrp_config.use_default) {
            res = default_non_root_profile.umount_modules;
        } else {
            res = profile->nrp_config.profile.umount_modules;
        }
    }
    rcu_read_unlock();

    /* 引用必须在退出 RCU 临界区后释放，put 内部可能触发 kfree_rcu */
    if (profile)
        ksu_put_app_profile(profile);
    return res;
#endif
}

/* 释放 ksu_get_app_profile 返回的引用：由 profile 字段反推 perm_data */
void ksu_put_app_profile(struct app_profile *profile)
{
    struct perm_data *p = container_of(profile, struct perm_data, profile);
    put_perm_data(p);
}

/* 获取 uid 对应的 root_profile：
 * - manager 与 allow_shell 的 shell 使用静态默认 profile
 * - 自定义且非 use_default 的条目返回带引用对象，需 ksu_put_root_profile
 * - 返回静态默认对象时引用计数为 0，put 时应直接返回
 */
struct root_profile *ksu_get_root_profile(uid_t uid)
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    (void)uid;
    return &default_root_profile;
#else
    /* p 为遍历节点；res 为最终返回的 profile 指针 */
    struct perm_data *p = NULL;
    struct root_profile *res;

    rcu_read_lock();
    if (is_uid_manager(uid)) {
        goto use_default;
    }

    if (unlikely(allow_shell && uid == SHELL_UID)) {
        goto use_default;
    }

retry:
    /* 节点在取引用失败时可能被并发替换，需重新从链表查找 */
    res = NULL;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && p->profile.allow_su) {
            if (!p->profile.rp_config.use_default) {
                if (!kref_get_unless_zero(&p->ref)) {
                    goto retry;
                }
                res = &p->profile.rp_config.profile;
            }
            break;
        }
    }

    if (unlikely(!res)) {
    use_default:
        res = &default_root_profile;
    }

    rcu_read_unlock();
    return res;
#endif
}

/* 释放 root_profile 引用：默认 profile 是静态对象，无需递减；自定义对象反推 perm_data 后 put */
void ksu_put_root_profile(struct root_profile *profile)
{
    if (likely(profile == &default_root_profile))
        return;
    /* 由 rp_config.profile 成员地址反推外层 perm_data，才能找到 kref */
    struct perm_data *p = container_of(profile, struct perm_data, profile.rp_config.profile);
    put_perm_data(p);
}

/* 导出 allow/deny 列表给上层（ksud/Manager）：
 * - array 容量为 length，超出部分丢弃但会计入 out_total
 * - out_length/out_total 可为 NULL
 * - manager uid 不导出，避免上层误改自身授权
 */
bool ksu_get_allow_list(int *array, u16 length, u16 *out_length, u16 *out_total, bool allow)
{
    struct perm_data *p = NULL;
    /* i 统计匹配总数，j 记录实际写入 array 的数量（受 length 限制） */
    u16 i = 0, j = 0;
    /* iter 仅作为 hash_for_each_rcu 的桶迭代游标 */
    int iter;
    rcu_read_lock();
    hash_for_each_rcu (allow_list, iter, p, list) {
        if (p->profile.allow_su == allow && !is_uid_manager(p->profile.curr_uid)) {
            if (j < length) {
                array[j++] = p->profile.curr_uid;
            }
            ++i;
        }
    }
    rcu_read_unlock();
    if (out_length) {
        *out_length = j;
    }
    if (out_total) {
        *out_total = i;
    }

    return true;
}

// TODO: move to kernel thread or work queue
/* task_work 回调：在 init 进程返回用户态前执行，把 allowlist 全量落盘
 * 文件格式为 [magic u32][version u32][app_profile ...]，顺序与版本必须与加载端一致
 * 使用 ksu_cred 覆盖 cred，确保有权限写入 /data/adb/ksu
 */
static void do_persistent_allow_list(struct callback_head *_cb)
{
    /* 写入文件头的魔数与格式版本，加载端据此拒绝损坏或过旧的文件 */
    u32 magic = FILE_MAGIC;
    u32 version = FILE_FORMAT_VERSION;
    struct perm_data *p = NULL;
    /* 文件内偏移，由 kernel_write/kernel_read 维护 */
    loff_t off = 0;
    /* i 为哈希桶迭代游标 */
    int i;

    /* 临时切换到 ksu cred，避免调用者（可能是受限进程）无写权限 */
    const struct cred *saved = override_creds(ksu_cred);
    struct file *fp = filp_open(KERNEL_SU_ALLOWLIST, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (IS_ERR(fp)) {
        pr_err("save_allow_list create file failed: %ld\n", PTR_ERR(fp));
        goto out;
    }

    // store magic and version
    /* 任一头部写入失败都直接关闭文件，避免留下格式不完整的文件 */
    if (kernel_write(fp, &magic, sizeof(magic), &off) != sizeof(magic)) {
        pr_err("save_allow_list write magic failed.\n");
        goto close_file;
    }

    if (kernel_write(fp, &version, sizeof(version), &off) != sizeof(version)) {
        pr_err("save_allow_list write version failed.\n");
        goto close_file;
    }

    /* 持锁期间逐个写整个 app_profile，保证写入内容与内存结构一致（布局即持久化格式） */
    mutex_lock(&allowlist_mutex);
    hash_for_each (allow_list, i, p, list) {
        kernel_write(fp, &p->profile, sizeof(p->profile), &off);
    }
    mutex_unlock(&allowlist_mutex);

close_file:
    filp_close(fp, 0);
out:
    /* 恢复原 cred 并释放 task_work 分配的回调结构 */
    revert_creds(saved);
    kfree(_cb);
}

/* 请求异步持久化：把工作挂到 init(task 1) 的 task_work 上，避免在原子上下文直接写文件 */
void ksu_persistent_allow_list()
{
    struct task_struct *tsk;

    rcu_read_lock();
    /* 通过 pid 1 取 task 引用，后续 task_work_add 需要稳定的 task_struct */
    tsk = get_pid_task(find_vpid(1), PIDTYPE_PID);
    if (!tsk) {
        rcu_read_unlock();
        pr_err("save_allow_list find init task err\n");
        return;
    }
    rcu_read_unlock();

    struct callback_head *cb = kzalloc(sizeof(struct callback_head), GFP_KERNEL);
    if (!cb) {
        pr_err("save_allow_list alloc cb err\b");
        goto put_task;
    }
    cb->func = do_persistent_allow_list;
    if (task_work_add(tsk, cb, TWA_RESUME)) {
        /* 入队失败时 cb 仍归调用者所有，必须自行释放 */
        kfree(cb);
        pr_warn("save_allow_list add task_work failed\n");
    }

put_task:
    /* get_pid_task 取得的 task 引用必须释放 */
    put_task_struct(tsk);
}

/* 把旧版本 profile 迁移到当前格式：
 * - v2: 把旧的 "u:r:su:s0" 域替换为默认 ksu 域
 * - v3: 为 root profile 补上 FLAG_KSU_NO_NEW_PRIVS 安全标志
 * 迁移后统一写回 KSU_APP_PROFILE_VER，加载端随后会触发一次重新落盘
 */
static void migrate_profile(u32 version, struct app_profile *profile)
{
    /* domain 指向待迁移的 selinux 域字符串（位于 profile 内） */
    char *domain;
    /* domain_len 固定为字段容量，strscpy_pad 需要该上界 */
    static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);

    switch (version) {
    case 2:
        if (profile->allow_su) {
            domain = profile->rp_config.profile.selinux_domain;
            if (strncmp(domain, "u:r:su:s0", domain_len) == 0) {
                strscpy_pad(domain, KSU_DEFAULT_SELINUX_DOMAIN, domain_len);
                pr_info("migrated domain of profile: %s\n", profile->key);
            }
        }
        fallthrough;
    case 3:
        if (profile->allow_su) {
            /* 旧版本缺少 no_new_privs，迁移时强制补上，避免 su 会话被 setuid 提权 */
            profile->rp_config.profile.flags = FLAG_KSU_NO_NEW_PRIVS;
        }
        break;
    }

    profile->version = KSU_APP_PROFILE_VER;
}

void ksu_load_allow_list()
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    pr_info("allowlist load skipped because policy is disabled\n");
    return;
#endif

    loff_t off = 0;
    ssize_t ret = 0;
    struct file *fp = NULL;
    u32 magic;
    u32 version;
    size_t app_profile_size;

    // load allowlist now!
    fp = filp_open(KERNEL_SU_ALLOWLIST, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("load_allow_list open file failed: %ld\n", PTR_ERR(fp));
        return;
    }

    // verify magic
    if (kernel_read(fp, &magic, sizeof(magic), &off) != sizeof(magic) || magic != FILE_MAGIC) {
        pr_err("allowlist file invalid: %d!\n", magic);
        goto exit;
    }

    // get file version
    if (kernel_read(fp, &version, sizeof(version), &off) != sizeof(version)) {
        pr_err("allowlist read version: %d failed\n", version);
        goto exit;
    }

    if (version < 2 || version > KSU_APP_PROFILE_VER) {
        pr_err("invalid allowlist version: %d\n", version);
        goto exit;
    }

    pr_info("allowlist version: %d\n", version);

    static const size_t kAppProfileSizePreV4 = 776;
    app_profile_size = version < KSU_APP_PROFILE_VER ? kAppProfileSizePreV4 : sizeof(struct app_profile);

    while (true) {
        struct app_profile profile;

        ret = kernel_read(fp, &profile, app_profile_size, &off);

        if (ret != app_profile_size) {
            if (ret != 0)
                pr_info("load_allow_list read err: %zd\n", ret);
            break;
        }

        migrate_profile(version, &profile);

        pr_info("load_allow_uid, name: %s, uid: %d, allow: %d\n", profile.key, profile.curr_uid, profile.allow_su);
        ksu_set_app_profile(&profile);
    }
    filp_close(fp, 0);
    if (version < KSU_APP_PROFILE_VER)
        ksu_persistent_allow_list();
    return;

exit:
    filp_close(fp, 0);
}

void ksu_prune_allowlist(bool (*is_uid_valid)(uid_t, char *, void *), void *data)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    if (!ksu_boot_completed) {
        pr_info("boot not completed, skip prune\n");
        return;
    }

    bool modified = false;
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        uid_t uid = np->profile.curr_uid;
        char *package = np->profile.key;
        // we use this uid for special cases, don't prune it!
        bool is_preserved_uid = uid == KSU_APP_PROFILE_PRESERVE_UID || uid == WEBVIEW_ZYGOTE_UID;
        if (!is_preserved_uid && !is_uid_valid(uid, package, data)) {
            modified = true;
            hlist_del_rcu(&np->list);
            put_perm_data(np);
            --allow_list_count;
        }
    }
    mutex_unlock(&allowlist_mutex);

    if (modified) {
        smp_mb();
        ksu_persistent_allow_list();
    }
}

void __init ksu_allowlist_init(void)
{
    init_default_profiles();
}

void __exit ksu_allowlist_exit(void)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    // free allowlist
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        hlist_del(&np->list);
        put_perm_data(np);
    }
    mutex_unlock(&allowlist_mutex);
}
