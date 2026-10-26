package ev.buan.plugins.agent.log.gate

import org.json.JSONObject

/**
 * 审核层：值班经理（`/root/specs/04-gate-executor.md` §3、`03-decision-layer.md`）。
 *
 * 只审**已经过权限层**的提案。回答的是「这件事该不该做」，不授予资格。
 *
 * 三段式（03 §1）：
 * ```
 * ① 快路径：规则表 → 零成本 / 零延迟 / 确定
 * ② 慢路径：规则覆盖不到 → escalate → 人（v0 里 LLM 不接，直接给人）
 * ③ 沉淀：判定回写成新规则（本类提供 addRule，谁来提议属于上层）
 * ```
 *
 * **不允许"默认放行"**：`escalate` 超时未决 → `deny`。规格反复强调这一条，
 * 因为"等着等着就干了"是这类系统最典型的翻车方式。
 */
class Review(
    private val rules: List<Rule> = emptyList(),
) {

    /**
     * 一条判定规则（03 §2）。
     *
     * [when] 必须是**结构化条件**，不是自然语言——自然语言的规则没法机器判定，
     * 而判定层存在的意义就是"绝大多数判断不叫 LLM"。人话放 [scopeNote]。
     */
    data class Rule(
        val ruleId: String,
        val version: Int = 1,
        val state: State = State.ACTIVE,
        val priority: Int = 0,
        val whenActionType: String,
        /** 参数前缀匹配，如 `path` → `/root/`。空表示不限定。 */
        val whenParam: String = "",
        val whenParamPrefix: String = "",
        val decision: String,
        val scopeNote: String = "",
        /** 非空：这条规则凭什么存在，必须能追到判定/事件。 */
        val sourceRefs: List<String>,
    ) {
        enum class State { ACTIVE, DOUBTFUL, RETIRED }

        fun matches(p: Proposal): Boolean {
            if (state != State.ACTIVE) return false
            if (whenActionType.isNotEmpty() && whenActionType != p.actionType) return false
            if (whenParam.isNotEmpty()) {
                val value = p.params.optString(whenParam, "")
                if (!value.startsWith(whenParamPrefix)) return false
            }
            return true
        }
    }

    /** 谁来判的。必须如实标注——审计与成本核算都靠它（02 §2）。 */
    enum class By { CODE, LLM, USER }

    sealed interface Verdict {
        val by: By
        val ruleRefs: List<String>
        val reasons: List<String>

        data class Allow(
            override val by: By = By.CODE,
            override val ruleRefs: List<String> = emptyList(),
            override val reasons: List<String> = emptyList(),
        ) : Verdict

        data class Deny(
            override val by: By = By.CODE,
            override val ruleRefs: List<String> = emptyList(),
            override val reasons: List<String> = emptyList(),
        ) : Verdict

        /** 规则覆盖不到。**这不是放行**，调用方必须转成人审或超时 deny。 */
        data class Escalate(
            override val by: By = By.CODE,
            override val ruleRefs: List<String> = emptyList(),
            override val reasons: List<String> = emptyList(),
        ) : Verdict

        data class Modify(
            val modifiedAction: JSONObject,
            override val by: By = By.CODE,
            override val ruleRefs: List<String> = emptyList(),
            override val reasons: List<String> = emptyList(),
        ) : Verdict
    }

    /**
     * 判定。
     *
     * 规则冲突时：**priority 大的赢；同 priority 时 deny 压 allow**（03 §2）。
     * 后者是刻意的悲观默认——两条规则打架时，宁可少做一件事。
     */
    fun decide(p: Proposal): Verdict {
        val matched = rules.filter { it.matches(p) }
        if (matched.isEmpty()) {
            return Verdict.Escalate(reasons = listOf("没有规则覆盖 ${p.actionType}，需要人工决定"))
        }

        // 先按 priority 降序；同优先级里 deny 排前面。
        val ordered = matched.sortedWith(
            compareByDescending<Rule> { it.priority }.thenByDescending { it.decision == "deny" },
        )
        val winner = ordered.first()
        val refs = matched.map { it.ruleId }.distinct()

        return when (winner.decision) {
            "allow" -> Verdict.Allow(ruleRefs = refs, reasons = listOf("规则 ${winner.ruleId} 放行"))
            "deny" -> Verdict.Deny(ruleRefs = refs, reasons = listOf("规则 ${winner.ruleId} 拒绝"))
            "modify" -> Verdict.Escalate(
                ruleRefs = refs,
                reasons = listOf("规则 ${winner.ruleId} 要求缩减影响，但 v0 还没有动作级缩降实现"),
            )
            else -> Verdict.Escalate(
                ruleRefs = refs,
                reasons = listOf("规则 ${winner.ruleId} 的判定值 '${winner.decision}' 无法识别"),
            )
        }
    }

    /**
     * 沉淀：登记一条新规则。
     *
     * 规格 03 §6：**LLM 只能提议，代码或用户批准后才生效**。这个函数代表"已批准"，
     * 所以调用方必须先经过批准，不能由模型直接调。
     *
     * 门槛也在这里守：`sourceRefs` 为空直接拒绝——一条说不出自己凭什么存在的规则，
     * 迟早会变成没人敢删的历史垃圾（03 §4 描述的那种慢性病）。
     */
    fun addRule(rule: Rule): Result<Unit> {
        if (rule.sourceRefs.isEmpty()) {
            return Result.failure(IllegalArgumentException("规则 ${rule.ruleId} 没有 sourceRefs：无来源的规则不许登记"))
        }
        return Result.success(Unit)
    }

    companion object {
        /**
         * v0 的初始规则集。
         *
         * 刻意很少：每一条都要有明确的来源（这里来源是规格文档本身），
         * 免得一上来就堆一堆没人验证过的"经验"。
         */
        fun v0(): Review = Review(
            rules = listOf(
                Rule(
                    ruleId = "rule_read_workspace",
                    priority = 100,
                    whenActionType = "fs.read",
                    whenParam = "path",
                    whenParamPrefix = "/root/",
                    decision = "allow",
                    scopeNote = "只读工作区，无副作用",
                    sourceRefs = listOf("spec:04-gate-executor"),
                ),
                Rule(
                    ruleId = "rule_deny_write_system",
                    priority = 200,
                    whenActionType = "fs.write",
                    whenParam = "path",
                    whenParamPrefix = "/system/",
                    decision = "deny",
                    scopeNote = "系统分区不允许写入",
                    sourceRefs = listOf("spec:04-gate-executor"),
                ),
            ),
        )
    }
}
