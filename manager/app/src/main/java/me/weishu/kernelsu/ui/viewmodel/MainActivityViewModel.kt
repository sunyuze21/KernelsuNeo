package me.weishu.kernelsu.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.theme.ThemeController

/**
 * 主 Activity 的 ViewModel：把设置项变化映射为 UI 状态。
 *
 * 通过监听 SharedPreferences 变更来刷新 StateFlow，而不是让每个页面自行读取配置，
 * 这样主题、缩放、底栏等全局开关能立即同步且只需维护一份状态。
 */
class MainActivityViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // 监听与读取共用同一个 SharedPreferences 实例，避免多实例缓存不一致
    private val prefs = ksuApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // 设置仓库提供类型化读取，内部同样基于该偏好文件
    private val settingRepo: SettingsRepository = SettingsRepositoryImpl()
    // 标签页状态单独封装，便于测试页码夹取逻辑
    private val mainPageState = MainPageState(savedStateHandle)
    // 只关心 UI 相关 key，避免无关偏好写入引起整棵界面重组
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in observedKeys) {
            _uiState.value = readUiState()
        }
    }

    // 初始值在构造时同步读取，保证首帧就能拿到正确主题，避免闪白
    private val _uiState = MutableStateFlow(readUiState())
    // 只读状态流，主题与界面设置变化的唯一数据源
    val uiState: StateFlow<MainActivityUiState> = _uiState.asStateFlow()
    // 当前选中的标签页由 SavedStateHandle 持有，进程重建后可恢复
    val selectedMainPage: StateFlow<Int> = mainPageState.selectedPage

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * ViewModel 销毁时必须注销监听，否则会泄漏 Activity 级别的监听器。
     */
    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 记录用户选中的主标签页，页码会被夹取到合法范围。
     */
    fun setSelectedMainPage(page: Int) {
        mainPageState.updateSelectedPage(page)
    }

    /**
     * 从设置仓库重新读取一份完整 UI 状态。
     *
     * 每次变更都整体重建而非局部更新，保证各字段之间的一致性（例如 Miuix 的 Monet 联动）。
     */
    private fun readUiState(): MainActivityUiState {
        return MainActivityUiState(
            appSettings = ThemeController.getAppSettings(),
            pageScale = settingRepo.pageScale,
            enableBlur = settingRepo.enableBlur,
            enableFloatingBottomBar = settingRepo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = settingRepo.enableFloatingBottomBarBlur,
            enableNavigationBadge = settingRepo.enableNavigationBadge,
            enableSwipeDismiss = settingRepo.enableSwipeDismiss,
            pagerInterceptionMode = settingRepo.pagerInterceptionMode,
            moduleDescriptionMaxLines = settingRepo.moduleDescriptionMaxLines,
            uiMode = UiMode.fromValue(settingRepo.uiMode),
        )
    }

    private companion object {
        // 需要监听的偏好 key 白名单，新增界面设置时须同步添加，否则改动不会即时生效
        val observedKeys = setOf(
            "color_mode",
            "key_color",
            "color_style",
            "color_spec",
            "page_scale",
            "module_description_max_lines",
            "enable_blur",
            "enable_floating_bottom_bar",
            "enable_floating_bottom_bar_blur",
            "enable_navigation_badge",
            "enable_swipe_dismiss",
            "pager_interception_mode",
            "ui_mode",
        )
    }
}

// SavedStateHandle 中的键名，改动会导致升级后丢失上次选中的标签页
private const val SELECTED_MAIN_PAGE_KEY = "selected_main_page"

/**
 * 主标签页索引的持有者。
 *
 * 使用 SavedStateHandle 而非普通字段，使页码能随进程死亡恢复，并与 ViewModel 生命周期解耦。
 */
private class MainPageState(
    private val savedStateHandle: SavedStateHandle,
) {
    val selectedPage: StateFlow<Int> = savedStateHandle.getStateFlow(SELECTED_MAIN_PAGE_KEY, 0)

    /**
     * 写入页码前夹取范围，防止外部传入越界值导致 Pager 崩溃。
     */
    fun updateSelectedPage(page: Int) {
        savedStateHandle[SELECTED_MAIN_PAGE_KEY] = MainPagerConfig.coercePage(page)
    }
}

/**
 * 主 Pager 的页数约定，供导航栏与 ViewModel 共用，避免两处硬编码不一致。
 */
object MainPagerConfig {
    // 主界面标签页数量，与 MainActivity 中 Pager 的 when 分支保持一致
    const val PAGE_COUNT = 4
    // 最后一个合法页码，用于夹取越界输入
    const val LAST_PAGE_INDEX = PAGE_COUNT - 1

    /** 把任意页码夹取到 [0, LAST_PAGE_INDEX]，越界时返回最近的合法页。 */
    fun coercePage(page: Int): Int = page.coerceIn(0, LAST_PAGE_INDEX)
}
