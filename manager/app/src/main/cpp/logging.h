#pragma once

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <string>

#ifndef LOG_TAG
# define LOG_TAG "KernelSU"
#endif

// 统一日志宏：Release 构建下 LOGD/LOGV 被编译为空，避免刷日志影响性能。
#ifndef NDEBUG
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGV(...)  __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...)
#define LOGV(...)
#endif
// 以下级别在 Release 构建中同样保留，用于记录失败与致命错误。
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGF(...)  __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, __VA_ARGS__)
// 记录失败的系统调用：自动追加 errno 与 strerror，调用后不应再覆盖 errno。
#define PLOGE(fmt, args...) LOGE(fmt " failed with %d: %s", ##args, errno, strerror(errno))
