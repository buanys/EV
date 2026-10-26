package ev.buan.plugins.agent.log

import org.json.JSONObject

/**
 * 事件流的不变量校验器。规格 `/root/specs/02-schemas.md` §4 与 §7。
 *
 * 它回答的是"这条流还能不能当真相源"，而不是"业务对不对"——后者需要领域知识，
 * 属于规则表（`03-decision-layer.md`），不在这里。
 *
 * **覆盖边界（必须诚实声明）**：
 * - `modify` 只检查"有没有 modified_action"和"动作类型是否被换掉"；
 *   "参数范围有没有变大"无法在通用 schema 上判定（不同 action 的 params 语义不同），
 *   留给每个动作类型自己的校验，本类对它标记为 [Violation.severity] = WARN。
 * - 不检查语义正确性（比如 fs.write 的路径是否真该写）。
 */
object EventValidator {

    enum class Severity { /** 流已不能当真相源，必须修 */ ERROR, /** 可疑，需要人看一眼 */ WARN }

    data class Violation(
        val seq: Int,
        val rule: String,
        val detail: String,
        val severity: Severity = Severity.ERROR,
    )

    /**
     * 全流扫描。
     *
     * 返回空列表 = 这条流满足全部可机器检查的不变量。
     * 调用方应当把每条 violation 追加成 `sys.violation` 事件——违反本身也是事件。
     */
    fun validate(events: List<EvEvent>): List<Violation> {
        val out = mutableListOf<Violation>()

        out += checkSeqContinuity(events)

        val byId = HashMap<String, EvEvent>(events.size * 2)
        for (e in events) {
            // 重复 id：同一 seq 被复用过，或者两条事件撞了 id，两者都让 refs 失去确定性。
            if (byId.put(e.id, e) != null) {
                out += Violation(e.seq, "id.duplicate", "事件 id 重复：${e.id}")
            }
        }

        out += checkRefs(events, byId)
        out += checkActors(events)
        out += checkActionsHaveAllow(events, byId)
        out += checkModify(events)

        return out
    }

    // ---- 规则 1：seq 从 1 起严格 +1 ----

    private fun checkSeqContinuity(events: List<EvEvent>): List<Violation> {
        if (events.isEmpty()) return emptyList()
        val out = mutableListOf<Violation>()
        var expected = 1
        for (e in events) {
            when {
                e.seq < expected -> out += Violation(e.seq, "seq.duplicate", "序号回退或重复：期望 $expected，实际 ${e.seq}")
                e.seq > expected -> out += Violation(e.seq, "seq.gap", "序号缺口：缺少 $expected..${e.seq - 1}")
            }
            expected = e.seq + 1
        }
        return out
    }

    // ---- 规则 4：跨引用必须指向已存在且 seq 更小的事件 ----

    private fun checkRefs(events: List<EvEvent>, byId: Map<String, EvEvent>): List<Violation> {
        val out = mutableListOf<Violation>()
        for (e in events) {
            for (ref in e.refs) {
                val target = byId[ref]
                if (target == null) {
                    out += Violation(e.seq, "ref.dangling", "${e.type} 引用了不存在的事件 $ref")
                } else if (target.seq >= e.seq) {
                    out += Violation(
                        e.seq, "ref.future",
                        "${e.type} 引用了不早于自身的事件 $ref（seq ${target.seq} >= ${e.seq}）",
                    )
                }
            }
        }
        return out
    }

    // ---- 规则 6：actor 必须是已知形态 ----

    private fun checkActors(events: List<EvEvent>): List<Violation> =
        events.mapNotNull { e ->
            val ok = e.actor == EvActors.HOST || e.actor == EvActors.CODE || e.actor == EvActors.USER ||
                e.actor.startsWith(EvActors.AGENT_PREFIX)
            if (ok) null else Violation(e.seq, "actor.invalid", "未知 actor：${e.actor}", Severity.WARN)
        }

    /** 动作的**结果**事件，不是动作本身：`action.result` / `action.failed` / `action.unknown`。 */
    private val RESULT_SUFFIXES = setOf("result", "failed", "unknown")

    // ---- 规则 1（核心）：不存在没有 allow 判定的 action.* ----

    private fun checkActionsHaveAllow(events: List<EvEvent>, byId: Map<String, EvEvent>): List<Violation> {
        // 所有放行判定，按 id 索引：action 只需在 refs 里挂上其中任意一条。
        val allowVerdictIds = HashSet<String>()
        for (e in events) {
            if (!e.type.startsWith(EvTypes.VERDICT)) continue
            if (e.payload.optString("decision", "") == "allow") allowVerdictIds += e.id
        }
        val out = mutableListOf<Violation>()
        for (e in events) {
            if (!e.type.startsWith(EvTypes.ACTION)) continue
            // 结果类事件（result/failed/unknown）不是"发起动作"，它们记录的是已经发生的事，
            // 天然不会挂 allow 判定。要求它们挂判定会把每一条成功的执行都判成违规。
            val suffix = e.type.removePrefix(EvTypes.ACTION)
            if (suffix in RESULT_SUFFIXES) continue
            if (e.refs.none { it in allowVerdictIds }) {
                out += Violation(
                    e.seq, "action.without-allow",
                    "${e.type} 没有引用任何 decision=allow 的判定——无 allow 即无动作",
                )
            }
        }
        return out
    }

    // ---- 规则 5：modify 必须带 modified_action，且不得换掉动作类型 ----

    private fun checkModify(events: List<EvEvent>): List<Violation> {
        val out = mutableListOf<Violation>()
        for (e in events) {
            if (!e.type.startsWith(EvTypes.VERDICT)) continue
            if (e.payload.optString("decision", "") != "modify") continue

            val modified = e.payload.optJSONObject("modified_action")
            if (modified == null) {
                out += Violation(e.seq, "modify.without-action", "decision=modify 但没有 modified_action")
                continue
            }
            // 换掉动作类型就等于换了一件事，不是"缩减影响"。
            val originalType = findOriginalActionType(e, events)
            val modifiedType = modified.optString("type", "")
            if (originalType != null && modifiedType.isNotEmpty() && modifiedType != originalType) {
                out += Violation(
                    e.seq, "modify.type-changed",
                    "modify 把动作类型从 $originalType 改成了 $modifiedType——只允许缩小影响",
                )
            }
            // 范围是否变大：通用 schema 判不了，标记为需要动作类型自己的校验。
            out += Violation(
                e.seq, "modify.range-unchecked",
                "参数范围是否被放大未检查（需要该 action 类型自己的校验）",
                Severity.WARN,
            )
        }
        return out
    }

    /** 顺着 verdict 的 refs 找到被审的那条 proposal 的 action.type。 */
    private fun findOriginalActionType(verdict: EvEvent, events: List<EvEvent>): String? {
        val proposalId = verdict.payload.optString("proposal_id", "")
        // 只按 proposal_id 找。之前这里还有个"refs 包含自己 id"的兜底——那是无意义条件
        // （事件不可能引用自己），留着只会让读者猜它想干什么。
        val proposal = events.firstOrNull { it.id == proposalId } ?: return null
        return proposal.payload.optJSONObject("action")?.optString("type")?.takeIf { it.isNotEmpty() }
    }
}
