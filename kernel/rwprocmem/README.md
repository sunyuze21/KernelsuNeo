# rwProcMem（裁剪版：仅进程提权）

从 [rwProcMem33](https://github.com/rwProcMem/rwProcMem33) 的 `rwProcMem33Module/rwProcMem_module`
移植，已编译进 `kernelsu.ko`。当前只保留原驱动的 **`SetProcessRoot`** 能力：
把指定进程的 `cred` 改成 `uid/gid = 0` 并授予全部 capability。

与上游版本的区别：

- 不再创建 `/proc` 节点，改为通过 KernelSU 的 supercall ioctl 调用。
- 不再接收用户态传入的偏移初始化数据，内核自己校准 `task->cred` 偏移。
- 删除了内存读写、maps、cmdline、RSS、PID 列表、物理内存访问、kprobe 隐藏等逻辑。
- 仅 arm64 编译（`kernel/Kbuild` 中按 `CONFIG_ARM64` 引入）。

## 调用方式

UAPI 新增（`uapi/supercall.h`，UAPI 版本升到 7）：

```c
struct ksu_set_process_root_cmd {
    __s32 pid;   /* Input: target pid */
    __u32 flags; /* Input: reserved, must be 0 */
};

static const __u32 KSU_IOCTL_SET_PROCESS_ROOT =
    _IOW('K', 23, struct ksu_set_process_root_cmd);
```

内核侧权限为 `only_root`，即调用进程的 uid 必须是 0。

ksud 新增命令：

```bash
su -c 'ksud kernel set-root <pid>'
```

成功返回 0；失败返回负 errno（例如目标进程不存在时为 `-ESRCH`）。

对应 Rust 封装在 `userspace/ksud/src/ksucalls.rs`：

```rust
pub fn set_process_root(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_process_root_cmd { pid, flags: 0 };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_PROCESS_ROOT, &raw mut cmd)?;
    Ok(())
}
```

## 内核实现

- `rwprocmem.c`：导出 `ksu_rwprocmem_set_root(pid)`，由
  `kernel/supercall/dispatch.c` 的 `do_set_process_root()` 调用。
- `proc_root.h` / `proc_root_auto_offset.h`：保留上游的 cred 偏移探测和
  `set_process_root()` 实现。
- 偏移校准在 `ksu_rwprocmem_init()` 时尝试一次；如果失败，第一次调用
  `SET_PROCESS_ROOT` 时会用当前任务重新校准。
- 校准失败会在 dmesg 打印：

  ```text
  rwProcMem: cred offset calibration failed
  ```

## 注意事项

- 目标进程获得的是 **Unix root + capabilities**，SELinux domain 不变，
  不等同于完整的 `su` 域。
- 部分 App 的 uid 被改成 0 后可能被系统或自身逻辑重启/异常。
- 该接口只允许 root 调用；普通 App 必须先通过 `su` 获得 root。
- 目前只在 `android15-6.6` 上编译验证过。

## 编译

```bash
python3 scripts/build_apk.py --clean --kernel -k android15-6.6
```

因为 UAPI 版本从 6 升到 7，`ksud`、Manager APK 和 `kernelsu.ko` 必须一起重新构建。
