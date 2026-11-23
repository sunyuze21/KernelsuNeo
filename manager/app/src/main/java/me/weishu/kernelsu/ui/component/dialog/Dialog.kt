package me.weishu.kernelsu.ui.component.dialog

import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import kotlin.coroutines.resume

// 对话框相关日志统一 tag，便于过滤与排查生命周期问题
private const val TAG = "DialogComponent"

/**
 * 确认对话框的可视内容。实现为 Parcelable 以便 rememberSaveable 跨进程/配置变更恢复，
 * 避免旋转屏幕后对话框内容丢失。
 */
interface ConfirmDialogVisuals : Parcelable {
    val title: String
    val content: String?
    val isMarkdown: Boolean
    val isHtml: Boolean
    val confirm: String?
    val dismiss: String?

    /** Registry-hosted content: rendered with JavaScript off and network locked to the registry. */
    val isRestricted: Boolean get() = false
}

/**
 * [ConfirmDialogVisuals] 的唯一实现。默认所有字段为空，
 * [Empty] 作为未配置内容的哨兵，show() 会拒绝空内容弹窗。
 */
@Parcelize
private data class ConfirmDialogVisualsImpl(
    override val title: String,
    override val content: String?,
    override val isMarkdown: Boolean,
    override val isHtml: Boolean,
    override val confirm: String?,
    override val dismiss: String?,
    override val isRestricted: Boolean = false,
) : ConfirmDialogVisuals {
    // 空内容哨兵等常量集中在此，避免散落的魔法实例
    companion object {
        // 空内容哨兵，用于区分“尚未 showConfirm”与“内容为空”
        val Empty: ConfirmDialogVisuals = ConfirmDialogVisualsImpl("", "", isMarkdown = false, isHtml = false, confirm = null, dismiss = null)
    }
}

/**
 * 对话框句柄的通用契约。句柄不持有界面，界面由 remember* 在组合中渲染，
 * 因此句柄可在 ViewModel/协程中安全持有并异步控制显隐。
 */
interface DialogHandle {
    val isShown: Boolean
    val dialogType: String
    // 显示由句柄内部协程切换状态，调用方无需关心线程
    fun show()
    // 隐藏同样在协程中执行，保证与组合生命周期一致
    fun hide()
}

/**
 * 加载对话框句柄。[withLoading] 保证 block 执行期间显示、结束后必定隐藏，
 * 异常也会走 finally 关闭，避免遮罩残留。
 */
interface LoadingDialogHandle : DialogHandle {
    // 挂起执行 block，期间展示加载遮罩
    suspend fun <R> withLoading(block: suspend () -> R): R
    // 仅显示遮罩，不包裹具体任务
    fun showLoading()
}

/**
 * 确认结果。用密封接口而非 Boolean，后续可扩展“第三方选项”等状态。
 */
sealed interface ConfirmResult {
    // 用户点击确认
    object Confirmed : ConfirmResult
    // 用户取消或关闭对话框
    object Canceled : ConfirmResult
}

/**
 * 确认对话框句柄：既可主动 [showConfirm] 展示，也可 [awaitConfirm] 挂起等待结果。
 * 两种用法的结果都会通过同一个 Channel 汇聚，回调与挂起可并存。
 */
interface ConfirmDialogHandle : DialogHandle {
    val visuals: ConfirmDialogVisuals

    // 更新内容并显示对话框，不等待结果
    fun showConfirm(
        title: String,
        content: String? = null,
        markdown: Boolean = false,
        html: Boolean = false,
        confirm: String? = null,
        dismiss: String? = null,
        restricted: Boolean = false,
    )

    // 显示对话框并挂起，直到用户确认或取消
    suspend fun awaitConfirm(
        title: String,
        content: String? = null,
        markdown: Boolean = false,
        html: Boolean = false,
        confirm: String? = null,
        dismiss: String? = null,
        restricted: Boolean = false,
    ): ConfirmResult
}

