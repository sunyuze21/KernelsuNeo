#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/task_work.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/mount.h>
#include <linux/namei.h>
#include <linux/nsproxy.h>
#include <linux/path.h>
#include <linux/printk.h>
#include <linux/types.h>

#include "feature/kernel_umount.h"
#include "klog.h" // IWYU pragma: keep
#include "policy/allowlist.h"
#include "selinux/selinux.h"
#include "policy/feature.h"
#include "runtime/ksud_boot.h"
#include "ksu.h"

// 特性开关：默认开启，通过 supercall 由用户态读写。仅在 hook 路径读取，
// 单字节 bool 赋值本身原子，无需额外锁。
static bool ksu_kernel_umount_enabled = true;

// 读取 kernel_umount 特性状态，写入 *value（0/1）。始终返回 0。
static int kernel_umount_feature_get(u64 *value)
{
    *value = ksu_kernel_umount_enabled ? 1 : 0;
    return 0;
}

// 设置 kernel_umount 特性开关；value 非 0 即开启。仅更新标志，
// 不触发任何立即卸载动作。始终返回 0。
static int kernel_umount_feature_set(u64 value)
{
    bool enable = value != 0;
    ksu_kernel_umount_enabled = enable;
    pr_info("kernel_umount: set to %d\n", enable);
    return 0;
}

// 描述 kernel_umount 特性，供 supercall 按 feature_id 分发 get/set。
static const struct ksu_feature_handler kernel_umount_handler = {
    .feature_id = KSU_FEATURE_KERNEL_UMOUNT,
    .name = "kernel_umount",
    .get_handler = kernel_umount_feature_get,
    .set_handler = kernel_umount_feature_set,
};

// 内核内部用于卸载单个挂载点的符号：path_umount() 直接操作 struct path，
// 无锁假设调用者已持有有效的 path 引用（下方 try_umount 通过 kern_path 获取）。
extern int path_umount(struct path *path, int flags);

// 调用 path_umount() 执行卸载，失败仅打印日志（不向上传播错误）。
// 注意：path_umount() 会消耗/释放 path 引用，调用后不应再 path_put()。
static void ksu_umount_mnt(const char *mnt, struct path *path, int flags)
{
    int err = path_umount(path, flags);
    if (err) {
        pr_info("umount %s failed: %d\n", mnt, err);
    }
}

// 尝试卸载指定路径：通过 kern_path() 取得 path 引用，若该 dentry 不是
// 挂载点根（可能已被其他进程卸载）则直接 path_put 返回。此处运行在
// 目标进程自己的 mount namespace 中，因此只能影响该进程可见的挂载。
static void try_umount(const char *mnt, int flags)
{
    struct path path;
    int err = kern_path(mnt, 0, &path);
    if (err) {
        return;
    }

    if (path.dentry != path.mnt->mnt_root) {
        // it is not root mountpoint, maybe umounted by others already.
        path_put(&path);
        return;
    }

    ksu_umount_mnt(mnt, &path, flags);
}

// task_work 回调包装：内嵌 callback_head，供需要在进程上下文延迟执行
// 卸载时使用（当前实现直接在 hook 中同步卸载，此结构为预留/扩展点）。
struct umount_tw {
    // 内核 task_work 链表节点，由 task_work_add() 排队后在进程上下文回调。
    struct callback_head cb;
};

// setresuid hook 的卸载处理入口。仅在确认是 zygote 派生的子进程时，
// 在“当前进程的 mount namespace”里逐个卸载 mount_list 中的挂载点，
// 从而对非 root 应用隐藏通过模块注入的挂载。始终返回 0。
int ksu_handle_umount(uid_t old_uid, uid_t new_uid)
{
    // if there isn't any module mounted, just ignore it!
    if (!ksu_module_mounted) {
        return 0;
    }

    if (!ksu_kernel_umount_enabled) {
        return 0;
    }

    // There are 6 scenarios:
    // 1. Normal app: zygote -> appuid
    // 2. Isolated process forked from zygote: zygote -> isolated_process
    // 3. App zygote forked from zygote: zygote -> appuid
    // 4. Webview zygote forked from zygote: zygote -> webview_zygote
    // 5. Isolated process forked from app zygote: appuid -> isolated_process (already handled by 3)
    // 6. Isolated process forked from webview zygote (already handled by 4)
    if (!is_appuid(new_uid) && new_uid != WEBVIEW_ZYGOTE_UID && !is_isolated_process(new_uid)) {
        return 0;
    }

    if (!ksu_uid_should_umount(new_uid) && !is_isolated_process(new_uid)) {
        return 0;
    }

    // check old process's selinux context, if it is not zygote, ignore it!
    // because some su apps may setuid to untrusted_app but they are in global mount namespace
    // when we umount for such process, that is a disaster!
    // also handle case 4 and 5
    bool is_zygote_child = is_zygote(current_cred());
    if (!is_zygote_child) {
        pr_info("handle umount ignore non zygote child: %d\n", current->pid);
        return 0;
    }
    // umount the target mnt
    pr_info("handle umount for uid: %d, pid: %d\n", new_uid, current->pid);

    // 临时切换到 KernelSU 的高权限 cred，确保有权限访问并卸载这些挂载点；
    // 必须与末尾 revert_creds(saved) 配对，否则会污染返回用户态的凭据。
    const struct cred *saved = override_creds(ksu_cred);

    // mount_list 的遍历条目指针；仅在持读锁期间有效。
    struct mount_entry *entry;
    // 读锁允许多个进程并发卸载各自 namespace，写者仅在增删列表时获取。
    down_read(&mount_list_lock);
    list_for_each_entry (entry, &mount_list, list) {
        pr_info("%s: unmounting: %s flags: 0x%x\n", __func__, entry->umountable, entry->flags);
        try_umount(entry->umountable, entry->flags);
    }
    up_read(&mount_list_lock);

    revert_creds(saved);

    return 0;
}

// 模块初始化时注册 kernel_umount 特性处理器，供 supercall 开关该特性。
// 注册失败仅告警，特性保持默认值，不影响模块加载。
void __init ksu_kernel_umount_init(void)
{
    if (ksu_register_feature_handler(&kernel_umount_handler)) {
        pr_err("Failed to register kernel_umount feature handler\n");
    }
}

// 模块退出时注销 kernel_umount 特性处理器；不主动清理 mount_list
// （条目由 allowlist/用户态流程负责释放）。
void __exit ksu_kernel_umount_exit(void)
{
    ksu_unregister_feature_handler(KSU_FEATURE_KERNEL_UMOUNT);
}
