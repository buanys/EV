package ev.buan

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.BudgetExhausted
import org.cordis.api.BudgetKind
import org.cordis.api.BudgetTimeout
import org.cordis.api.BudgetUsage
import org.cordis.api.RuntimeBudgetService

/**
 * [RuntimeBudgetService] 的宿主实现：一个插件一份，名额表全局共享。
 *
 * 三个决定值得写下来，因为它们都是"看起来可以更简单"的地方：
 *
 * 1. **名额放在 [gate] 里而不是这里**。资源是设备的，一个插件占满就不能再开——
 *    每插件一份计数等于把总并发放大成"插件数 × 每插件上限"，也就是没有上限。
 *    这一份是**门禁**，插件只是门口登记的名字（[owner]）。
 *
 * 2. **任务挂在插件的 [lifetime] 上**。插件被停用或卸载时宿主取消这个 Job，
 *    于是"它提交的任务全部被收掉"不需要额外代码——取消沿协程树自动发生。
 *    如果不这么做，卸载后的任务会一直跑到自己的寿命结束，而它服务的对象早已不存在，
 *    名额也被一个不存在的插件占着。
 *
 * 3. **超时用 [withTimeoutOrNull] 包一层 [Done]，不用 `withTimeout`**。
 *    `withTimeout` 抛的是 `CancellationException` 的子类，调用方几乎一定会当成
 *    "我被取消了"原样往上吞（或者被 `runCatching` 吃掉），预算超时就静默消失了。
 *    [BudgetTimeout] 是普通异常，必须被看见。
 */
internal class HostRuntimeBudget(
    private val owner: String,
    private val gate: RuntimeBudgetGate,
    /** 插件自己的寿命：取消它等于取消这个插件名下所有仍在 [run] 里的任务。 */
    private val lifetime: Job,
) : RuntimeBudgetService {

    override suspend fun <T> run(kind: BudgetKind, block: suspend () -> T): T {
        // 占名额在进入 block 之前：已经取消的插件一个名额都不该借出去。
        if (gate.tryAcquire(kind, owner) == null) {
            throw BudgetExhausted(kind, gate.limitOf(kind), gate.runningOf(kind))
        }
        try {
            return runBounded(kind, block)
        } finally {
            // 四条出口都归位：正常返回、抛异常、超时、以及下面的取消。
            gate.release(kind, owner)
        }
    }

    override fun usage(): List<BudgetUsage> = gate.usage()

    private suspend fun <T> runBounded(kind: BudgetKind, block: suspend () -> T): T = coroutineScope {
        // 插件被停用：取消这个作用域，而不是等它把寿命跑完。已经取消的 lifetime 会在
        // 注册时立刻回调，所以卸载后残留的调用也进不来。
        val hook = lifetime.invokeOnCompletion { cancel() }
        try {
            val lifetimeMs = gate.lifetimeOf(kind)
            // null 只可能来自超时：block 的返回值被 Done 包着，不会与 null 混淆。
            withTimeoutOrNull(lifetimeMs) { Done(block()) }?.value
                ?: throw BudgetTimeout(kind, lifetimeMs)
        } finally {
            hook.dispose()
        }
    }
}

/** 把 block 的结果包起来，好让 [withTimeoutOrNull] 的 `null` 只表示超时。 */
private class Done<T>(val value: T)