/**
 * 句柄基类：统一维护可见状态与协程作用域。
 * show/hide 均切到 [coroutineScope] 中修改状态，保证线程安全且与组合生命周期一致。
 */
private abstract class DialogHandleBase(
    val visible: MutableState<Boolean>,
    val coroutineScope: CoroutineScope
) : DialogHandle {
    override val isShown: Boolean
        get() = visible.value

    // 简化实现中显示即把可见状态置 true
    override fun show() {
        coroutineScope.launch {
            visible.value = true
        }
    }

    // 统一在协程中修改状态，避免与组合线程竞争
    final override fun hide() {
        coroutineScope.launch {
            visible.value = false
        }
    }

    // 便于日志中直接看到对话框类型
    override fun toString(): String {
        return dialogType
    }
}

/**
 * 加载对话框句柄实现。withLoading 用 async 包裹以便在 finally 中恢复可见性，
 * 且不会吞掉原异常。
 */
private class LoadingDialogHandleImpl(
    visible: MutableState<Boolean>,
    coroutineScope: CoroutineScope
) : LoadingDialogHandle, DialogHandleBase(visible, coroutineScope) {
    // 用 async 包住 block，finally 中一定隐藏遮罩，同时保留原异常
    override suspend fun <R> withLoading(block: suspend () -> R): R {
        return coroutineScope.async {
            try {
                visible.value = true
                block()
            } finally {
                // 无论成功或异常都必须收起遮罩
                visible.value = false
            }
        }.await()
    }

    // 语义别名，便于调用处更直观
    override fun showLoading() {
        show()
    }

    override val dialogType: String get() = "LoadingDialog"
}

// 可空回调别名，统一 confirm/dismiss 的签名
typealias NullableCallback = (() -> Unit)?

/**
 * 确认/取消回调集合。用 provider 形式延迟取值，确保回调闭包内读取的是最新状态，
 * 避免 remember 捕获旧值。
 */
interface ConfirmCallback {

    // 确认回调，为空表示调用方不关心确认
    val onConfirm: NullableCallback

    // 取消/关闭回调
    val onDismiss: NullableCallback

    // 两者都为空时视为“无回调模式”，取消挂起时需要主动关闭对话框
    val isEmpty: Boolean get() = onConfirm == null && onDismiss == null

    // 工厂成员集中在此，便于外部以 ConfirmCallback(...) 形式创建
    companion object {
        // 工厂：用 provider 延迟取值，保证读到的始终是最新回调
        operator fun invoke(
            onConfirmProvider: () -> NullableCallback,
            onDismissProvider: () -> NullableCallback
        ): ConfirmCallback {
            return object : ConfirmCallback {
                override val onConfirm: NullableCallback
                    get() = onConfirmProvider()
                override val onDismiss: NullableCallback
                    get() = onDismissProvider()
            }
        }
    }
}

/**
 * 确认对话框实现。界面由外部 Composable 渲染，本类只负责：
 * 维护 visuals、把按钮点击经 resultFlow 汇聚、驱动回调或恢复挂起协程。
 *
 * [resultFlow] 不能在此关闭，需由外部 remember 生命周期管理，否则重建后失效。
 */
