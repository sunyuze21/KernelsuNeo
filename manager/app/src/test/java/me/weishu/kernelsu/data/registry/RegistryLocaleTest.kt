package me.weishu.kernelsu.data.registry

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证语言标签到站点 locale 的映射：仅简体中文映射 zh-CN，其余一律 en。 */
class RegistryLocaleTest {

    // 便于在断言消息中带上原始标签，定位是哪一项映射错误。
    private fun site(tag: String) = RegistryLocale.siteLocale(tag)

    // 简体系变体（含大小写、扩展子标签）都必须折叠为 zh-CN。
    @Test
    fun simplifiedChineseMapsToZhCn() {
        for (tag in listOf(
            "zh", "zh-CN", "zh_CN", "zh-Hans", "zh-Hans-CN", "zh-Hans-HK", "zh-Hans-TW", "zh-SG", "zh-MY",
            "ZH-cn", "zh-CN-u-fw-mon-mu-celsius", "zh-Hans-CN-u-nu-hanidec", "zh-u-ca-chinese",
        )) {
            assertEquals(tag, "zh-CN", site(tag))
        }
    }

    // 繁体中文、其他语言及非法标签统一回退到 en，避免出现未翻译的站点页面。
    @Test
    fun traditionalChineseAndOtherLanguagesMapToEn() {
        for (tag in listOf(
            "zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW", "zh-Hant-CN", "zh-HK-u-mu-celsius",
            "en", "en-US", "en-US-u-fw-mon-mu-celsius", "in-ID", "id-ID", "in", "pt-BR", "pt_BR", "ja-JP",
            "und", "", "  ", "x-private", "zhx-CN",
        )) {
            assertEquals(tag, "en", site(tag))
        }
    }
}
