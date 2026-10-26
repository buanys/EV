package ev.buan.plugins.agent.log.gate

import ev.buan.plugins.agent.log.EvActors
import ev.buan.plugins.agent.log.EvEvent
import ev.buan.plugins.agent.log.EvTypes
import ev.buan.plugins.agent.log.EventLog
import org.json.JSONObject

/**
 * 总控：把一条事件走完「提案 → 权限 → 审核 → 执行」，全程写进同一条流。
 *
 * `world-design-v2.md` §8 第 2 步的验收就是它：
 * **一条真实事件 → 一个提案 → 一次判定 → 一次执行 → 可完整回放**。
 *
 * 总控**不判断**，只按固定顺序调用三层，并保证每个环节都留下事件：
 * 判断在 Gate 和 Review 里，动手在 Executor 里。它唯一的职责是"别让任何一步被跳过"。
 *
 * 失败路径同样写事件——被拒的提案也要能回放，否则事后无法解释"为什么当时没做"。
 */
class AgentLoop(
    private val log: EventLog,
    private val gate: Gate,
    private val review: Review,
    private val executor: Executor,
) {

    sealed interface Outcome {
        data class Executed(val resultEventId: String, val idempotentReplay: Boolean) : Outcome
        /** 权限层没放行。 */
        data class DeniedByGate(val stage: String, val reason: String) : Outcome
        /** 审核层拒绝。 */
        data class DeniedByReview(val reason: String) : Outcome
        /**
         * 审核层不确定 → 升级。v0 没有人审进程，所以**立即按超时处理成 deny**
         * （fail-closed：不能"等着等着就干了"）。
         */
        data class EscalatedThenDenied(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data class Unknown(val reason: String) : Outcome
    }

    /**
     * 处理一条触发事件与它的提案。
     *
     * [trigger] 必须已经在流里——提案要挂 `evidence_refs` 指向它，没有来源的提案过不了权限层。
     */
    fun handle(trigger: EvEvent, proposal: Proposal): Outcome {
        // 1. 提案入库。提案本身是事件，不是内存对象：没进流就等于没发生过。
        val proposalEvent = log.append(
            type = "proposal.${proposal.actionType}",
            actor = proposal.actor,
            payload = proposal.toEventPayload(),
            refs = listOf(trigger.id) + proposal.evidenceRefs,
        )

        // 2. 权限层。出不了门就不进审核层——顺序颠倒会"审完才发现没资格"。
        when (val g = gate.check(proposal)) {
            is Gate.Result.Denied -> {
                log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload("deny", proposalEvent.id, "code", listOf(g.reason), null),
                    refs = listOf(proposalEvent.id),
                )
                return Outcome.DeniedByGate(g.stage.name, g.reason)
            }
            Gate.Result.Allowed -> Unit
        }

        // 3. 审核层。
        val verdict = review.decide(proposal)
        return when (verdict) {
            is Review.Verdict.Allow -> {
                val verdictEvent = log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload("allow", proposalEvent.id, verdict.by.name.lowercase(), verdict.reasons, null),
                    refs = listOf(proposalEvent.id) + verdict.ruleRefs.map { ruleRefToEventId(it) }.filter { it.isNotEmpty() },
                )
                run(proposal, verdictEvent)
            }

            is Review.Verdict.Deny -> {
                log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload("deny", proposalEvent.id, verdict.by.name.lowercase(), verdict.reasons, null),
                    refs = listOf(proposalEvent.id),
                )
                Outcome.DeniedByReview(verdict.reasons.firstOrNull() ?: "审核层拒绝")
            }

            is Review.Verdict.Escalate -> {
                log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload("escalate", proposalEvent.id, verdict.by.name.lowercase(), verdict.reasons, null),
                    refs = listOf(proposalEvent.id),
                )
                // 没人审 → 按超时 deny。这条路径必须写事件，否则回放时只看到 escalate，
                // 事后无法区分"还在等人"和"已经按超时拒了"。
                log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload(
                        "deny", proposalEvent.id, "code",
                        listOf("escalate 无人在场，按超时处理为 deny（fail-closed）"), null,
                    ),
                    refs = listOf(proposalEvent.id),
                )
                Outcome.EscalatedThenDenied(verdict.reasons.firstOrNull() ?: "规则未覆盖")
            }

            is Review.Verdict.Modify -> {
                // v0 没有动作级缩降实现，modify 一律当 escalate 处理并同样超时 deny。
                log.append(
                    "verdict.decision", EvActors.CODE,
                    verdictPayload("deny", proposalEvent.id, "code", listOf("v0 未实现 modify 缩降，按 deny 处理"), null),
                    refs = listOf(proposalEvent.id),
                )
                Outcome.EscalatedThenDenied("modify 未实现")
            }
        }
    }

    private fun run(proposal: Proposal, verdictEvent: EvEvent): Outcome =
        when (val o = executor.execute(proposal, verdictEvent.id)) {
            is Executor.Outcome.Done -> Outcome.Executed(o.resultEventId, o.idempotentReplay)
            is Executor.Outcome.Failed -> Outcome.Failed(o.reason)
            is Executor.Outcome.Unknown -> Outcome.Unknown(o.reason)
            is Executor.Outcome.Rejected -> Outcome.Failed("执行器拒绝：${o.reason}")
        }

    private fun verdictPayload(
        decision: String,
        proposalId: String,
        by: String,
        reasons: List<String>,
        modifiedAction: JSONObject?,
    ): JSONObject = JSONObject().apply {
        put("decision", decision)
        put("proposal_id", proposalId)
        // by 必须如实标注：审计和成本核算都靠它（02 §2）。
        put("by", by)
        put("reasons", org.json.JSONArray(reasons))
        if (modifiedAction != null) put("modified_action", modifiedAction)
    }

    /**
     * 规则 id 到事件 id 的映射。v0 规则来自代码常量（[Review.v0]），流里没有对应的 `rule.add`
     * 事件，因此这里返回空串，由调用方过滤掉——**不编造一个不存在的事件 id**，
     * 否则校验器的 `ref.dangling` 会（正确地）把整条流判成不可信。
     */
    private fun ruleRefToEventId(ruleId: String): String = ""

    companion object {
        /** 闭环验收用的演示动作：不碰任何外部资源，纯粹验证链路。 */
        const val DEMO_ACTION = "demo.echo"

        fun demoHandler(): Executor.ActionHandler = Executor.ActionHandler { params ->
            JSONObject().put("echo", params.optString("text", ""))
        }

        /**
         * v0 的默认接线。授权只给演示动作，且范围限定在本插件存储区——
         * 默认什么都不放行，比默认能做事安全得多。
         */
        fun v0(log: EventLog): AgentLoop = AgentLoop(
            log = log,
            gate = Gate(
                grants = listOf(
                    Gate.Grant(capability = DEMO_ACTION, scope = "plugin:agent-log"),
                ),
                hardDenies = listOf(
                    Gate.HardDeny("cmd.run", "v0 不允许执行任意命令"),
                    Gate.HardDeny("fs.write", "v0 不允许写文件", paramEquals = "/system/" to ""),
                ),
            ),
            review = Review.v0(),
            executor = Executor(log, handlers = mapOf(DEMO_ACTION to demoHandler())),
        )
    }
}
