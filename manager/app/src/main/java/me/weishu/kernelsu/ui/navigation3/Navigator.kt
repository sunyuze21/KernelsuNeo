package me.weishu.kernelsu.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/**
 * Simple navigation helper that owns a miuix-nav back stack.
 * Supports push/replace/pop/popUntil over the shared [NavBackStack].
 *
 * 中文说明：导航栈由外部注入而非内部创建，因此同一个 Navigator 可跨重组复用；
 * 所有路由操作都直接改写 [backStack]，Compose 侧通过其快照状态自动重组。
 */
@Suppress("unused")
class Navigator(
    val backStack: NavBackStack,
) {

    // 以 requestKey 为维度保存一次性结果通道；replay=1 让订阅方在稍后订阅时仍能拿到最近一次结果
    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    /**
     * Push a key onto the back stack, idempotently: a double tap pushing the same route value
     * twice is a duplicate contentKey (rejected by the runtime), so skip keys already present.
     * Routes needing multiple live instances carry unique values instead.
     *
     * 中文说明：去重仅比较路由值，因此同一页面需要有多个实例时，路由必须携带唯一参数。
     */
    fun push(key: NavKey) {
        if (key !in backStack) {
            backStack.add(key)
        }
    }

    /**
     * Replace the top key, or push if the stack is empty.
     *
     * 中文说明：栈深保持不变，原栈顶页面不会留在返回栈中，适用于"同一层级切换"场景。
     */
    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    /**
     * Replace the backstack with a new list of keys if the stack is not empty.
     *
     * 中文说明：空列表或原本为空的栈会被忽略，避免导航栈被清空导致无页面可显示。
     */
    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) {
            return
        }
        if (backStack.isNotEmpty()) {
            backStack.clear()
            backStack.addAll(keys)
        }
    }


    /**
     * Pop the top key if present.
     *
     * 中文说明：至少保留一个根页面，防止返回栈为空时界面消失。
     */
    fun pop() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    /**
     * Pop until predicate matches the top key.
     *
     * 中文说明：用于返回到某个已有页面（如从深层详情跳回列表），根页面永远不会被弹出。
     */
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.size > 1 && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /**
     * Navigate expecting a result. Caller should subscribe via observeResult(requestKey).
     *
     * 中文说明：调用方必须使用相同的 requestKey 订阅，否则结果会滞留在通道中直到被覆盖。
     */
    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    /**
     * Set a result for the given request and then pop.
     *
     * 中文说明：tryEmit 不会挂起，若缓冲区不可用结果会丢失，因此调用后必须立即返回上一页。
     */
    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    /**
     * Observe results for a given request key as a SharedFlow.
     *
     * 中文说明：通道是复用的，同一 requestKey 的多次导航会共享同一个 Flow；
     * 泛型仅靠调用方约定，类型不匹配会导致运行时强转失败。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> {
        return ensureChannel(requestKey) as SharedFlow<T>
    }

    /**
     * Clear the last emitted result for the request key.
     *
     * 中文说明：必须显式清理，否则下一次订阅会重放上一次的旧结果，造成界面状态错乱。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    /**
     * Get current NavKey on the back stack.
     *
     * 中文说明：栈为空时返回 null，调用方需自行兜底。
     */
    fun current() = backStack.lastOrNull()

    /**
     * Get current size of back stack.
     *
     * 中文说明：可用于判断是否存在可返回的页面。
     */
    fun backStackSize() = backStack.size

    // 按需创建结果通道：提前创建可让 navigateForResult 之前就订阅的调用方不丢事件
    private fun ensureChannel(key: String): MutableSharedFlow<Any> {
        return resultBus.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 0) }
    }

}

/**
 * 创建并与当前组合绑定的 Navigator。
 *
 * rememberNavBackStack 会保存导航栈，配置变更/重组后仍能恢复到原页面；
 * remember(backStack) 保证只在栈实例变化时重建 Navigator，避免结果通道被反复清空。
 */
@Composable
fun rememberNavigator(startRoute: Route): Navigator {
    val backStack = rememberNavBackStack<Route>(startRoute)
    return androidx.compose.runtime.remember(backStack) { Navigator(backStack) }
}

/**
 * 向下传递 Navigator 实例。
 *
 * 没有默认值，未提供时直接抛错，用于尽早暴露组合树结构错误；
 * 同时使用 static 变体，避免导航调用引起不必要的重组。
 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