private class ConfirmDialogHandleImpl(
    visible: MutableState<Boolean>,
    coroutineScope: CoroutineScope,
    callback: ConfirmCallback,
    initialVisuals: ConfirmDialogVisuals = ConfirmDialogVisualsImpl.Empty,
    private val resultFlow: ReceiveChannel<ConfirmResult>
) : ConfirmDialogHandle, DialogHandleBase(visible, coroutineScope) {
    // 可视内容随每次 showConfirm 更新，用 State 驱动重组
    private val visualsState = mutableStateOf(initialVisuals)
    override val visuals: ConfirmDialogVisuals get() = visualsState.value

    /**
     * 结果收集器：把 ConfirmResult 转成对应回调。
     * 仅在需要回调的模式下使用，挂起等待由 init 中的 continuation 处理。
     */
    private class ResultCollector(
        private val callback: ConfirmCallback
    ) : FlowCollector<ConfirmResult> {
        // 结果到回调的单一分派点，便于统一加日志与后续扩展
        fun handleResult(result: ConfirmResult) {
            Log.d(TAG, "handleResult: ${result.javaClass.simpleName}")
            when (result) {
                ConfirmResult.Confirmed -> onConfirm()
                ConfirmResult.Canceled -> onDismiss()
            }
        }

        // 委托给 ConfirmCallback，返回值已做空安全处理
        fun onConfirm() {
            callback.onConfirm?.invoke()
        }

        // 取消回调同样允许为空
        fun onDismiss() {
            callback.onDismiss?.invoke()
        }

        // Flow 收集入口，转交 handleResult 处理
        override suspend fun emit(value: ConfirmResult) {
            handleResult(value)
        }
    }

    private val resultCollector = ResultCollector(callback)

    // 挂起等待中的 continuation，同一时刻只允许一个 awaitConfirm
    private var awaitContinuation: CancellableContinuation<ConfirmResult>? = null

    private val isCallbackEmpty = callback.isEmpty

    init {
        // 常驻收集结果：先恢复挂起协程，再关闭对话框，最后触发回调
        coroutineScope.launch {
            resultFlow
                .consumeAsFlow()
                .onEach { result ->
                    awaitContinuation?.let {
                        awaitContinuation = null
                        if (it.isActive) {
                            it.resume(result)
                        }
                    }
                }
                .onEach { hide() }
                .collect(resultCollector)
        }
    }

    /**
     * 挂起等待一次结果。无回调模式下，若等待被取消需顺手关闭对话框，
     * 否则界面会一直停留在遮罩状态。
     */
    private suspend fun awaitResult(): ConfirmResult {
        return suspendCancellableCoroutine {
            awaitContinuation = it.apply {
                if (isCallbackEmpty) {
                    invokeOnCancellation {
                        visible.value = false
                    }
                }
            }
        }
    }

    // 更新可视内容；showConfirm 与 awaitConfirm 都会先走这里再显示
    fun updateVisuals(visuals: ConfirmDialogVisuals) {
        visualsState.value = visuals
    }

    // 未设置内容时不允许直接 show，避免弹出空白对话框
    override fun show() {
        if (visuals !== ConfirmDialogVisualsImpl.Empty) {
            super.show()
        } else {
            throw UnsupportedOperationException("can't show confirm dialog with the Empty visuals")
        }
    }

    // 只展示不等待结果，适合配合回调使用
    override fun showConfirm(
        title: String,
        content: String?,
        markdown: Boolean,
        html: Boolean,
        confirm: String?,
        dismiss: String?,
        restricted: Boolean,
    ) {
        coroutineScope.launch {
            // 先更新内容再显示，保证弹窗出现时内容已就绪
            updateVisuals(ConfirmDialogVisualsImpl(title, content, markdown, html, confirm, dismiss, restricted))
            show()
        }
    }

    // 展示并挂起等待结果，内部与回调共用同一个 resultChannel
    override suspend fun awaitConfirm(
        title: String,
        content: String?,
        markdown: Boolean,
        html: Boolean,
        confirm: String?,
        dismiss: String?,
        restricted: Boolean,
    ): ConfirmResult {
        coroutineScope.launch {
            updateVisuals(ConfirmDialogVisualsImpl(title, content, markdown, html, confirm, dismiss, restricted))
            show()
        }
        return awaitResult()
    }

    override val dialogType: String get() = "ConfirmDialog"

    // 日志输出时附带当前内容，便于定位“弹了哪个确认框”
    override fun toString(): String {
        return "${super.toString()}(visuals: $visuals)"
    }

    // 保存/恢复辅助集中在此，供 rememberSaveable 使用
    companion object {
        /**
         * rememberSaveable 的 Saver：只保存 visuals（Parcelable），
         * 恢复时用现有的 visible/scope/callback/resultChannel 重建句柄，
         * 从而在配置变更后仍沿用同一个结果通道。
         */
        fun Saver(
            visible: MutableState<Boolean>,
            coroutineScope: CoroutineScope,
            callback: ConfirmCallback,
            resultChannel: ReceiveChannel<ConfirmResult>
        ) = Saver<ConfirmDialogHandle, ConfirmDialogVisuals>(
            save = {
                it.visuals
            },
            restore = {
                Log.d(TAG, "ConfirmDialog restore, visuals: $it")
                ConfirmDialogHandleImpl(visible, coroutineScope, callback, it, resultChannel)
            }
        )
    }
}

