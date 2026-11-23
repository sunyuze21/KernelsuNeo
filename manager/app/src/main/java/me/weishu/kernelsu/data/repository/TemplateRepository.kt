package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.TemplateInfo

/** root profile 模板的读写入口；模板通过 ksud 持久化，远端模板会缓存到本地。 */
interface TemplateRepository {
    /** 读取模板列表；[sync] 为 true 时强制从 kernelsu.org 重新拉取。 */
    suspend fun getTemplates(sync: Boolean): Result<List<TemplateInfo>>
    /** 导入 JSON（数组或单个对象）中的模板，非法项被跳过。 */
    suspend fun importTemplates(jsonString: String): Result<Unit>
    /** 导出本地模板为 JSON 数组字符串；没有模板时以 failure 返回。 */
    suspend fun exportTemplates(): Result<String>
    /** 按 id 读取单个模板；不存在时以 failure 返回。 */
    suspend fun getTemplate(id: String): Result<TemplateInfo>
}
