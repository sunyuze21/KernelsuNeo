package me.weishu.kernelsu.ui.component

/**
 * 警告等级，用于统一区分“阻断性错误”和“仅提示”两类信息。
 * Error 通常需要用户处理后才能继续（例如关键校验失败），Notice 只是温和提醒。
 */
enum class WarningLevel { Error, Notice }
