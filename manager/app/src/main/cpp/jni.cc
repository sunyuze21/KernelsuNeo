#include <jni.h>

#include <sys/prctl.h>
#include <linux/capability.h>
#include <pwd.h>
#include <unistd.h>
#include <sys/wait.h>

#include <android/log.h>
#include <cstring>

#include "ksu.h"
#include "logging.h"

// 返回内核版本号；新接口失败（返回 0）时回退到 legacy prctl 通道。
extern "C"
JNIEXPORT jint JNICALL
Java_me_weishu_kernelsu_Natives_getVersion(JNIEnv *env, jobject) {
    int version = get_version();
    if (version > 0) {
        return version;
    }
    // try legacy method as fallback
    return legacy_get_info().first;
}

// 返回内核 UAPI 版本，供 Kotlin 侧判断特性是否可用。
extern "C"
JNIEXPORT jint JNICALL
Java_me_weishu_kernelsu_Natives_getKernelUAPIVersion(JNIEnv *env, jobject) {
    return get_kernel_uapi_version();
}

// 返回 Manager 编译期 UAPI 版本，与内核版本对比可发现不匹配。
extern "C"
JNIEXPORT jint JNICALL
Java_me_weishu_kernelsu_Natives_getManagerUAPIVersion(JNIEnv *env, jobject) {
    return get_manager_uapi_version();
}

// 统计 allowlist 条目数：只请求计数（count=0），失败时返回 0 而非抛异常。
extern "C"
JNIEXPORT jint JNICALL
Java_me_weishu_kernelsu_Natives_getSuperuserCount(JNIEnv *env, jobject) {
    // count=0 表示不拷贝条目，仅由内核回填 total_count。
    struct ksu_new_get_allow_list_cmd cmd = {
        .count = 0
    };
    bool result = get_allow_list(&cmd);
    return result ? cmd.total_count : 0;
}

// 查询内核安全模式；该状态由内核在启动异常时设置。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isSafeMode(JNIEnv *env, jclass clazz) {
    return is_safe_mode();
}

// 查询是否为 LKM 模式，用于 UI 展示安装方式。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isLkmMode(JNIEnv *env, jclass clazz) {
    return is_lkm_mode();
}

// 查询 LKM 是否随 Manager 捆绑安装。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isLkmBundled(JNIEnv *env, jclass clazz) {
    return is_lkm_bundled();
}

// 查询是否为 late-load 模式。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isLateLoadMode(JNIEnv *env, jclass clazz) {
    return is_late_load_mode();
}

// 查询当前进程是否被内核识别为 Manager。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isManager(JNIEnv *env, jclass clazz) {
    return is_manager();
}

// 查询是否为 PR 构建。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isPrBuild(JNIEnv *env, jclass clazz) {
    return is_pr_build();
}

// 把 int 数组批量填入 Kotlin 的 MutableList<Int>；调用方需保证 list 非空且可修改。
// 注意每次循环都会创建 Integer 局部引用，条目数受内核上限约束，不会撑爆局部引用表。
static void fillIntArray(JNIEnv *env, jobject list, int *data, int count) {
    auto cls = env->GetObjectClass(list);
    auto add = env->GetMethodID(cls, "add", "(Ljava/lang/Object;)Z");
    auto integerCls = env->FindClass("java/lang/Integer");
    auto constructor = env->GetMethodID(integerCls, "<init>", "(I)V");
    for (int i = 0; i < count; ++i) {
        auto integer = env->NewObject(integerCls, constructor, data[i]);
        env->CallBooleanMethod(list, add, integer);
    }
}

// 向 Kotlin 的 MutableList<Int> 追加一个 int；用于能力位逐个展开。
static void addIntToList(JNIEnv *env, jobject list, int ele) {
    auto cls = env->GetObjectClass(list);
    auto add = env->GetMethodID(cls, "add", "(Ljava/lang/Object;)Z");
    auto integerCls = env->FindClass("java/lang/Integer");
    auto constructor = env->GetMethodID(integerCls, "<init>", "(I)V");
    auto integer = env->NewObject(integerCls, constructor, ele);
    env->CallBooleanMethod(list, add, integer);
}

