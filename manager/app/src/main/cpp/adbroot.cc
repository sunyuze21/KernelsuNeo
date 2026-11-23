#include <string>
#include <dlfcn.h>
#include <cstdlib>
#include <unistd.h>
#include <vector>
#include <sys/system_properties.h>

// Skip drop privileges
// https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/adb/daemon/main.cpp;l=123;drc=3b74954ec50836e0c8faeed1877fefb1dc2de006
// 让 adbd 认为系统是 debuggable，从而跳过降权分支；该符号被 adbd 动态解析，必须保持默认可见性。
extern "C"
[[gnu::visibility("default"), gnu::used]]
// NOLINTNEXTLINE(bugprone-reserved-identifier)
int __android_log_is_debuggable() {
    return 1;
}

// 占位结构：只需提供非空地址来冒充 "service.adb.root" 的属性记录，
// adbd 不会解引用其内容，读取时由下面的 read_callback 拦截。
struct prop_info {};

// 在进程生命周期内保持有效，不能用栈上临时对象，否则属性查询会拿到悬空指针。
static struct prop_info g_adb_root_prop;

// Enable adb root by default, without run `adb root` command explicitly or setprop
// https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/adb/daemon/main.cpp;l=84;drc=3b74954ec50836e0c8faeed1877fefb1dc2de006
// https://cs.android.com/android/platform/superproject/+/android-latest-release:system/libbase/properties.cpp;l=159-175;drc=6d19b5c690fa4220c10e42c2326150bbd4d4b7bb
// android::base::GetProperty is from libbase.so, but some adbd are statically-linked to it, 
// so we replace these two underlying functions.

// 拦截属性查找：命中原属性名时返回伪造记录，其余查询透传给真正的 libc 实现。
extern "C" [[gnu::visibility("default"), gnu::used]]
const prop_info* __system_property_find(const char* name) {
    // 首次调用时才 dlsym，避免初始化顺序依赖；指针缓存后不再解析。
    static decltype(__system_property_find) *system_property_find_fn = nullptr;
    if (strcmp(name, "service.adb.root") == 0) {
        return &g_adb_root_prop;
    }
    if (!system_property_find_fn) {
        system_property_find_fn = (decltype(system_property_find_fn)) dlsym(RTLD_NEXT, "__system_property_find");
    }
    return system_property_find_fn(name);
}

// 拦截属性读取：伪造记录直接回调 "service.adb.root" = "1"，使 adbd 默认以 root 运行。
// callback 在 Android 上可能为 nullptr，必须判空后再调用。
extern "C" [[gnu::visibility("default"), gnu::used]]
void __system_property_read_callback(const prop_info* pi,
    void (*callback)(void* cookie, const char* name, const char* value, uint32_t serial),
    void* cookie) {
    // 同样懒加载原函数指针，非伪造记录的读取仍走系统实现。
    static decltype(__system_property_read_callback) *orig_fn = nullptr;
    if (pi == &g_adb_root_prop) {
        if (callback) {
            callback(cookie, "service.adb.root", "1", 0);
        }
        return;
    }
    if (!orig_fn) {
        orig_fn = (decltype(orig_fn)) dlsym(RTLD_NEXT, "__system_property_read_callback");
    }
    orig_fn(pi, callback, cookie);
}

// If the user creates ksurc, use ksurc instead of mkshrc
// https://cs.android.com/android/platform/superproject/+/android-latest-release:packages/modules/adb/daemon/shell_service.cpp;l=389-394
// 重写 execle 以注入 ENV：adb shell 启动交互 shell 时优先读取 /data/adb/ksu/.ksurc。
// 注意必须手动展开可变参数，因为无法把 va_list 原样转交真正的 execle。
extern "C" [[gnu::visibility("default"), gnu::used]]
int execle(const char *pathname, const char *arg, ...) {
    std::vector<const char *> argv_list;
    va_list va;
    va_start(va, arg);

    // start dump argv
    if (arg != nullptr) {
        argv_list.push_back(arg);
        const char *next;
        while ((next = va_arg(va, const char*)) != nullptr) {
            argv_list.push_back(next);
        }
    }
    argv_list.push_back(nullptr);

    char *const *old_envp = va_arg(va, char* const*);
    va_end(va);

    // start dump envp
    std::vector<const char *> env_list;
    // 每次调用都检查，允许运行期新增/删除 ksurc；access 失败按“不存在”处理。
    bool ksurc_exists = (access("/data/adb/ksu/.ksurc", F_OK) == 0);
    // 常量字符串无需释放，其生命周期覆盖整个 execve 调用。
    const char *ksu_env_str = "ENV=/data/adb/ksu/.ksurc";

    if (old_envp != nullptr) {
        for (size_t i = 0; old_envp[i] != nullptr; ++i) {
            // skip original ENV in envp
            // even there shouldn't exists in normal android, but let's still check it, avoid newer android/custom rom modify there
            if (ksurc_exists && strncmp(old_envp[i], "ENV=", 4) == 0) {
                continue;
            }
            env_list.push_back(old_envp[i]);
        }
    }

    if (ksurc_exists) {
        // push our ENV in here!!!
        env_list.push_back(ksu_env_str);
    }
    env_list.push_back(nullptr);

    // skip origin execle, because we already completed everything of execle
    // 直接调用 execve 而不是继续查找下一个 execle，避免陷入符号解析循环。
    return execve(pathname,
                  const_cast<char *const *>(argv_list.data()),
                  const_cast<char *const *>(env_list.data()));
}

// skip setcon
// 让 adbd 跳过 SELinux 上下文切换，否则 shell 会被限制在受限域中而无法 root。
extern "C"
[[gnu::visibility("default"), gnu::used]]
int selinux_android_setcon(const char *con) {
    return 0;
}

// 进程启动构造函数：清理可能被恶意继承的 LD_* 环境，并把 KernelSU 的 bin 目录追加到 PATH 末尾。
// constructor(0) 保证在库中其他初始化之前执行，尽量不影响 adbd 原有逻辑。
[[gnu::used, gnu::constructor(0)]]
void Init() {
    // 预加载变量会把任意 so 注入特权进程，必须移除；LD_LIBRARY_PATH 同理。
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    std::string path = getenv("PATH") ?: "";
    // 追加而不是前置，避免覆盖系统自带的 busybox/toybox 等命令。
    if (!path.empty()) {
        path += ":/data/adb/ksu/bin";
    } else {
        path += "/data/adb/ksu/bin";
    }
    setenv("PATH", path.c_str(), 1);
}
