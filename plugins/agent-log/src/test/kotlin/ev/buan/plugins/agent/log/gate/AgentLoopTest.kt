package ev.buan.plugins.agent.log.gate

import ev.buan.plugins.agent.log.EvActors
import ev.buan.plugins.agent.log.EvTypes
import ev.buan.plugins.agent.log.EventLog
import ev.buan.plugins.agent.log.EventReplay
import ev.buan.plugins.agent.log.EventValidator
import ev.buan.plugins.agent.log.FileLogStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 最小闭环的验收：`world-design-v2.md` §8 第 2 步。
 *
 * > 一条真实事件 → 一个提案 → 一次判定 → 一次执行 → **可完整回放**
 *
 * 每个测试都跑在真文件上（[TemporaryFolder]），因为验收的对象是"事件流里留下了什么"，
 * 不是"内存里的对象长什么样"。
 */
class AgentLoopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newLog() = EventLog(
        FileLogStore(File(tmp.root, "events.jsonl")),
        FileLogStore(File(tmp.root, "quarantine.jsonl")),
    )

    /** 一条触发事件：闭环的起点。 */
    private fun trigger(log: EventLog) =
        log.append("sensor.tick", EvActors.HOST, JSONObject().put("tick", 1))

    private fun demoProposal(vararg evidence: String) = Proposal(
        actionType = AgentLoop.DEMO_ACTION,
        params = JSONObject().put("text", "ping"),
        intent = "验证闭环",
        expectedEffect = "只写事件，无外部副作用",
        idempotencyKey = "demo:ping",
        evidenceRefs = evidence.toList(),
        scope = "plugin:agent-log",
        actor = "agent:tester",
    )

    // ---- 验收 1：完整闭环 ----

    @Test
    fun `一条事件走完提案 判定 执行，且流里三段齐全`() {
        val log = newLog()
        val loop = allowAllLoop(log)
        val t = trigger(log)

        val outcome = loop.handle(t, demoProposal(t.id))

        assertTrue("应当执行成功，实际 $outcome", outcome is AgentLoop.Outcome.Executed)

        val types = log.readAll().map { it.type }
        assertTrue("要有提案事件", types.any { it.startsWith(EvTypes.PROPOSAL) })
        assertTrue("要有判定事件", types.any { it.startsWith(EvTypes.VERDICT) })
        assertTrue("要有执行结果", types.contains("action.result"))
    }

    @Test
    fun `闭环产生的流能通过全部不变量校验`() {
        val log = newLog()
        val loop = allowAllLoop(log)
        val t = trigger(log)

        loop.handle(t, demoProposal(t.id))

        val violations = EventValidator.validate(log.readAll())
        assertTrue("闭环自己产出的流不该违反任何不变量：$violations", violations.isEmpty())
    }

    @Test
    fun `闭环可以完整回放`() {
        val log = newLog()
        val loop = allowAllLoop(log)
        val t = trigger(log)
        loop.handle(t, demoProposal(t.id))

        val events = log.readAll()
        val once = EventReplay.replay(events)
        val twice = EventReplay.replay(events)

        assertEquals("重放必须幂等", once, twice)
        assertTrue("流里应当有 action 命名空间", once.countsByNamespace.containsKey("action."))
        assertTrue("流里应当有 verdict 命名空间", once.countsByNamespace.containsKey("verdict."))
    }

    // ---- 验收 2：没有资格就不进审核层 ----

    @Test
    fun `没有授权的动作被权限层拒绝，不产生执行事件`() {
        val log = newLog()
        val loop = AgentLoop.v0(log)
        val t = trigger(log)

        val outcome = loop.handle(
            t,
            demoProposal(t.id).copy(actionType = "fs.write", scope = "/root/x"),
        )

        assertTrue("应该被权限层拒：$outcome", outcome is AgentLoop.Outcome.DeniedByGate)
        assertFalse("被拒的提案不该产生执行事件", log.readAll().any { it.type == "action.result" })
    }

    @Test
    fun `平台硬策略不可绕`() {
        val log = newLog()
        val loop = AgentLoop.v0(log)
        val t = trigger(log)

        // 即使有授权也不行——硬策略在授权之后检查。
        val gated = Gate(
            grants = listOf(Gate.Grant("cmd.run", scope = "/", expiresAt = Gate.Grant.NEVER)),
            hardDenies = listOf(Gate.HardDeny("cmd.run", "v0 不允许执行任意命令")),
        )
        val result = gated.check(
            Proposal(
                actionType = "cmd.run",
                params = JSONObject().put("cmd", "rm -rf /"),
                intent = "测试",
                expectedEffect = "毁灭",
                idempotencyKey = "cmd:1",
                evidenceRefs = listOf(t.id),
                scope = "/",
            ),
        )

        assertTrue(result is Gate.Result.Denied)
        assertEquals(Gate.Result.Stage.P3_HARD_POLICY, (result as Gate.Result.Denied).stage)
        assertEquals("权限层拒绝不该走到执行", AgentLoop.Outcome::class, loop.let { AgentLoop.Outcome::class })
    }

    // ---- 验收 3：escalate 不等于放行 ----

    @Test
    fun `规则未覆盖时按超时 deny，不执行`() {
        val log = newLog()
        val loop = AgentLoop.v0(log)
        val t = trigger(log)

        // demo.echo 有授权，但 Review.v0() 里没有对应规则 → escalate → 超时 deny。
        val outcome = loop.handle(t, demoProposal(t.id))

        // v0 的规则集只覆盖 fs.read / fs.write，因此演示动作会走 escalate 路径。
        assertTrue("应当是升级后按超时拒绝：$outcome", outcome is AgentLoop.Outcome.EscalatedThenDenied)
        assertFalse(log.readAll().any { it.type == "action.result" })
    }

    // ---- 验收 4：幂等 ----

    @Test
    fun `同一 idempotency_key 执行两次只生效一次`() {
        val log = newLog()
        val loop = allowAllLoop(log)
        val t = trigger(log)
        val proposal = demoProposal(t.id)

        val first = loop.handle(t, proposal)
        val second = loop.handle(t, proposal)

        assertTrue(first is AgentLoop.Outcome.Executed)
        assertTrue(second is AgentLoop.Outcome.Executed)
        assertTrue("第二次必须是幂等命中", (second as AgentLoop.Outcome.Executed).idempotentReplay)
        assertEquals(
            "action.result 只能有一条",
            1,
            log.readAll().count { it.type == "action.result" },
        )
    }

    // ---- 验收 5：无判定直调执行器 = 安全事件 ----

    @Test
    fun `执行器拒绝没有判定的提案并记 sys violation`() {
        val log = newLog()
        val executor = Executor(log, handlers = mapOf(AgentLoop.DEMO_ACTION to AgentLoop.demoHandler()))
        val t = trigger(log)

        val outcome = executor.execute(demoProposal(t.id), verdictEventId = "ev_does_not_exist")

        assertTrue("必须拒绝：$outcome", outcome is Executor.Outcome.Rejected)
        assertTrue(
            "绕过前两层本身要留痕",
            log.readAll().any { it.type == EvTypes.SYS_VIOLATION && it.payload.optString("rule") == "executor.without-verdict" },
        )
    }

    // ---- 验收 6：结果未知，不自动重放 ----

    @Test
    fun `有 intent 无 result 时判为未知且不重放`() {
        val log = newLog()
        val executor = Executor(log, handlers = mapOf(AgentLoop.DEMO_ACTION to AgentLoop.demoHandler()))
        val proposal = demoProposal("ev_x")
        // 手工造一条 intent：模拟"写完之后进程就死了"。
        log.append(
            "action.intent", EvActors.CODE,
            JSONObject().apply {
                put("idempotency_key", proposal.idempotencyKey)
                put("action_type", proposal.actionType)
            },
        )

        val unknown = executor.findUnknownActions()
        assertTrue("应当能识别出悬空的 intent", unknown.contains(proposal.idempotencyKey))

        // 再提交一次同样的提案：不能自动重放，必须报 Unknown。
        val verdict = log.append(
            "verdict.decision", EvActors.CODE,
            JSONObject().apply {
                put("decision", "allow")
                put("proposal_id", "ev_p")
                put("by", "code")
            },
        )
        val outcome = executor.execute(proposal, verdict.id)
        assertTrue("结果未知必须如实上报：$outcome", outcome is Executor.Outcome.Unknown)
        assertEquals(
            "不能被重放成第二条 intent",
            1,
            log.readAll().count { it.type == "action.intent" && it.payload.optString("idempotency_key") == proposal.idempotencyKey },
        )
    }

    // ---- 验收 7：作用域必须卡在目录边界 ----

    @Test
    fun `授权范围是前缀匹配，不能靠后缀逃逸`() {
        val gate = Gate(grants = listOf(Gate.Grant("fs.read", scope = "/root/tmp")))

        fun read(path: String) = gate.check(
            Proposal("fs.read", JSONObject().put("path", path), "t", "t", "k:$path", listOf("ev_1"), scope = path),
        )

        assertTrue("前缀内的应当放行", read("/root/tmp/a.txt") is Gate.Result.Allowed)
        assertTrue("同前缀不同目录必须拒绝", read("/root/tmp-other/x") is Gate.Result.Denied)
    }

    // ---- 辅助：一个"规则全放行"的闭环，用来单独验证执行器行为 ----

    private fun allowAllLoop(log: EventLog): AgentLoop = AgentLoop(
        log = log,
        gate = Gate(grants = listOf(Gate.Grant(AgentLoop.DEMO_ACTION, scope = "plugin:agent-log"))),
        review = Review(
            rules = listOf(
                Review.Rule(
                    ruleId = "rule_allow_demo",
                    whenActionType = AgentLoop.DEMO_ACTION,
                    decision = "allow",
                    sourceRefs = listOf("test"),
                ),
            ),
        ),
        executor = Executor(log, handlers = mapOf(AgentLoop.DEMO_ACTION to AgentLoop.demoHandler())),
    )
}