// 把 Kotlin 的 List<Int> 能力列表压缩成 64 位掩码；越界能力位直接丢弃。
// 掩码布局需与内核 capabilities.effective 的位定义保持一致。
static uint64_t capListToBits(JNIEnv *env, jobject list) {
    auto cls = env->GetObjectClass(list);
    auto get = env->GetMethodID(cls, "get", "(I)Ljava/lang/Object;");
    auto size = env->GetMethodID(cls, "size", "()I");
    auto listSize = env->CallIntMethod(list, size);
    auto integerCls = env->FindClass("java/lang/Integer");
    auto intValue = env->GetMethodID(integerCls, "intValue", "()I");
    uint64_t result = 0;
    for (int i = 0; i < listSize; ++i) {
        auto integer = env->CallObjectMethod(list, get, i);
        int data = env->CallIntMethod(integer, intValue);

        // 过滤非法能力号，避免 1ULL << data 触发未定义行为。
        if (cap_valid(data)) {
            result |= (1ULL << data);
        }
    }

    return result;
}

// 读取 Kotlin List 的 size，用于校验组数量是否超过内核上限。
static int getListSize(JNIEnv *env, jobject list) {
    auto cls = env->GetObjectClass(list);
    auto size = env->GetMethodID(cls, "size", "()I");
    return env->CallIntMethod(list, size);
}

// 把 Kotlin List<Int> 的内容写入预分配的 int 数组；count 由调用方保证不超过数组容量。
static void fillArrayWithList(JNIEnv *env, jobject list, int *data, int count) {
    auto cls = env->GetObjectClass(list);
    auto get = env->GetMethodID(cls, "get", "(I)Ljava/lang/Object;");
    auto integerCls = env->FindClass("java/lang/Integer");
    auto intValue = env->GetMethodID(integerCls, "intValue", "()I");
    for (int i = 0; i < count; ++i) {
        auto integer = env->CallObjectMethod(list, get, i);
        data[i] = env->CallIntMethod(integer, intValue);
    }
}

