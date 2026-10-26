package ev.buan.plugins.agent.log

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验器的验收。对应 `/root/specs/02-schemas.md` §7 的每一条"机器可查"。
 *
 * 每个测试都构造一条**只违反一条规则**的流，避免"A 规则坏了但 B 规则也报"的测试
 * 在别处修好之后悄悄变成假阳性。
 */
class EventValidatorTest {

    private var seq = 0

    private fun event(
        type: String,
        actor: String = EvActors.CODE,
        payload: JSONObject = JSONObject(),
        refs: List<String> = emptyList(),
        idSeq: Int? = null,
    ): EvEvent {
        val s = idSeq ?: ++seq
        return EvEvent(
            seq = s,
            id = "ev_${s}_0000",
            ts = 1_000L * s,
            type = type,
            actor = actor,
            payload = payload,
            refs = refs,
        )
    }

    private fun rules(events: List<EvEvent>) =
        EventValidator.validate(events).map { it.rule to it.severity }

    // ---- 规则 1：seq 连续 ----

    @Test
    fun `干净的流没有任何违规`() {
        val events = listOf(
            event("sensor.battery", EvActors.HOST),
            event("sensor.battery", EvActors.HOST),
        )
        assertTrue(rules(events).isEmpty())
    }

    @Test
    fun `序号缺口被报出来`() {
        val events = listOf(
            event("sensor.battery", EvActors.HOST, idSeq = 1),
            event("sensor.battery", EvActors.HOST, idSeq = 3),
        )
        assertTrue(EventValidator.validate(events).any { it.rule == "seq.gap" })
    }

    // ---- 规则 4：refs 必须指向已存在且更早的事件 ----

    @Test
    fun `悬空引用被报出来`() {
        val events = listOf(event("report.period", refs = listOf("ev_99_0000")))
        assertTrue(EventValidator.validate(events).any { it.rule == "ref.dangling" })
    }

    @Test
    fun `引用更晚的事件被报出来`() {
        val later = event("sensor.battery", EvActors.HOST, idSeq = 2)
        val earlier = event("report.period", idSeq = 1, refs = listOf(later.id))
        assertTrue(EventValidator.validate(listOf(earlier, later)).any { it.rule == "ref.future" })
    }

    // ---- 规则 1（核心）：没有 allow 判定的 action 不合法 ----

    @Test
    fun `没有 allow 判定的动作是违规的`() {
        val proposal = event("proposal.fs.write", "agent:w1")
        val action = event("action.fs.write", EvActors.CODE, refs = listOf(proposal.id))

        val violations = EventValidator.validate(listOf(proposal, action))

        assertTrue(
            "无 allow 即无动作——这是整个规格最重要的一条",
            violations.any { it.rule == "action.without-allow" && it.seq == action.seq },
        )
    }

    @Test
    fun `挂了 allow 判定的动作合法`() {
        val proposal = event("proposal.fs.write", "agent:w1")
        val verdict = event(
            "verdict.decision", EvActors.CODE,
            payload = JSONObject().put("decision", "allow").put("proposal_id", proposal.id),
            refs = listOf(proposal.id),
        )
        val action = event("action.fs.write", EvActors.CODE, refs = listOf(verdict.id))

        val violations = EventValidator.validate(listOf(proposal, verdict, action))

        assertTrue("不应报无 allow", violations.none { it.rule == "action.without-allow" })
    }

    @Test
    fun `deny 不能充当放行`() {
        val proposal = event("proposal.fs.write", "agent:w1")
        val verdict = event(
            "verdict.decision", EvActors.CODE,
            payload = JSONObject().put("decision", "deny").put("proposal_id", proposal.id),
            refs = listOf(proposal.id),
        )
        val action = event("action.fs.write", EvActors.CODE, refs = listOf(verdict.id))

        assertTrue(
            EventValidator.validate(listOf(proposal, verdict, action))
                .any { it.rule == "action.without-allow" },
        )
    }

    // ---- 规则 5：modify 只能缩小 ----

    @Test
    fun `modify 缺少 modified_action 是违规的`() {
        val events = listOf(
            event("verdict.decision", payload = JSONObject().put("decision", "modify")),
        )
        assertTrue(EventValidator.validate(events).any { it.rule == "modify.without-action" })
    }

    @Test
    fun `modify 换掉动作类型是违规的`() {
        val proposal = event(
            "proposal.notify.send", "agent:w1",
            payload = JSONObject().put("action", JSONObject().put("type", "notify.send")),
        )
        val verdict = event(
            "verdict.decision", EvActors.CODE,
            payload = JSONObject()
                .put("decision", "modify")
                .put("proposal_id", proposal.id)
                // 把「发通知」降级成「删文件」不是缩减影响，是换了一件事。
                .put("modified_action", JSONObject().put("type", "fs.write")),
            refs = listOf(proposal.id),
        )

        assertTrue(
            EventValidator.validate(listOf(proposal, verdict)).any { it.rule == "modify.type-changed" },
        )
    }

    @Test
    fun `modify 参数范围未检查只报警告不报错`() {
        val proposal = event(
            "proposal.notify.send", "agent:w1",
            payload = JSONObject().put("action", JSONObject().put("type", "notify.send")),
        )
        val verdict = event(
            "verdict.decision", EvActors.CODE,
            payload = JSONObject()
                .put("decision", "modify")
                .put("proposal_id", proposal.id)
                .put("modified_action", JSONObject().put("type", "notify.send")),
            refs = listOf(proposal.id),
        )

        val violation = EventValidator.validate(listOf(proposal, verdict))
            .first { it.rule == "modify.range-unchecked" }
        assertTrue("通用 schema 判不了范围，必须诚实标成警告", violation.severity == EventValidator.Severity.WARN)
    }

    // ---- 规则 6：actor 形态 ----

    @Test
    fun `未知 actor 只报警告`() {
        val events = listOf(event("sys.test", actor = "somebody"))
        val violation = EventValidator.validate(events).first { it.rule == "actor.invalid" }
        assertTrue(violation.severity == EventValidator.Severity.WARN)
    }
}
