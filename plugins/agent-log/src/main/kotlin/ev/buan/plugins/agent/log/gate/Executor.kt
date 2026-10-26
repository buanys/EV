package ev.buan.plugins.agent.log.gate

import ev.buan.plugins.agent.log.EvActors
import ev.buan.plugins.agent.log.EvEvent
import ev.buan.plugins.agent.log.EvTypes
import ev.buan.plugins.agent.log.EventLog
import org.json.JSONObject

/**
 * 执行器：操作工（`/root/specs/04-gate-executor.md` §4）。
 *
 * 只动手，不做决定。三条不变量由这个类自己守：
 *
 * 1. **没有判定就拒绝**，并且记 `sys.violation`——这是安全事件，不是普通错误。
 * 2. **幂等**：同一 `idempotency_key` 执行多次 = 效果一次。
 * 3. **结果未知不许自动重放**：写 `action.intent` 之后再执行，执行完写 `action.result`。
 *    恢复时发现"intent 有、result 无"→ 标 `unknown` 交给上层，**不自动重试**。
 *    自动重试一个可能已经生效的动作，是这类系统最典型的自伤方式。
 *
 * 执行器不缓存判定——每次都用事件流里的 verdict 事件本身做校验，
 * 防的是内部代码绕过前两层直接调它。
 */
class Executor(
    private val log: EventLog,
    private val handlers: Map<String, ActionHandler>,
    private val deadlineMs: Long = 10_000,
) {

    /** 动作处理器。返回结果 payload；抛异常表示失败。 */
    fun interface ActionHandler {
        fun run(params: JSONObject): JSONObject
    }

    sealed interface Outcome {
        /** 成功执行（或此前已成功过——幂等命中）。 */
        data class Done(val resultEventId: String, val idempotentReplay: Boolean) : Outcome

        /** 执行失败，原因已写进流。 */
        data class Failed(val reason: String, val retryable: Boolean) : Outcome

        /**
         * 结果未知：intent 已写、result 缺失。
         * **不能当失败**——失败意味着确认没生效，而这里意味着可能已经生效了。
         */
        data class Unknown(val reason: String) : Outcome

        /** 被拒绝：没有合法判定、或判定不是放行。 */
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * 执行一条提案。
     *
     * [verdictEventId] 必须在流里存在且是 `allow` 判定——不接受调用方口头说"已经批过了"。
     */
    fun execute(proposal: Proposal, verdictEventId: String): Outcome {
        val events = log.readAll()
        val verdict = events.firstOrNull { it.id == verdictEventId }
            ?: return reject(proposal, "判定事件 $verdictEventId 不存在")

        val decision = verdict.payload.optString("decision", "")
        if (decision != "allow" && decision != "modify") {
            return reject(proposal, "判定不是放行（decision=$decision）")
        }
        if (verdict.type != EvTypes.VERDICT.plus("decision")) {
            return reject(proposal, "引用的不是判定事件（type=${verdict.type}）")
        }

        // ---- 幂等：这个键是否已经跑完过 ----
        val prior = events.firstOrNull {
            it.type == ACTION_RESULT && it.payload.optString("idempotency_key") == proposal.idempotencyKey
        }
        if (prior != null) {
            return Outcome.Done(prior.id, idempotentReplay = true)
        }

        // ---- 结果未知：上次 intent 写了但没落 result ----
        val danglingIntent = events.firstOrNull {
            it.type == ACTION_INTENT && it.payload.optString("idempotency_key") == proposal.idempotencyKey
        }
        if (danglingIntent != null) {
            return Outcome.Unknown(
                "同一 idempotency_key 已有 intent（${danglingIntent.id}）但没有 result，" +
                    "上次执行结果未知，不自动重放",
            )
        }

        val handler = handlers[proposal.actionType]
            ?: run {
                log.append(
                    ACTION_FAILED, EvActors.CODE,
                    JSONObject().apply {
                        put("idempotency_key", proposal.idempotencyKey)
                        put("reason", "没有 ${proposal.actionType} 的处理器")
                        put("retryable", false)
                    },
                    refs = listOf(verdictEventId),
                )
                return Outcome.Failed("没有 ${proposal.actionType} 的处理器", retryable = false)
            }

        // ---- 写 intent：从这一刻起，"可能已生效" ----
        val intent = log.append(
            ACTION_INTENT, EvActors.CODE,
            JSONObject().apply {
                put("idempotency_key", proposal.idempotencyKey)
                put("action_type", proposal.actionType)
                put("params", proposal.params)
                put("deadline_ms", deadlineMs)
            },
            refs = listOf(verdictEventId),
        )

        return try {
            val result = handler.run(proposal.params)
            val done = log.append(
                ACTION_RESULT, EvActors.CODE,
                JSONObject().apply {
                    put("idempotency_key", proposal.idempotencyKey)
                    put("action_type", proposal.actionType)
                    put("result", result)
                },
                refs = listOf(intent.id),
            )
            Outcome.Done(done.id, idempotentReplay = false)
        } catch (t: Throwable) {
            log.append(
                ACTION_FAILED, EvActors.CODE,
                JSONObject().apply {
                    put("idempotency_key", proposal.idempotencyKey)
                    put("action_type", proposal.actionType)
                    put("reason", t.message ?: t.javaClass.simpleName)
                    // 抛异常意味着处理器明确知道自己失败了 → 可以重试。
                    // 如果是超时/进程被杀，根本不会走到这里，而是留下"有 intent 无 result"。
                    put("retryable", true)
                },
                refs = listOf(intent.id),
            )
            Outcome.Failed(t.message ?: t.javaClass.simpleName, retryable = true)
        }
    }

    /** 拒绝并记安全事件。绕过了前两层还来调执行器，这件事本身要留痕。 */
    private fun reject(proposal: Proposal, reason: String): Outcome {
        log.append(
            EvTypes.SYS_VIOLATION, EvActors.CODE,
            JSONObject().apply {
                put("rule", "executor.without-verdict")
                put("detail", reason)
                put("action_type", proposal.actionType)
                put("idempotency_key", proposal.idempotencyKey)
            },
        )
        return Outcome.Rejected(reason)
    }

    /**
     * 恢复：找出"有 intent 无 result"的动作。
     *
     * 这些不是失败，是**未知**——进程可能在外部副作用发生之后、写 result 之前死掉。
     * 调用方应当把每一条转成 `unknown` 事件交给人看，而不是重试。
     */
    fun findUnknownActions(): List<String> {
        val events = log.readAll()
        val settled = HashSet<String>()
        for (e in events) {
            if (e.type == ACTION_RESULT || e.type == ACTION_FAILED) {
                settled += e.payload.optString("idempotency_key", "")
            }
        }
        return events
            .filter { it.type == ACTION_INTENT }
            .map { it.payload.optString("idempotency_key", "") }
            .filter { it.isNotEmpty() && it !in settled }
            .distinct()
    }

    private companion object {
        const val ACTION_INTENT = "action.intent"
        const val ACTION_RESULT = "action.result"
        const val ACTION_FAILED = "action.failed"
    }
}

/** 事件流的读取便捷方法：判定事件用。 */
internal fun EventLog.verdicts(): List<EvEvent> =
    readAll().filter { it.type.startsWith(EvTypes.VERDICT) }