// 读取指定包名/UID 的 app profile，并填充为 Kotlin 的 Natives$Profile 对象。
// 未命中时返回“默认策略”对象（禁止 root、非 root 使用默认配置），不返回 null。
extern "C"
JNIEXPORT jobject JNICALL
Java_me_weishu_kernelsu_Natives_getAppProfile(JNIEnv *env, jobject, jstring pkg, jint uid) {
    // 超出 uapi 固定数组长度会导致后续 strcpy 截断/越界，直接拒绝。
    if (env->GetStringLength(pkg) > KSU_MAX_PACKAGE_NAME) {
        return nullptr;
    }

    // 内核结构体中的 key 是定长数组，必须先清零再拷贝，保证尾部 '\0'。
    p_key_t key = {};
    auto cpkg = env->GetStringUTFChars(pkg, nullptr);
    strcpy(key, cpkg);
    // GetStringUTFChars 可能返回副本，必须配对释放以免泄漏。
    env->ReleaseStringUTFChars(pkg, cpkg);

    app_profile profile = {};
    profile.version = KSU_APP_PROFILE_VER;

    strcpy(profile.key, key);
    profile.curr_uid = uid;

    // 非 0 表示没有持久化 profile，内核会保留上面的默认字段。
    bool useDefaultProfile = get_app_profile(&profile) != 0;

    auto cls = env->FindClass("me/weishu/kernelsu/Natives$Profile");
    auto constructor = env->GetMethodID(cls, "<init>", "()V");
    auto obj = env->NewObject(cls, constructor);
    // 字段签名必须与 Kotlin 声明严格一致，否则 GetFieldID 返回 null 并在调用时崩溃。
    auto keyField = env->GetFieldID(cls, "name", "Ljava/lang/String;");
    auto currentUidField = env->GetFieldID(cls, "currentUid", "I");
    auto allowSuField = env->GetFieldID(cls, "allowSu", "Z");

    auto rootUseDefaultField = env->GetFieldID(cls, "rootUseDefault", "Z");
    auto rootTemplateField = env->GetFieldID(cls, "rootTemplate", "Ljava/lang/String;");

    auto uidField = env->GetFieldID(cls, "uid", "I");
    auto gidField = env->GetFieldID(cls, "gid", "I");
    auto groupsField = env->GetFieldID(cls, "groups", "Ljava/util/List;");
    auto capabilitiesField = env->GetFieldID(cls, "capabilities", "Ljava/util/List;");
    auto domainField = env->GetFieldID(cls, "context", "Ljava/lang/String;");
    auto namespacesField = env->GetFieldID(cls, "namespace", "I");
    jfieldID flagsField = env->GetFieldID(cls, "flags", "J");

    auto nonRootUseDefaultField = env->GetFieldID(cls, "nonRootUseDefault", "Z");
    auto umountModulesField = env->GetFieldID(cls, "umountModules", "Z");

    env->SetObjectField(obj, keyField, env->NewStringUTF(profile.key));
    env->SetIntField(obj, currentUidField, profile.curr_uid);

    if (useDefaultProfile) {
        // no profile found, so just use default profile:
        // don't allow root and use default profile!
        LOGD("use default profile for: %s, %d", key, uid);

        // allow_su = false
        // non root use default = true
        env->SetBooleanField(obj, allowSuField, false);
        env->SetBooleanField(obj, nonRootUseDefaultField, true);

        return obj;
    }

    auto allowSu = profile.allow_su;

    if (allowSu) {
        env->SetBooleanField(obj, rootUseDefaultField, (jboolean) profile.rp_config.use_default);
        // 空模板名表示不使用模板，避免创建空字符串误导 UI。
        if (strlen(profile.rp_config.template_name) > 0) {
            env->SetObjectField(obj, rootTemplateField,
                    env->NewStringUTF(profile.rp_config.template_name));
        }

        env->SetIntField(obj, uidField, profile.rp_config.profile.uid);
        env->SetIntField(obj, gidField, profile.rp_config.profile.gid);

        jobject groupList = env->GetObjectField(obj, groupsField);
        int groupCount = profile.rp_config.profile.groups_count;
        // 防御内核返回异常值：截断到 uapi 固定数组容量，避免越界读取。
        if (groupCount > KSU_MAX_GROUPS) {
            LOGD("kernel group count too large: %d???", groupCount);
            groupCount = KSU_MAX_GROUPS;
        }
        fillIntArray(env, groupList, profile.rp_config.profile.groups, groupCount);

        jobject capList = env->GetObjectField(obj, capabilitiesField);
        for (int i = 0; i <= CAP_LAST_CAP; i++) {
            if (profile.rp_config.profile.capabilities.effective & (1ULL << i)) {
                addIntToList(env, capList, i);
            }
        }

        env->SetObjectField(obj, domainField,
                env->NewStringUTF(profile.rp_config.profile.selinux_domain));
        env->SetIntField(obj, namespacesField, profile.rp_config.profile.namespaces);
        env->SetBooleanField(obj, allowSuField, profile.allow_su);
        env->SetLongField(obj, flagsField, (jlong) profile.rp_config.profile.flags);
    } else {
        env->SetBooleanField(obj, nonRootUseDefaultField,
                (jboolean) profile.nrp_config.use_default);
        env->SetBooleanField(obj, umountModulesField, profile.nrp_config.profile.umount_modules);
    }

    return obj;
}

