#ifndef KSU_EVENT_QUEUE_H
#define KSU_EVENT_QUEUE_H

/*
 * 内核侧事件队列：把内核事件（记录头 + 负载）排队，供用户态通过 read/poll
 * 消费。单生产者可并发 push，单读者串行 read；队列满时丢弃并生成一条
 * "dropped" 汇总记录，保证读者能感知缺口而不阻塞内核路径。
 */
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/poll.h>
#include <linux/spinlock.h>
#include <linux/types.h>
#include <linux/wait.h>

/* flags 的 bit0：该记录由内核内部生成（例如 dropped 汇总），非业务事件。 */
#define KSU_EVENT_RECORD_FLAG_INTERNAL (1U << 0)
/* 保留 type 值：表示"发生过丢弃"的汇总记录，负载为 dropped_info。 */
#define KSU_EVENT_QUEUE_TYPE_DROPPED ((__u16)0xFFFF)

/* 每条记录的固定头；payload 紧随其后，len 为其字节数（不含头）。 */
struct ksu_event_record_hdr {
    /* 事件类型，由生产者定义；KSU_EVENT_QUEUE_TYPE_DROPPED 为保留值。 */
    __u16 type;
    /* KSU_EVENT_RECORD_FLAG_* 位标志。 */
    __u16 flags;
    /* 负载长度，push 时受 max_payload_len 限制。 */
    __u32 len;
    /* 全局单调序号，从 1 开始；用于识别丢弃区间。 */
    __u64 seq;
    /* 入队时刻的 CLOCK_MONOTONIC 纳秒时间戳。 */
    __u64 ts_ns;
};

/* dropped 记录的负载：一次汇总中丢弃的条数与序号范围。 */
struct ksu_event_queue_dropped_info {
    /* 本区间丢弃的事件总数。 */
    __u64 dropped;
    /* 区间内第一条被丢弃事件的序号。 */
    __u64 first_seq;
    /* 区间内最后一条被丢弃事件的序号。 */
    __u64 last_seq;
};

/*
 * 队列主体。push 侧在自旋锁内维护 pending/dropped_*，read 侧另用 read_lock
 * 保证只有一个读者；read_wait 用于阻塞 read 与 poll。队列生命周期由持有者
 * 管理，destroy/close 后不再接受新事件。
 */
struct ksu_event_queue {
    /* 保护 pending 链表、计数与 dropped_* 统计；可在中断上下文使用。 */
    spinlock_t lock;
    /* The first implementation supports a single reader. */
    /* 串行化 read 路径，避免多个读者同时摘链/复制；可睡眠。 */
    struct mutex read_lock;
    /* 已入队未被读取的记录，按 seq 升序。 */
    struct list_head pending;
    /* 新数据或关闭时唤醒阻塞读者/轮询者。 */
    wait_queue_head_t read_wait;
    /* 当前 pending 中的记录数。 */
    __u32 queued;
    /* 允许排队的记录上限，0 表示不限制（仍受内存约束）。 */
    __u32 max_queued;
    /* 单条负载字节数上限，超出 push 返回 -EMSGSIZE。 */
    __u32 max_payload_len;
    /* 下一条记录的序号，单调递增，即使丢弃也占用序号。 */
    __u64 next_seq;
    /* 累计丢弃总数（含已上报与未上报）。 */
    __u64 dropped_total;
    /* 尚未被读者取走的丢弃条数。 */
    __u64 dropped_pending;
    /* 待上报丢弃区间的首序号，dropped_pending 为 0 时无意义。 */
    __u64 dropped_first_seq;
    /* 待上报丢弃区间的末序号。 */
    __u64 dropped_last_seq;
    /* 正在通过 copy_to_user 上报的丢弃条数；复制失败时用于回滚。 */
    __u64 dropped_inflight;
    /* 上报中区间的首序号，失败回滚时恢复。 */
    __u64 dropped_inflight_first_seq;
    /* 上报中区间的末序号，失败回滚时恢复。 */
    __u64 dropped_inflight_last_seq;
    /* 已关闭：push/drop 返回 -EPIPE，read 读空后返回 0，poll 报 POLLHUP。 */
    bool closed;
};

/* 初始化队列结构，max_queued/max_payload_len 为容量约束；不做内存分配。 */
void ksu_event_queue_init(struct ksu_event_queue *queue, __u32 max_queued, __u32 max_payload_len);
/* 标记关闭、唤醒读者并释放所有 pending 记录；之后不得再 push。 */
void ksu_event_queue_destroy(struct ksu_event_queue *queue);

/*
 * 复制负载并追加一条记录。gfp 决定是否可睡眠（原子上下文用 GFP_ATOMIC）。
 * 返回 0 成功；-EMSGSIZE 负载超限；-EINVAL 负载为空指针；-ENOMEM 分配失败；
 * -ENOSPC 队列满（两种情况都会记录一次 drop）；-EPIPE 队列已关闭。
 */
int ksu_event_queue_push(struct ksu_event_queue *queue, __u16 type, __u16 flags, const void *payload, __u32 len,
                         gfp_t gfp);
/* 不携带负载地记一次丢弃（占一个序号），用于生产者主动跳过事件。 */
void ksu_event_queue_drop(struct ksu_event_queue *queue);

/*
 * 读取记录到用户缓冲区，可能一次返回多条。file_flags 含 O_NONBLOCK 时无数据
 * 立即返回 -EAGAIN；count 小于单条记录大小时返回 -EMSGSIZE；copy_to_user 失败
 * 返回 -EFAULT。返回复制的总字节数，关闭且无数据时为 0。
 */
ssize_t ksu_event_queue_read(struct ksu_event_queue *queue, char __user *buf, size_t count, int file_flags);
/* poll 回调：有数据置 POLLIN|POLLRDNORM，关闭置 POLLHUP；不会睡眠。 */
__poll_t ksu_event_queue_poll(struct ksu_event_queue *queue, struct file *file, poll_table *wait);

/* 仅标记关闭并唤醒等待者，不释放记录；供 release 等路径调用。 */
void ksu_event_queue_close(struct ksu_event_queue *queue);
/* 在自旋锁内查询是否有可读数据（含待上报的丢弃记录）。 */
bool ksu_event_queue_has_data(struct ksu_event_queue *queue);

#endif // KSU_EVENT_QUEUE_H
