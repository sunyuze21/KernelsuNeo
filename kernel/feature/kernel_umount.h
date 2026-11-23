#ifndef __KSU_H_KERNEL_UMOUNT
#define __KSU_H_KERNEL_UMOUNT

#include <linux/types.h>
#include <linux/list.h>
#include <linux/rwsem.h>

// 注册 kernel_umount feature handler；仅 init 阶段调用一次。
void ksu_kernel_umount_init(void);
// 注销 kernel_umount feature handler；仅 exit 阶段调用。
void ksu_kernel_umount_exit(void);

// Handler function to be called from setresuid hook
// old_uid 为切换前的 uid，new_uid 为切换后的 uid。仅当当前进程源自 zygote
// 且新 uid 命中需卸载名单（或为 isolated 进程）时才执行卸载；始终返回 0。
int ksu_handle_umount(uid_t old_uid, uid_t new_uid);

// for the umount list
// 待卸载挂载点条目，由用户态（ksud）通过 supercall 下发后链入 mount_list。
struct mount_entry {
    // 挂载点绝对路径字符串，内核堆分配，与 entry 同生命周期；
    // 读取时需持有 mount_list_lock 的读锁。
    char *umountable;
    // 传给 path_umount() 的标志位（如 MNT_DETACH），取值由用户态决定。
    unsigned int flags;
    // 链表节点，挂到全局 mount_list 上；受 mount_list_lock 保护。
    struct list_head list;
};
// 全局待卸载挂载点链表头，读写均需持有 mount_list_lock。
extern struct list_head mount_list;
// 保护 mount_list 的读写信号量：遍历/挂载时持读锁，增删条目时持写锁。
extern struct rw_semaphore mount_list_lock;

#endif
