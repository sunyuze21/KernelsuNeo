#ifndef __KSU_H_SELINUX_HIDE
#define __KSU_H_SELINUX_HIDE

// 初始化 SELinux 隐藏特性：注册 feature handler，并按加载时机
// （late_load 与否）决定是立即初始化 fake status 还是先使能 static key，
// 同时安装 selinuxfs status open 钩子。仅在模块 init 阶段调用。
void ksu_selinux_hide_init();
// 退出 SELinux 隐藏特性：注销 feature handler、撤销所有 hook，
// 并在 status_lock 保护下释放 fake_status 页面。仅模块 exit 阶段调用。
void ksu_selinux_hide_exit();
// 若特性从未启用过，则释放为兼容检索而保留的 backup_sepolicy
// （sidtab 与 policy 本体），避免长期占用内存。由启动流程在
// 确定不再需要备份策略后调用；加 selinux_hide_mutex 串行化。
void ksu_selinux_hide_drop_backup_if_unused();
// second stage 阶段调用：尝试初始化 fake status，成功后关闭
// fake_status_initialize_key，避免后续 open 路径再做懒初始化。
void ksu_selinux_hide_handle_second_stage();
// post-fs-data 阶段调用：无条件关闭懒初始化 static key，
// 并检查 fake status 是否已就绪（未就绪仅打印错误，不失败）。
void ksu_selinux_hide_handle_post_fs_data();

#endif
