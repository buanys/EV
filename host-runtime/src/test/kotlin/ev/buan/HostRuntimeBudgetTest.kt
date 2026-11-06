package ev.buan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cordis.api.BudgetExhausted
import org.cordis.api.BudgetKind
import org.cordis.api.BudgetTimeout
import org.cordis.api.BudgetUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行预算的语义测试。
 *
 * 契约只有四件事，每条都用一个用例盯着：满额**拒绝且不执行**、名额**任何出口都归还**、
 * 超时是**普通异常**而不是取消、插件卸载时**在飞任务被收掉**。
 *
 * 全部跑在 `runTest` 的虚拟时间上，所以 `Short` 档的 30 秒寿命不用真等。
 * 填额度的任务一律用 `UNDISPATCHED` 立刻顶到挂起点，**故意不让 body 有挂起点**：
 * 一旦挂起，runTest 就会自动把虚拟时间推到 30 秒之后，前一半用例就会看见任务自己超时。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent：只跑排队中的任务，不推进虚拟时间
class HostRuntimeBudgetTest {

    /** 一个插件视角的装配：共享的名额表 + 它自己的寿命。 */
    private class Fixture {
        val gate = RuntimeBudgetGate()
        val lifetime: Job = SupervisorJob()

        fun budget(owner: String = "plugin.a"): HostRuntimeBudget =
            HostRuntimeBudget(owner, gate, lifetime)
    }

    private fun usageOf(budget: HostRuntimeBudget, kind: BudgetKind): BudgetUsage =
        budget.usage().first { it.kind == kind }

    /**
     * 把 [count] 个任务顶进 [kind] 档位，让它们停在 `awaitCancellation` 上，返回真的开跑的数量。
     *
     * 用 `UNDISPATCHED` + [runCurrent] 而不是 `receive()`：后者会让 body 挂起，
     * 从而给 runTest 一个推进虚拟时间的机会，把"还在飞"的任务推进成"已经超时"。
     */
    private fun TestScope.fill(budget: HostRuntimeBudget, kind: BudgetKind, count: Int): Int {
        var started = 0
        repeat(count) {
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    budget.run(kind) {
                        started += 1
                        awaitCancellation()
                    }
                } catch (_: BudgetTimeout) {
                    // 寿命到点即结束；用例的断言都在那之前做完。
                }
            }
        }
        runCurrent()
        return started
    }

    @Test
    fun `满额直接拒绝且 block 一行都没跑`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()
        assertEquals(4, fill(budget, BudgetKind.Short, count = 4))

        var ran = false
        val failure = runCatching {
            budget.run(BudgetKind.Short) { ran = true }
        }.exceptionOrNull()

        assertTrue("应抛 BudgetExhausted，实际 $failure", failure is BudgetExhausted)
        failure as BudgetExhausted
        assertEquals(BudgetKind.Short, failure.kind)
        assertEquals(4, failure.limit)
        assertEquals(4, failure.running)
        assertFalse("被拒绝的调用不该执行 block", ran)
        fixture.lifetime.cancel()
    }

    @Test
    fun `正常返回后名额归还`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()

        // 远超 Long 档的 2 个额度：只要有一次没归还，第 3 次就会被拒。
        repeat(8) {
            assertEquals(it * 2, budget.run(BudgetKind.Long) { it * 2 })
            assertEquals(0, usageOf(budget, BudgetKind.Long).running)
        }
        fixture.lifetime.cancel()
    }

    @Test
    fun `block 抛异常也归还名额`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()

        repeat(6) {
            val failure = runCatching { budget.run(BudgetKind.Long) { error("boom") } }.exceptionOrNull()
            assertEquals("boom", failure?.message)
            assertEquals(0, usageOf(budget, BudgetKind.Long).running)
        }
        fixture.lifetime.cancel()
    }

    @Test
    fun `超时抛 BudgetTimeout 而不是取消 并归还名额`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()

        val failure = runCatching {
            budget.run(BudgetKind.Short) { awaitCancellation() }
        }.exceptionOrNull()

        // 关键是它**不是** CancellationException：否则调用方一定会当成"我被取消了"吞掉。
        assertTrue("应抛 BudgetTimeout，实际 $failure", failure is BudgetTimeout)
        assertFalse(failure is CancellationException)
        failure as BudgetTimeout
        assertEquals(BudgetKind.Short, failure.kind)
        assertEquals(30_000L, failure.maxDurationMs)
        assertEquals(0, usageOf(budget, BudgetKind.Short).running)
        fixture.lifetime.cancel()
    }

    @Test
    fun `插件卸载时在飞任务被收掉且名额归还`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()
        assertEquals(2, fill(budget, BudgetKind.Long, count = 2))
        assertEquals(2, usageOf(budget, BudgetKind.Long).running)

        fixture.lifetime.cancel()
        runCurrent()

        // 名额在插件消失的同一轮里就还回来了，不用等寿命到期。
        assertEquals(0, usageOf(budget, BudgetKind.Long).running)
    }

    @Test
    fun `卸载后残留的调用立即结束 不会占着名额`() = runTest {
        val fixture = Fixture()
        fixture.lifetime.cancel()
        val budget = fixture.budget()

        val failure = runCatching { budget.run(BudgetKind.Short) { "unreachable" } }.exceptionOrNull()

        assertTrue("应是取消，实际 $failure", failure is CancellationException)
        assertEquals(0, usageOf(budget, BudgetKind.Short).running)
    }

    @Test
    fun `不同档位互不占用`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()
        assertEquals(4, fill(budget, BudgetKind.Short, count = 4))

        // Short 已经满了，Long 还必须能开：卡住一次读屏不该让 WebView 渲染也开不了。
        assertEquals("ok", budget.run(BudgetKind.Long) { "ok" })
        fixture.lifetime.cancel()
    }

    @Test
    fun `名额是设备的 不是每个插件一份`() = runTest {
        val fixture = Fixture()
        val first = fixture.budget("plugin.a")
        val second = fixture.budget("plugin.b")
        assertEquals(2, fill(first, BudgetKind.Long, count = 2))

        // b 自己一个都没开，也要被拒：被抢走的是进程的线程和内存。
        val failure = runCatching { second.run(BudgetKind.Long) { "unreachable" } }.exceptionOrNull()
        assertTrue("应是 BudgetExhausted，实际 $failure", failure is BudgetExhausted)
        fixture.lifetime.cancel()
    }

    @Test
    fun `usage 报得出谁占着`() = runTest {
        val fixture = Fixture()
        val first = fixture.budget("plugin.a")
        val second = fixture.budget("plugin.b")
        assertEquals(2, fill(first, BudgetKind.Short, count = 2))
        assertEquals(1, fill(second, BudgetKind.Short, count = 1))

        val usage = usageOf(first, BudgetKind.Short)
        assertEquals(3, usage.running)
        assertEquals(4, usage.limit)
        assertFalse(usage.full)
        // 占用多的排前面，排障时第一眼看得到是谁把额度吃掉了。
        assertEquals(
            listOf("plugin.a" to 2, "plugin.b" to 1),
            usage.holders.map { it.pluginId to it.count },
        )
        fixture.lifetime.cancel()
    }

    @Test
    fun `满额时 usage 如实说满了`() = runTest {
        val fixture = Fixture()
        val budget = fixture.budget()
        assertEquals(2, fill(budget, BudgetKind.Background, count = 2))

        val usage = usageOf(budget, BudgetKind.Background)
        assertTrue(usage.full)
        assertEquals(2, usage.running)
        fixture.lifetime.cancel()
    }
}