/**
 * 创建加载对话框句柄，并按当前 UI 模式渲染对应对话框。
 * 句柄只创建一次，复用同一个 visible 状态与 scope。
 */
@Composable
fun rememberLoadingDialog(): LoadingDialogHandle {
    val visible = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    when (LocalUiMode.current) {
        UiMode.Miuix -> LoadingDialogMiuix(visible)
        UiMode.Material -> LoadingDialogMaterial(visible)
    }

    return remember {
        LoadingDialogHandleImpl(visible, coroutineScope)
    }
}

/**
 * 创建确认对话框句柄并渲染界面。
 * visible 与句柄都用 rememberSaveable 保存，配置变更后对话框不会凭空消失；
 * resultChannel 用 remember 保持单例，避免恢复后的句柄指向失效通道。
 */
@Composable
private fun rememberConfirmDialog(visuals: ConfirmDialogVisuals, callback: ConfirmCallback): ConfirmDialogHandle {
    val visible = rememberSaveable {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val resultChannel = remember {
        Channel<ConfirmResult>()
    }

    val handle = rememberSaveable(
        saver = ConfirmDialogHandleImpl.Saver(visible, coroutineScope, callback, resultChannel),
        init = {
            ConfirmDialogHandleImpl(visible, coroutineScope, callback, visuals, resultChannel)
        }
    )

    when (LocalUiMode.current) {
        // 按钮点击只向通道发送结果，句柄负责统一驱动回调/挂起与关闭
        UiMode.Miuix -> ConfirmDialogMiuix(
            handle.visuals,
            confirm = { coroutineScope.launch { resultChannel.send(ConfirmResult.Confirmed) } },
            dismiss = { coroutineScope.launch { resultChannel.send(ConfirmResult.Canceled) } },
            showDialog = visible
        )

        UiMode.Material -> ConfirmDialogMaterial(
            handle.visuals,
            confirm = { coroutineScope.launch { resultChannel.send(ConfirmResult.Confirmed) } },
            dismiss = { coroutineScope.launch { resultChannel.send(ConfirmResult.Canceled) } },
            showDialog = visible
        )
    }

    return handle
}

/**
 * 把普通回调包装成 [ConfirmCallback]，内部用 rememberUpdatedState 保证
 * 始终调用最新 lambda，避免长生命周期句柄持有过期引用。
 */
@Composable
fun rememberConfirmCallback(onConfirm: NullableCallback, onDismiss: NullableCallback): ConfirmCallback {
    val currentOnConfirm by rememberUpdatedState(newValue = onConfirm)
    val currentOnDismiss by rememberUpdatedState(newValue = onDismiss)
    return remember {
        ConfirmCallback({ currentOnConfirm }, { currentOnDismiss })
    }
}

/**
 * [rememberConfirmDialog] 的便捷重载：直接传入确认/取消回调。
 */
@Composable
fun rememberConfirmDialog(onConfirm: NullableCallback = null, onDismiss: NullableCallback = null): ConfirmDialogHandle {
    return rememberConfirmDialog(rememberConfirmCallback(onConfirm, onDismiss))
}

/**
 * 以回调对象创建确认对话框，初始内容为空，需再调用 showConfirm/awaitConfirm 填充。
 */
@Composable
fun rememberConfirmDialog(callback: ConfirmCallback): ConfirmDialogHandle {
    return rememberConfirmDialog(ConfirmDialogVisualsImpl.Empty, callback)
}