// 把 Kotlin 的 Natives$Profile 写回内核；字段校验失败或列表越界时返回 false。
// 所有字符串都先转成临时 char* 再拷贝到定长 UAPI 结构体，拷贝后立即释放 JNI 引用。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_setAppProfile(JNIEnv *env, jobject clazz, jobject profile) {
    auto cls = env->FindClass("me/weishu/kernelsu/Natives$Profile");

    auto keyField = env->GetFieldID(cls, "name", "Ljava/lang/String;");
    auto currentUidField = env->GetFieldID(cls, "currentUid", "I");
    auto allowSuField = env->GetFieldID(cls, "allowSu", "Z");

    auto rootUseDefaultField = env->GetFieldID(cls, "rootUseDefault", "Z");
    auto rootTemplateField = env->GetFieldID(cls, "rootTemplate", "Ljava/lang/String;");

    auto uidField = env->GetFieldID(cls, "uid", "I");
    auto gidField = env->GetFieldID(cls, "gid", "I");
    auto groupsField = env->GetFieldID(cls, "groups", "Ljava/util/List;");
    auto capabilitiesField = env->GetFieldID(cls, "capabilities", "Ljava/util/List;");
    auto domainField = env->GetFieldID(cls, "context", "Ljava/lang/String;");
    auto namespacesField = env->GetFieldID(cls, "namespace", "I");
    jfieldID flagsField = env->GetFieldID(cls, "flags", "J");

    auto nonRootUseDefaultField = env->GetFieldID(cls, "nonRootUseDefault", "Z");
    auto umountModulesField = env->GetFieldID(cls, "umountModules", "Z");

    auto key = env->GetObjectField(profile, keyField);
    // 包名是内核索引键，缺失或超长都无法安全写入定长数组。
    if (!key) {
        return false;
    }
    if (env->GetStringLength((jstring) key) > KSU_MAX_PACKAGE_NAME) {
        return false;
    }

    auto cpkg = env->GetStringUTFChars((jstring) key, nullptr);
    p_key_t p_key = {};
    strcpy(p_key, cpkg);
    // 释放与 GetStringUTFChars 配对，避免 JNI 字符串副本泄漏。
    env->ReleaseStringUTFChars((jstring) key, cpkg);

    auto currentUid = env->GetIntField(profile, currentUidField);

    auto uid = env->GetIntField(profile, uidField);
    auto gid = env->GetIntField(profile, gidField);
    auto groups = env->GetObjectField(profile, groupsField);
    auto capabilities = env->GetObjectField(profile, capabilitiesField);
    auto domain = env->GetObjectField(profile, domainField);
    auto allowSu = env->GetBooleanField(profile, allowSuField);
    auto umountModules = env->GetBooleanField(profile, umountModulesField);

    // 内核结构体必须整体零初始化，未使用分支的字段会随结构体一起下发。
    app_profile p = {};
    p.version = KSU_APP_PROFILE_VER;

    strcpy(p.key, p_key);
    p.allow_su = allowSu;
    p.curr_uid = currentUid;

    if (allowSu) {
        p.rp_config.use_default = env->GetBooleanField(profile, rootUseDefaultField);
        auto templateName = env->GetObjectField(profile, rootTemplateField);
        if (templateName) {
            auto ctemplateName = env->GetStringUTFChars((jstring) templateName, nullptr);
            strcpy(p.rp_config.template_name, ctemplateName);
            env->ReleaseStringUTFChars((jstring) templateName, ctemplateName);
        }

        p.rp_config.profile.uid = uid;
        p.rp_config.profile.gid = gid;

        int groups_count = getListSize(env, groups);
        // 内核数组只有 KSU_MAX_GROUPS 项，超限直接拒绝而不是静默截断。
        if (groups_count > KSU_MAX_GROUPS) {
            LOGD("groups count too large: %d", groups_count);
            return false;
        }
        p.rp_config.profile.groups_count = groups_count;
        fillArrayWithList(env, groups, p.rp_config.profile.groups, groups_count);

        p.rp_config.profile.capabilities.effective = capListToBits(env, capabilities);

        auto cdomain = env->GetStringUTFChars((jstring) domain, nullptr);
        strcpy(p.rp_config.profile.selinux_domain, cdomain);
        env->ReleaseStringUTFChars((jstring) domain, cdomain);

        p.rp_config.profile.namespaces = env->GetIntField(profile, namespacesField);

        p.rp_config.profile.flags = env->GetLongField(profile, flagsField);
    } else {
        p.nrp_config.use_default = env->GetBooleanField(profile, nonRootUseDefaultField);
        p.nrp_config.profile.umount_modules = umountModules;
    }

    return set_app_profile(&p);
}
// 查询指定 UID 是否应卸载模块，供非 root 应用的隐藏策略使用。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_uidShouldUmount(JNIEnv *env, jobject thiz, jint uid) {
    return uid_should_umount(uid);
}
// 查询 Su compat 开关。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isSuEnabled(JNIEnv *env, jobject thiz) {
    return is_su_enabled();
}
// 设置 Su compat 开关，返回内核是否接受。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_setSuEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_su_enabled(enabled);
}

