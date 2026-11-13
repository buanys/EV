package ev.buan

import org.cordis.api.BudgetHolder
import org.cordis.api.BudgetKind
import org.cordis.api.BudgetUsage

/**
 * 每个档位的并发上限与寿命。
 *
 * 数值是按设备上的真实代价定的，不是拍脑袋的整数：
 *
 *  - [BudgetKind.Short] 4 个 / 30 秒。单个很便宜（一次读屏、一次 GET），但它是高频档位，
 *    真同时开五个就会在主线程上排队，用户看到的是"点了没反应"。
 *  - [BudgetKind.Long] 2 个 / 180 秒。这一档会攥原生内存（离屏 WebView 一个就是几 MB）
 *    或外部配额（模型调用）。2 是"能并行做两件正事"与"内存还回得来"的交点；
 *    180 秒覆盖 `run_shell` 的上限 120 秒，留出收尾时间。
 *  - [BudgetKind.Background] 2 个 / 600 秒。维护动作做不完可以下次再做，
 *    所以额度不宽、寿命很长——它被拒绝是对的，被中途掐死才浪费。
 *
 * 三档**互不占用**：卡住的长任务不该让一次读屏也开不了。
 */
internal class BudgetPolicy(
    private val limits: Map<BudgetKind, Int>,
    private val lifetimes: Map<BudgetKind, Long>,
) {
    fun limitOf(kind: BudgetKind): Int = limits.getValue(kind)

    fun lifetimeOf(kind: BudgetKind): Long = lifetimes.getValue(kind)

    companion object {
        val Default = BudgetPolicy(
            limits = mapOf(
                BudgetKind.Short to 4,
                BudgetKind.Long to 2,
                BudgetKind.Background to 2,
            ),
            lifetimes = mapOf(
                BudgetKind.Short to 30_000L,
                BudgetKind.Long to 180_000L,
                BudgetKind.Background to 600_000L,
            ),
        )
    }
}

/**
 * 全局的准入计数器：每个档位还能开几个，谁占着。
 *
 * 为什么是全局而不是每插件一份：**被抢的资源是设备的**。插件 A 起了两个离屏渲染，
 * 插件 B 的那一个就该被拒——按插件分别计数只会让总并发放大到「插件数 × 每插件上限」，
 * 也就是没有上限。
 *
 * 代价是不公平：一个插件可以占满一个档位。这是已知且接受的偏差——[usage] 把持有者
 * 暴露出来，好让排障时看得见是谁；真正的公平让给后面的调度层，这一层不做
 * （ADR-0004：宿主只回答能不能开、多久必须结束）。
 *
 * 用一把锁而不是 CAS：预算操作是**每次任务一次**，不在热路径上，锁竞争可以忽略；
 * 换来的是计数与持有者表永远一致——这两件事分开维护才是真正容易错的地方。
 */
internal class RuntimeBudgetGate(private val policy: BudgetPolicy = BudgetPolicy.Default) {

    private val lock = Any()

    /** 每个档位上，各插件正在占用的名额数。占用为零的插件会被移出，避免无界增长。 */
    private val held = HashMap<BudgetKind, MutableMap<String, Int>>()

    /** 该档位的并发上限，写进拒绝消息与 [usage]。 */
    fun limitOf(kind: BudgetKind): Int = policy.limitOf(kind)

    /** 该档位一次调用的寿命上限，超时即取消。 */
    fun lifetimeOf(kind: BudgetKind): Long = policy.lifetimeOf(kind)

    /** 该档位此刻的占用总数，只用于拒绝消息（判定本身在 [tryAcquire] 的锁里做）。 */
    fun runningOf(kind: BudgetKind): Int = synchronized(lock) {
        held[kind].orEmpty().values.sum()
    }

    /**
     * 试着占一个名额。返回占用后该插件在此档位的总数；已满返回 null（**调用方没有执行**）。
     */
    fun tryAcquire(kind: BudgetKind, owner: String): Int? = synchronized(lock) {
        val byOwner = held.getOrPut(kind) { HashMap() }
        val running = byOwner.values.sum()
        if (running >= policy.limitOf(kind)) return null
        val mine = (byOwner[owner] ?: 0) + 1
        byOwner[owner] = mine
        mine
    }

    /** 归还一个名额。多个同名 owner 的占用各自记账，所以归还必须带 owner。 */
    fun release(kind: BudgetKind, owner: String) {
        synchronized(lock) {
            val byOwner = held[kind] ?: return
            val mine = byOwner[owner] ?: return
            if (mine <= 1) byOwner.remove(owner) else byOwner[owner] = mine - 1
        }
    }

    fun usage(): List<BudgetUsage> = synchronized(lock) {
        BudgetKind.entries.map { kind ->
            val byOwner = held[kind].orEmpty()
            BudgetUsage(
                kind = kind,
                running = byOwner.values.sum(),
                limit = policy.limitOf(kind),
                holders = byOwner
                    .map { BudgetHolder(it.key, it.value) }
                    .sortedWith(compareByDescending<BudgetHolder> { it.count }.thenBy { it.pluginId }),
            )
        }
    }
}
