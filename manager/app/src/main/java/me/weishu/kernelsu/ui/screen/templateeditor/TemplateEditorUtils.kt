package me.weishu.kernelsu.ui.screen.templateeditor

import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.TemplateInfo
import me.weishu.kernelsu.toRawFlags
import me.weishu.kernelsu.ui.util.getAppProfileTemplate
import me.weishu.kernelsu.ui.util.setAppProfileTemplate

/**
 * 把持久化的模板模型转换成内核使用的原生 Profile。
 *
 * flags 以枚举序号列表保存，跨版本可能存在越界序号：这里会丢弃无效项，
 * 防止把未知 flag 传给内核；rules 由行列表重新拼成换行分隔字符串。
 */
fun toNativeProfile(templateInfo: TemplateInfo): Natives.Profile {
    val allFlags = Natives.Profile.RootProfileFlag.entries

    // 忽略越界序号，保证旧版本模板在新枚举下仍可加载。
    val mappedFlags = templateInfo.flags.mapNotNull { ordinal ->
        if (ordinal in allFlags.indices) allFlags[ordinal] else null
    }

    return Natives.Profile().copy(
        rootTemplate = templateInfo.id,
        uid = templateInfo.uid,
        gid = templateInfo.gid,
        groups = templateInfo.groups,
        capabilities = templateInfo.capabilities,
        context = templateInfo.context,
        namespace = templateInfo.namespace,
        rules = templateInfo.rules.joinToString("\n").ifBlank { "" },
        flags = mappedFlags.toRawFlags(),
    )
}

/** 保存前的最小校验：id 非空且符合命名规则；其他字段允许为空。 */
fun isTemplateValid(template: TemplateInfo): Boolean {
    if (template.id.isBlank()) {
        return false
    }
    if (!isValidTemplateId(template.id)) {
        return false
    }
    return true
}

/**
 * 检查模板 id 的可用性。
 *
 * 返回 0 表示可用，1 表示已存在，2 表示格式非法。调用方依赖这些常量
 * 区分错误提示，新增状态时需同步更新 UI。
 */
fun idCheck(value: String): Int {
    return if (value.isEmpty()) 0 else if (isTemplateExist(value)) 1 else if (!isValidTemplateId(value)) 2 else 0
}

/**
 * 持久化模板到本地存储。
 *
 * 写入前再次校验 id；新建时额外查重，避免并发创建同名模板。
 * 落盘时强制标记 `local=true`，防止编辑后的远端模板被误当成远端数据。
 */
fun saveTemplate(template: TemplateInfo, isCreation: Boolean = false): Boolean {
    if (!isTemplateValid(template)) {
        return false
    }
    if (isCreation && isTemplateExist(template.id)) {
        return false
    }
    val json = template.toJSON()
    // 用 local 标记区分本地模板与注册表下载的模板。
    json.put("local", true)
    return setAppProfileTemplate(template.id, json.toString())
}

/**
 * 模板 id 允许点分隔的字母段（如 `a.b`），每段必须以字母开头，
 * 后续只允许字母、数字或下划线，避免与文件路径/命令参数冲突。
 */
fun isValidTemplateId(id: String): Boolean {
    return Regex("""^([A-Za-z][A-Za-z\d_]*\.)*[A-Za-z][A-Za-z\d_]*$""").matches(id)
}

/** 通过读取模板内容是否为空来判断 id 是否已被占用。 */
fun isTemplateExist(id: String): Boolean {
    return getAppProfileTemplate(id).isNotBlank()
}
