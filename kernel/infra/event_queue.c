#include <linux/ktime.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/overflow.h>
#include <linux/poll.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/wait.h>

#include "infra/event_queue.h"

/* 队列节点：定长头 + 柔性数组负载，一次性分配，读取后整体释放。 */
struct ksu_event_queue_node {
    /* 挂入 queue->pending，按入队顺序。 */
    struct list_head list;
    /* 对外暴露的记录头，seq/ts_ns 在成功入队时填写。 */
    struct ksu_event_record_hdr hdr;
    /* 负载区，长度即 hdr.len；由 push 复制，读者不可修改。 */
    __u8 payload[];
};

/* 记录总大小 = 头 + 负载；用于容量检查与 read 返回值。 */
static size_t ksu_event_queue_record_size(__u32 payload_len)
{
    return sizeof(struct ksu_event_record_hdr) + payload_len;
}

/* 在持锁状态下累加一次丢弃，并扩展待上报区间；调用者必须持有 queue->lock。 */
static void ksu_event_queue_note_drop_locked(struct ksu_event_queue *queue, __u64 seq)
{
    queue->dropped_total++;
    /* 区间内第一条丢弃决定 first_seq */
    if (!queue->dropped_pending) {
        queue->dropped_first_seq = seq;
    }
    queue->dropped_pending++;
    queue->dropped_last_seq = seq;
}

/* 持锁判断是否有可读内容：待上报丢弃、上报中记录或 pending 链表。 */
static bool ksu_event_queue_has_data_locked(const struct ksu_event_queue *queue)
{
    return queue->dropped_pending || queue->dropped_inflight || !list_empty(&queue->pending);
}

/* 置 closed 标记；使用 irqsave 以兼容中断上下文的生产者。 */
static void ksu_event_queue_mark_closed(struct ksu_event_queue *queue)
{
    unsigned long irq_flags;

    spin_lock_irqsave(&queue->lock, irq_flags);
    queue->closed = true;
    spin_unlock_irqrestore(&queue->lock, irq_flags);
}

/* 初始化所有字段；seq 从 1 开始，0 保留给"无记录"语义。 */
void ksu_event_queue_init(struct ksu_event_queue *queue, __u32 max_queued, __u32 max_payload_len)
{
    spin_lock_init(&queue->lock);
    mutex_init(&queue->read_lock);
    INIT_LIST_HEAD(&queue->pending);
    init_waitqueue_head(&queue->read_wait);
    queue->queued = 0;
    queue->max_queued = max_queued;
    queue->max_payload_len = max_payload_len;
    queue->next_seq = 1;
    queue->dropped_total = 0;
    queue->dropped_pending = 0;
    queue->dropped_first_seq = 0;
    queue->dropped_last_seq = 0;
    queue->dropped_inflight = 0;
    queue->dropped_inflight_first_seq = 0;
    queue->dropped_inflight_last_seq = 0;
    queue->closed = false;
}

/* 关闭队列并释放所有待读节点。先唤醒阻塞读者，再在 read_lock 下清空链表，
 * 与并发 read 互斥；清空后仍保留结构体本身（无额外内存）。 */
void ksu_event_queue_destroy(struct ksu_event_queue *queue)
{
    struct ksu_event_queue_node *node, *tmp; /* 遍历用节点与其后继 */
    unsigned long irq_flags;

    ksu_event_queue_mark_closed(queue);
    wake_up_interruptible_poll(&queue->read_wait, EPOLLHUP | POLLHUP);

    mutex_lock(&queue->read_lock);
    spin_lock_irqsave(&queue->lock, irq_flags);
    list_for_each_entry_safe (node, tmp, &queue->pending, list) {
        list_del(&node->list);
        kfree(node);
    }
    queue->queued = 0;
    queue->dropped_pending = 0;
    queue->dropped_first_seq = 0;
    queue->dropped_last_seq = 0;
    queue->dropped_inflight = 0;
    queue->dropped_inflight_first_seq = 0;
    queue->dropped_inflight_last_seq = 0;
    spin_unlock_irqrestore(&queue->lock, irq_flags);
    mutex_unlock(&queue->read_lock);

    wake_up_interruptible_poll(&queue->read_wait, EPOLLHUP | POLLHUP);
}

/*
 * 追加一条记录。分配在锁外进行（gfp 可睡眠），入队时再取锁；即使分配失败或
 * 队列满也占用一个序号并记一次丢弃，使读者能通过序号缺口发现丢失。
 * 返回值见头文件。
 */