// 查询内核 umount 开关。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isKernelUmountEnabled(JNIEnv *env, jobject thiz) {
    return is_kernel_umount_enabled();
}

// 设置内核 umount 开关。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_setKernelUmountEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_kernel_umount_enabled(enabled);
}

// 查询 SELinux 隐藏开关。
extern "C"
JNIEXPORT jboolean JNICALL
Java_me_weishu_kernelsu_Natives_isSelinuxHideEnabled(JNIEnv *env, jobject thiz) {
    return is_selinux_hide_enabled();
}

// 设置 SELinux 隐藏；返回 0 成功，负值为 errno，Kotlin 侧据此提示失败原因。
extern "C"
JNIEXPORT jint JNICALL
Java_me_weishu_kernelsu_Natives_setSelinuxHideEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    return set_selinux_hide_enabled(enabled);
}

// 根据 UID 返回用户名；查不到或名为空时返回 null，交给 Kotlin 侧显示数字 UID。
extern "C"
JNIEXPORT jstring JNICALL
Java_me_weishu_kernelsu_Natives_getUserName(JNIEnv *env, jobject thiz, jint uid) {
    // getpwuid 返回静态缓冲区，多线程查询需外部加锁；这里只在 UI 线程/短生命周期内使用。
    struct passwd *pw = getpwuid((uid_t) uid);
    if (pw && pw->pw_name && pw->pw_name[0] != '\0') {
        return env->NewStringUTF(pw->pw_name);
    }
    return nullptr;
}

// 以 root 身份启动 ksud late-load --magica：先 fork 出中间子进程并等待，
// 再二次 fork 让真正的执行进程脱离调用者，避免 Manager 进程被等待或收到 SIGCHLD。
int fork_dont_care_and_exec_ksud(const char *path, const char *pkg) {
    int pid = fork();
    if (pid < 0) {
        PLOGE("fork");
        return pid;
    } else if (pid > 0) {
        // 父进程只等待第一层子进程，确认它已成功派生即可返回，不等待实际 ksud。
        int status = 0;
        if (TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) < 0) {
            PLOGE("waitpid");
            return -1;
        }
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
            LOGE("magica bootstrap child failed, status=%d", status);
        }
        return pid;
    }

    // 子进程继承 Manager 的普通权限，必须提权后才能执行受保护的 ksud。
    if (setuid(0) != 0) {
        PLOGE("setuid");
        _exit(1);
    }

    pid = fork();
    if (pid < 0) {
        PLOGE("fork 2");
        _exit(1);
    } else if (pid > 0) {
        // 中间进程立即退出，真正的 ksud 由孙进程执行并被 init 收养。
        _exit(0);
    }

    execl(path, "ksud", "late-load", "--magica", "5555", "--package-name", pkg, nullptr);
    // execl 只在失败时返回；用 _exit 而不是 exit，避免运行父子进程共享的 atexit 处理器。
    PLOGE("exec magica");
    _exit(1);
}

// AppZygote 预加载回调：在 zygote 派生的特殊进程中启动 late-load 流程。
// 字符串指针在 fork 后由子进程各自持有，父进程释放不会影响已复制的地址空间。
extern "C"
JNIEXPORT void JNICALL
Java_me_weishu_kernelsu_magica_AppZygotePreload_forkDontCareAndExecKsud(JNIEnv *env, jclass clazz,
                                                                        jstring ksud_path, jstring pkg_name) {
    auto path = env->GetStringUTFChars(ksud_path, nullptr);
    auto pkg = env->GetStringUTFChars(pkg_name, nullptr);
    LOGD("executing magica %s (pkg %s)", path, pkg);
    fork_dont_care_and_exec_ksud(path, pkg);
    // fork 返回后必须成对释放；子进程已 exec 或退出，不会再用到这两个副本。
    env->ReleaseStringUTFChars(ksud_path, path);
    env->ReleaseStringUTFChars(pkg_name, pkg);
}
