package org.cordis.api

import org.cordis.ServiceKey

/**
 * 执行预算：插件问宿主「这件事现在能不能开、最久能跑多久」。
 *
 * 为什么需要它：**并发是设备的资源，不是插件的私有财产**。一个插件自己 `new Thread` 或者
 * 建一个无上限的线程池，代价由整个进程承担——离屏 `WebView`、子进程、JPEG 解码挤在一起，
 * 最先死掉的是用户正在看的那个界面。以前宿主对此没有任何观测点：坏插件吃满线程数，
 * 日志里只有一句 OOM。
 *
 * 这个契约只回答两个问题，**不排队、不做优先级、不做调度**：
 *
 *  - **能不能开**：该档位已经满了就立刻抛 [BudgetExhausted]；
 *  - **多久必须结束**：超过档位寿命抛 [BudgetTimeout]。
 *
 * 拒绝而不是排队是刻意的。排队会把「现在开不了」变成「过一会儿也许能开」，
 * 调用方于是必须为一个可能永远不来的结果设计超时；而栈已经压到预算上限的时候，
 * 排队只是把崩溃时间往后推。调用方拿到拒绝，应当**降级**（跳过这一步、缩减批量、
 * 告诉模型「资源忙」），而不是重试到成功。
 *
 * ```
 * val budget = ctx.require(RuntimeBudgetService.Key)
 * val page = budget.run(BudgetKind.Short) { fetch(url) }
 * ```
 *
 * 三条使用纪律：
 *
 *  1. **不要自己建线程池 / 无主 scope**。工作包进 [run]，宿主才知道有多少在飞；
 *  2. 被拒绝是**正常结果**，不是异常事故——你的代码必须能在被拒后继续做事；
 *  3. 别拿 [run] 当线程池用。它不加速任何东西，只做准入。
 *
 * 名额一定会归还：[run] 在被拒绝之后、抛异常、超时、被取消这四种出口上都释放它。
 * 插件被停用或卸载时，宿主取消它名下所有仍在 [run] 里的任务，名额随之归还。
 */
interface RuntimeBudgetService {

    /**
     * 在 [kind] 档位的预算内跑 [block]，返回它的结果。
     *
     * 抛 [BudgetExhausted]：该档位已满，**这次没有执行**（[block] 一行都没跑）。
     * 抛 [BudgetTimeout]：跑超时了，[block] 被取消；底层不感知取消的工作（阻塞式 IO、
     * 原生调用）可能仍在收尾，但它已经与调用方无关。
     *
     * 外部取消（插件被停用）照常以 `CancellationException` 传播，不会被包装成上面两种。
     */
    suspend fun <T> run(kind: BudgetKind, block: suspend () -> T): T

    /**
     * 当前各档位的占用情况。
     *
     * 给「资源忙」这类界面文案与排障用：宿主唯一的预算观测点就在这里，
     * 想知道谁把名额吃满了，看 [BudgetUsage.holders]。
     */
    fun usage(): List<BudgetUsage>

    companion object {
        val Key = ServiceKey<RuntimeBudgetService>("host/runtime-budget")
    }
}

/**
 * 预算档位。按**代价**分，不按调用方的心情分。
 *
 * 三个档位各自一套并发上限与寿命，互不占用对方的额度：一个卡住的长任务不该让
 * 一次读屏也开不了。
 */
enum class BudgetKind {
    /**
     * 用户正盯着结果等的一次短动作：读一次屏、点一下、发一个 HTTP 请求、查一次日历。
     *
     * 额度最宽（这类动作单个很便宜），寿命最短（超过半分钟还没回来，用户已经走神了）。
     */
    Short,

    /**
     * 用户仍等着、但代价确实大的动作：渲染一个需要用 WebView 跑 JS 的页面、抓一整站、
     * 让模型跑一轮。这些会占原生内存或外部配额，所以额度很窄。
     */
    Long,

    /**
     * 用户看不见的维护动作：重建索引、整理日志、清理缓存、归档会话。
     *
     * 额度中等，寿命最长。调用方应当假定它**随时可能被拒绝**：维护做不完可以下次再做，
     * 与用户正等的动作抢资源才是真的亏。
     */
    Background,
}

/** 一个档位眼下的占用情况。 */
data class BudgetUsage(
    val kind: BudgetKind,
    /** 正在占用名额的任务数。 */
    val running: Int,
    /** 这个档位的并发上限。 */
    val limit: Int,
    /** 各插件占用的数量，按占用数降序。空闲时为空。 */
    val holders: List<BudgetHolder> = emptyList(),
) {
    val full: Boolean get() = running >= limit
}

/** 某个插件在某档位占着的名额数。 */
data class BudgetHolder(val pluginId: String, val count: Int)

/**
 * 该档位已满，这次调用**没有执行**。
 *
 * 是 [IllegalStateException] 而不是受检异常：Kotlin 没有受检异常，而这必须被看见。
 * 消息里带上档位、上限与当前占用，排障时不用再猜是哪一步没跑。
 */
class BudgetExhausted(
    val kind: BudgetKind,
    val limit: Int,
    val running: Int,
) : IllegalStateException(
    "执行预算已满：${kind.name} 档位 $running/$limit，这次没有执行（不排队，请降级）",
)

/**
 * 超过该档位的寿命，任务已被取消。
 *
 * 与 [BudgetExhausted] 分开，是因为处理方式完全不同：满额应当**跳过**这个动作，
 * 超时则说明这件事太大了，应当**拆小或换路子**。
 *
 * 不是 `CancellationException` 的子类：它不是协程取消，调用方不该把它当成"我被取消了"
 * 原样往外吞——它就是一次失败，得让上层看见。
 */
class BudgetTimeout(
    val kind: BudgetKind,
    val maxDurationMs: Long,
) : IllegalStateException(
    "执行预算超时：${kind.name} 档位最多 $maxDurationMs ms，任务已取消",
)