int ksu_event_queue_push(struct ksu_event_queue *queue, __u16 type, __u16 flags, const void *payload, __u32 len,
                         gfp_t gfp)
{
    struct ksu_event_queue_node *node = NULL; /* 新节点，入队成功前归本地所有 */
    unsigned long irq_flags;
    __u64 seq; /* 本次事件占用的序号，无论是否真正入队 */
    bool wake = false;
    int ret = 0;

    if (len > queue->max_payload_len) {
        return -EMSGSIZE;
    }

    if (len && !payload) {
        return -EINVAL;
    }

    node = kmalloc(struct_size(node, payload, len), gfp);

    if (node) {
        INIT_LIST_HEAD(&node->list);
        node->hdr.type = type;
        node->hdr.flags = flags;
        node->hdr.len = len;
        /* seq/ts_ns 在持锁入队时填写，丢弃路径不会发布该节点 */
        node->hdr.ts_ns = 0;
        node->hdr.seq = 0;

        if (len) {
            memcpy(node->payload, payload, len);
        }
    }

    spin_lock_irqsave(&queue->lock, irq_flags);
    if (queue->closed) {
        ret = -EPIPE;
        goto out_unlock;
    }

    /* 序号在锁内分配，保证与丢弃记录顺序一致 */
    seq = queue->next_seq++;
    if (!node || (queue->max_queued && queue->queued >= queue->max_queued)) {
        ksu_event_queue_note_drop_locked(queue, seq);
        wake = true;
        ret = node ? -ENOSPC : -ENOMEM;
        goto out_unlock;
    }

    node->hdr.seq = seq;
    node->hdr.ts_ns = ktime_get_ns();
    list_add_tail(&node->list, &queue->pending);
    queue->queued++;
    wake = true;

out_unlock:
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    /* 未入队的节点在锁外释放，避免在自旋锁内 kfree */
    if (ret && node) {
        kfree(node);
    }

    /* 唤醒放在解锁后，减少读者争用 */
    if (wake) {
        wake_up_interruptible_poll(&queue->read_wait, EPOLLIN | EPOLLRDNORM);
    }

    return ret;
}

/* 主动记录一次丢弃，不分配内存；关闭后静默返回。 */
void ksu_event_queue_drop(struct ksu_event_queue *queue)
{
    unsigned long irq_flags;
    __u64 seq;

    spin_lock_irqsave(&queue->lock, irq_flags);
    if (queue->closed) {
        spin_unlock_irqrestore(&queue->lock, irq_flags);
        return;
    }

    seq = queue->next_seq++;
    ksu_event_queue_note_drop_locked(queue, seq);
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    wake_up_interruptible_poll(&queue->read_wait, EPOLLIN | EPOLLRDNORM);
}

/* 等待可读数据或关闭。返回 0 表示可继续读取，-EAGAIN 非阻塞无数据，
 * 其余负值为被信号打断的返回值（调用方应原样上抛）。 */
static int ksu_event_queue_wait_ready(struct ksu_event_queue *queue, int file_flags)
{
    int ret;

    for (;;) {
        if (ksu_event_queue_has_data(queue)) {
            return 0;
        }

        if (READ_ONCE(queue->closed)) {
            return 0;
        }

        if (file_flags & O_NONBLOCK) {
            return -EAGAIN;
        }

        /* 条件在锁内重新检查，避免丢失唤醒 */
        ret = wait_event_interruptible(queue->read_wait, queue->closed || ksu_event_queue_has_data(queue));
        if (ret) {
            return ret;
        }
    }
}

static ssize_t ksu_event_queue_read_drop(struct ksu_event_queue *queue, char __user *buf, size_t count)
{
    struct ksu_event_record_hdr hdr;
    struct ksu_event_queue_dropped_info info;
    size_t record_size = ksu_event_queue_record_size(sizeof(info));
    unsigned long irq_flags;

    spin_lock_irqsave(&queue->lock, irq_flags);
    if (!queue->dropped_pending) {
        spin_unlock_irqrestore(&queue->lock, irq_flags);
        return 0;
    }
    if (count < record_size) {
        spin_unlock_irqrestore(&queue->lock, irq_flags);
        return -EMSGSIZE;
    }

    hdr.type = KSU_EVENT_QUEUE_TYPE_DROPPED;
    hdr.flags = KSU_EVENT_RECORD_FLAG_INTERNAL;
    hdr.len = sizeof(info);
    hdr.seq = queue->dropped_first_seq;
    hdr.ts_ns = ktime_get_ns();

    info.dropped = queue->dropped_pending;
    info.first_seq = queue->dropped_first_seq;
    info.last_seq = queue->dropped_last_seq;

    queue->dropped_inflight = queue->dropped_pending;
    queue->dropped_inflight_first_seq = queue->dropped_first_seq;
    queue->dropped_inflight_last_seq = queue->dropped_last_seq;
    queue->dropped_pending = 0;
    queue->dropped_first_seq = 0;
    queue->dropped_last_seq = 0;
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    if (copy_to_user(buf, &hdr, sizeof(hdr))) {
        goto out_restore;
    }

    if (copy_to_user(buf + sizeof(hdr), &info, sizeof(info))) {
        goto out_restore;
    }

    spin_lock_irqsave(&queue->lock, irq_flags);
    queue->dropped_inflight = 0;
    queue->dropped_inflight_first_seq = 0;
    queue->dropped_inflight_last_seq = 0;
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    return record_size;

out_restore:
    spin_lock_irqsave(&queue->lock, irq_flags);
    if (!queue->dropped_pending) {
        queue->dropped_pending = queue->dropped_inflight;
        queue->dropped_first_seq = queue->dropped_inflight_first_seq;
        queue->dropped_last_seq = queue->dropped_inflight_last_seq;
    } else {
        queue->dropped_pending += queue->dropped_inflight;
        queue->dropped_first_seq = queue->dropped_inflight_first_seq;
    }
    queue->dropped_inflight = 0;
    queue->dropped_inflight_first_seq = 0;
    queue->dropped_inflight_last_seq = 0;
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    return -EFAULT;
}

