package me.weishu.kernelsu.ui.util

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.screen.modulerepo.RepoModuleArg
import me.weishu.kernelsu.ui.screen.scriptrun.ScriptRunArg
import me.weishu.kernelsu.ui.viewmodel.TemplateViewModel

// 导航参数使用 Parcelable 序列化，保证进程重建/深链时仍能恢复复杂参数。
object FlashItSerializer : BaseParcelableSerializer<FlashIt>(FlashIt::class.java)
// 模块仓库参数在导航时通过此序列化器传递。
object RepoModuleArgSerializer : BaseParcelableSerializer<RepoModuleArg>(RepoModuleArg::class.java)
// 脚本运行参数同样使用 Parcelable 编码。
object ScriptRunArgSerializer : BaseParcelableSerializer<ScriptRunArg>(ScriptRunArg::class.java)
// 模板信息同样需要跨页面传递，复用通用的 Parcelable 序列化器。
object TemplateInfoSerializer : BaseParcelableSerializer<TemplateViewModel.TemplateInfo>(TemplateViewModel.TemplateInfo::class.java)

/**
 * 基于 Android Parcel 的通用 kotlinx.serialization 适配器。
 *
 * 目标类型必须是 Parcelable 并提供静态 CREATOR；序列化结果就是 Parcel 的
 * 字节数组。注意该格式依赖 Android 运行时，不能用于跨平台持久化。
 */
open class BaseParcelableSerializer<T : Parcelable>(
    private val clazz: Class<T>
) : KSerializer<T> {

    // 用字节数组作为底层编码，descriptor 直接复用它。
    private val delegate = ByteArraySerializer()
    override val descriptor = delegate.descriptor

    // 反射获取 Parcelable 的静态 CREATOR，只初始化一次。
    private val creator: Parcelable.Creator<T> by lazy {
        @Suppress("UNCHECKED_CAST")
        clazz.getField("CREATOR").get(null) as Parcelable.Creator<T>
    }

    // 写入 Parcel 后 marshall 成字节；无论成功与否都要 recycle 复用池中的 Parcel。
    override fun serialize(encoder: Encoder, value: T) {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            encoder.encodeSerializableValue(delegate, bytes)
        } finally {
            parcel.recycle()
        }
    }

    // 反序列化前需复位 dataPosition，否则 CREATOR 读不到开头数据。
    override fun deserialize(decoder: Decoder): T {
        val bytes = decoder.decodeSerializableValue(delegate)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
