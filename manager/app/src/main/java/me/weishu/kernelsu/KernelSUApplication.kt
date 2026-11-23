package me.weishu.kernelsu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

/** 全局 Application 单例，供仓库层等无法拿到 Context 的代码访问应用级资源。 */
lateinit var ksuApp: KernelSUApplication

/**
 * KernelSU Manager 的 Application。
 * 实现 [ViewModelStoreOwner] 是为了在 Activity 之外（如启动阶段）创建并复用 ViewModel。
 */
class KernelSUApplication : Application(), ViewModelStoreOwner {

    companion object {
        /**
         * 通过反射调用隐藏 API，控制是否启用预测性返回动画。
         * 副作用：会修改传入的 [appInfo]；调用前需已通过 HiddenApiBypass 放开该方法的限制。
         */
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    /** 进程级 OkHttp 客户端，带 10MB 磁盘缓存并统一附加 UA / 语言头，供仓库层共享连接池。 */
    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    /**
     * 直接启动（Direct Boot）阶段用户尚未解锁，此时 CE 存储不可用。
     * 返回 false 时 [onCreate] 会跳过初始化，等解锁后由系统重新拉起进程。
     */
    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /**
     * 进程启动入口：注册全局单例、恢复预测性返回设置、预加载超级用户列表，
     * 并准备 WebUI 根目录、TMPDIR 与 OkHttp。用户未解锁时直接返回，避免访问加密存储。
     */
    override fun onCreate() {
        super.onCreate()
        ksuApp = this

        // 未解锁的 Direct Boot 阶段不能初始化依赖 CE 存储的组件。
        if (!isUserUnlocked()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = SettingsRepositoryImpl().enablePredictiveBack
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        // 启动阶段即创建 ViewModel，后续 UI 可复用同一实例，避免重复拉取应用列表。
        val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
        superUserViewModel.loadAppList()

        // 模块 WebUI 的本地根目录，需在首次使用前存在。
        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "KernelSU/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    /** Application 级别的 ViewModelStore，进程存活期间一直保留。 */
    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