static ssize_t ksu_event_queue_read_node(struct ksu_event_queue *queue, char __user *buf, size_t count)
{
    struct ksu_event_queue_node *node;
    struct list_head *first;
    size_t record_size;
    unsigned long irq_flags;

    spin_lock_irqsave(&queue->lock, irq_flags);
    if (list_empty(&queue->pending)) {
        spin_unlock_irqrestore(&queue->lock, irq_flags);
        return 0;
    }

    first = queue->pending.next;
    node = list_entry(first, struct ksu_event_queue_node, list);
    record_size = ksu_event_queue_record_size(node->hdr.len);
    if (count < record_size) {
        spin_unlock_irqrestore(&queue->lock, irq_flags);
        return -EMSGSIZE;
    }
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    if (copy_to_user(buf, &node->hdr, sizeof(node->hdr))) {
        return -EFAULT;
    }

    if (node->hdr.len && copy_to_user(buf + sizeof(node->hdr), node->payload, node->hdr.len)) {
        return -EFAULT;
    }

    spin_lock_irqsave(&queue->lock, irq_flags);
    list_del(first);
    queue->queued--;
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    kfree(node);
    return record_size;
}

ssize_t ksu_event_queue_read(struct ksu_event_queue *queue, char __user *buf, size_t count, int file_flags)
{
    ssize_t ret;
    ssize_t copied = 0;

    if (!count) {
        return 0;
    }

    ret = mutex_lock_interruptible(&queue->read_lock);
    if (ret) {
        return ret;
    }

    ret = ksu_event_queue_wait_ready(queue, file_flags);
    if (ret) {
        copied = ret;
        goto out_unlock;
    }

    while (count > 0) {
        ret = ksu_event_queue_read_drop(queue, buf, count);
        if (ret < 0) {
            if (!copied) {
                copied = ret;
            }
            break;
        }
        if (ret > 0) {
            copied += ret;
            buf += ret;
            count -= ret;
            continue;
        }

        ret = ksu_event_queue_read_node(queue, buf, count);
        if (ret < 0) {
            if (!copied) {
                copied = ret;
            }
            break;
        }
        if (ret == 0) {
            break;
        }

        copied += ret;
        buf += ret;
        count -= ret;
    }

    if (!copied && READ_ONCE(queue->closed)) {
        copied = 0;
    }

out_unlock:
    mutex_unlock(&queue->read_lock);
    return copied;
}

__poll_t ksu_event_queue_poll(struct ksu_event_queue *queue, struct file *file, poll_table *wait)
{
    __poll_t mask = 0;
    unsigned long irq_flags;

    poll_wait(file, &queue->read_wait, wait);

    spin_lock_irqsave(&queue->lock, irq_flags);
    if (ksu_event_queue_has_data_locked(queue)) {
        mask |= POLLIN | POLLRDNORM;
    }
    if (queue->closed) {
        mask |= POLLHUP;
    }
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    return mask;
}

void ksu_event_queue_close(struct ksu_event_queue *queue)
{
    ksu_event_queue_mark_closed(queue);
    wake_up_interruptible_poll(&queue->read_wait, EPOLLHUP | POLLHUP);
}

bool ksu_event_queue_has_data(struct ksu_event_queue *queue)
{
    bool has_data;
    unsigned long irq_flags;

    spin_lock_irqsave(&queue->lock, irq_flags);
    has_data = ksu_event_queue_has_data_locked(queue);
    spin_unlock_irqrestore(&queue->lock, irq_flags);

    return has_data;
}
