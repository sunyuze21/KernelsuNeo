#ifndef __KSU_H_ALLOWLIST
#define __KSU_H_ALLOWLIST

#include <linux/types.h>
#include <linux/uidgid.h>
#include "uapi/app_profile.h"

/* 每个 Android 用户（多用户/工作资料）占用的 UID 区间大小，appid = uid % PER_USER_RANGE */
#define PER_USER_RANGE 100000
/* WebView zygote 的固定 UID，属于需要保留、不能随包卸载被裁剪的特殊条目 */
#define WEBVIEW_ZYGOTE_UID 1053
/* 普通应用 appid 下界（含），用于 is_appuid() 判定 */
#define FIRST_APPLICATION_UID 10000
/* 普通应用 appid 上界（含） */
#define LAST_APPLICATION_UID 19999
/* 隔离进程（isolated process）appid 下界（含） */
#define FIRST_ISOLATED_UID 99000
/* 隔离进程 appid 上界（含） */
#define LAST_ISOLATED_UID 99999

/* 初始化 allowlist 子系统的默认 profile 缓存；必须在允许任何查询前由模块 init 调用 */
void ksu_allowlist_init(void);

/* 释放 allowlist 哈希表内全部 profile 节点；仅在模块卸载路径调用 */
void ksu_allowlist_exit(void);

/* 从 /data/adb/ksu/.allowlist 加载持久化 profile；在 boot 阶段、文件系统就绪后调用 */
void ksu_load_allow_list(void);

// Check if the uid is in allow list
/* 判定 uid 是否被授权 root（含 manager/shell 特例），无锁语义见实现；返回 true 表示允许 */
bool __ksu_is_allow_uid(uid_t uid);
/* 包装热路径：以 unlikely 提示编译器该查询通常为 false，减少分支预测开销 */
#define ksu_is_allow_uid(uid) unlikely(__ksu_is_allow_uid(uid))

// Check if the uid is in allow list, or current is ksu domain root.
// Only reflects the grant state, use ksu_is_allow_uid_for_current() to
// authorize current.
/* 仅反映“是否授予 root”的持久状态；uid 0 时要求当前进程处于 ksu domain，避免误用为当前进程鉴权 */
bool ksu_is_uid_granted_root(uid_t uid);

// ksu_is_uid_granted_root() plus extra restrictions on current, e.g. shell
// may have to descend from adbd or a ksu domain process
/* 在授予态基础上叠加“当前调用者”约束（如 shell 必须来自 adbd/ksu domain）；用于实际授权判定 */
bool __ksu_is_allow_uid_for_current(uid_t uid);
/* 授权判定热路径包装，通常返回 false，故使用 unlikely */
#define ksu_is_allow_uid_for_current(uid) unlikely(__ksu_is_allow_uid_for_current(uid))

/* 导出 allow/deny 两类 uid 列表到调用者数组：length 为容量，out_length 为实际写入数，out_total 为总数（可能大于容量） */
bool ksu_get_allow_list(int *array, u16 length, u16 *out_length, u16 *out_total, bool allow);

/* 遍历并删除已不存在对应包的条目；回调返回 false 即视为失效。boot 未完成时会跳过，避免误删 */
void ksu_prune_allowlist(bool (*is_uid_exist)(uid_t, char *, void *), void *data);
/* 异步（task_work）把当前 allowlist 全量写回 /data/adb/ksu/.allowlist */
void ksu_persistent_allow_list();

// should be called with rcu read lock
/* 按 uid 查找 app_profile 并增加引用计数；调用者必须持有 rcu_read_lock，用完调用 ksu_put_app_profile */
struct app_profile *ksu_get_app_profile(uid_t uid);
// only used to put the app_profile returned by ksu_get_app_profile
/* 释放 ksu_get_app_profile 取得的引用；kref 归零后经 RCU 延迟释放 */
void ksu_put_app_profile(struct app_profile *);
/* 新增或覆盖一条 profile；内部持 allowlist_mutex，返回 0 成功，负数 errno 失败 */
int ksu_set_app_profile(struct app_profile *);

/* 判断该 uid 的进程是否应卸载模块（用于非 root 应用的隐藏策略） */
bool ksu_uid_should_umount(uid_t uid);
/* 获取 root profile：自定义 profile 返回带引用的对象，否则返回静态默认 profile */
struct root_profile *ksu_get_root_profile(uid_t uid);
// only used to put the root_profile returned by ksu_get_root_profile
/* 释放自定义 root_profile 的引用；默认 profile 为静态对象，直接返回 */
void ksu_put_root_profile(struct root_profile *);

/* 判断 uid 是否为普通应用（排除隔离进程/系统进程），多用户通过取模归一化 */
static inline bool is_appuid(uid_t uid)
{
    uid_t appid = uid % PER_USER_RANGE;
    return appid >= FIRST_APPLICATION_UID && appid <= LAST_APPLICATION_UID;
}

/* 判断 uid 是否属于隔离进程区间；隔离进程通常无持久包名，裁剪逻辑需谨慎对待 */
static inline bool is_isolated_process(uid_t uid)
{
    uid_t appid = uid % PER_USER_RANGE;
    return appid >= FIRST_ISOLATED_UID && appid <= LAST_ISOLATED_UID;
}
#endif
