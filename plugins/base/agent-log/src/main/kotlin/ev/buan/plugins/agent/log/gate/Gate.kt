package ev.buan.plugins.agent.log.gate

import org.json.JSONObject

/**
 * 权限层：门禁（`/root/specs/04-gate-executor.md` §2）。
 *
 * 只回答一个问题：**「它有没有资格做这件事」**。不判断合理性——那是审核层的事。
 * 顺序按成本排：便宜的先做，所以形状不对的提案连查表都不用查。
 *
 * ```
 * P1 schema 校验       形状不对直接拒
 * P2 能力清单 + 授权     capability × scope × ttl
 * P3 平台硬策略         不可绕，没有"仍然允许"
 * P4 配额              类型化配额 + 时间窗
 * ```
 *
 * **fail-closed**：任何一段出问题（包括内部异常）都返回 deny。出不了这张门，就不进审核层。
 */
class Gate(
    private val grants: List<Grant>,
    private val hardDenies: List<HardDeny> = emptyList(),
    private val quota: QuotaPolicy = QuotaPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 授权是四元组里的三元（能力 × 范围 × 时效）。条件匹配留给具体动作类型。 */
    data class Grant(
        val capability: String,
        /** 范围：路径前缀、包名、目标标识。空串表示不限范围。 */
        val scope: String = "",
        /** 到期时间（epoch ms）；[NEVER] 表示不过期。一次同意 ≠ 永久通行，所以默认给个期限。 */
        val expiresAt: Long = NEVER,
    ) {
        companion object {
            const val NEVER = Long.MAX_VALUE
        }
    }

    /** 平台硬策略：无论谁批过都不行。规格 §2 P3「不可绕，没有"仍然允许"」。 */
    data class HardDeny(
        val actionType: String,
        val reason: String,
        /** 命中后才拒绝的参数条件；null 表示该类型全部拒绝。 */
        val paramEquals: Pair<String, String>? = null,
    )

    /** 类型化配额 + 时间窗。v0 只做"每类型每窗口若干次"。 */
    data class QuotaPolicy(
        val windowMs: Long = 60_000,
        val perTypeLimit: Int = 60,
    )

    sealed interface Result {
        data object Allowed : Result
        data class Denied(val stage: Stage, val reason: String, val violation: Boolean = false) : Result

        enum class Stage { P1_SCHEMA, P2_CAPABILITY, P3_HARD_POLICY, P4_QUOTA, INTERNAL }
    }

    private val hits = HashMap<String, MutableList<Long>>()

    /**
     * 判定。纯函数式地看 proposals，但 P4 有状态（配额窗口）——调用方需保证单线程，
     * 与事件流的单写者假设一致。
     */
    fun check(proposal: Proposal): Result {
        return try {
            schema(proposal)
                ?: capability(proposal)
                ?: hardPolicy(proposal)
                ?: quota(proposal)
                ?: Result.Allowed
        } catch (t: Throwable) {
            // 内部异常也是 deny：门禁坏掉的时候，默认必须是不放行。
            Result.Denied(Result.Stage.INTERNAL, "权限层内部异常：${t.javaClass.simpleName}")
        }
    }

    // ---- P1 形状 ----

    private fun schema(p: Proposal): Result.Denied? {
        if (p.actionType.isBlank()) return deny(Result.Stage.P1_SCHEMA, "action.type 为空")
        if (p.intent.isBlank()) return deny(Result.Stage.P1_SCHEMA, "intent 为空——不知道为什么要做的事不能做")
        if (p.idempotencyKey.isBlank()) return deny(Result.Stage.P1_SCHEMA, "idempotency_key 为空，无法保证不重复执行")
        // 规格 02 §1：evidence_refs 非空（sys.* 除外）。没有来源的提案等于凭空出现的命令。
        if (!p.actionType.startsWith("sys.") && p.evidenceRefs.isEmpty()) {
            return deny(Result.Stage.P1_SCHEMA, "evidence_refs 为空——没有来源的提案不能执行")
        }
        return null
    }

    // ---- P2 资格 ----

    private fun capability(p: Proposal): Result.Denied? {
        val now = clock()
        val usable = grants.filter { it.capability == p.actionType && it.expiresAt > now }
        if (usable.isEmpty()) {
            val expired = grants.any { it.capability == p.actionType }
            return deny(
                Result.Stage.P2_CAPABILITY,
                if (expired) "能力 ${p.actionType} 的授权已过期" else "没有能力 ${p.actionType} 的授权",
            )
        }
        val scope = p.scope
        val matched = usable.any { g -> covers(g.scope, scope) }
        if (!matched) {
            // 前缀匹配必须卡在目录边界上。朴素的 startsWith 会把 `/root/tmp-other` 判成在
            // `/root/tmp` 范围内——后缀一换就逃逸，这是权限系统最经典的越权方式之一。
            return deny(
                Result.Stage.P2_CAPABILITY,
                "能力 ${p.actionType} 的授权范围不覆盖 $scope",
            )
        }
        return null
    }

    /**
     * 授权范围是否覆盖目标。
     *
     * 空范围 = 不限。否则要求**相等**或**以 `范围/` 开头**（确保是它的子路径）。
     * 不用裸 startsWith：那会把 `/root/tmp-other` 当成 `/root/tmp` 的子路径。
     */
    private fun covers(grantScope: String, target: String): Boolean {
        if (grantScope.isEmpty()) return true
        if (target == grantScope) return true
        val prefix = if (grantScope.endsWith("/")) grantScope else grantScope + "/"
        return target.startsWith(prefix)
    }

    // ---- P3 平台硬策略 ----

    private fun hardPolicy(p: Proposal): Result.Denied? {
        for (rule in hardDenies) {
            if (rule.actionType != p.actionType) continue
            val condition = rule.paramEquals
            if (condition == null || p.params.optString(condition.first) == condition.second) {
                return deny(Result.Stage.P3_HARD_POLICY, "平台硬策略：${rule.reason}")
            }
        }
        return null
    }

    // ---- P4 配额 ----

    private fun quota(p: Proposal): Result.Denied? {
        val now = clock()
        val bucket = hits.getOrPut(p.actionType) { mutableListOf() }
        bucket.removeAll { now - it > quota.windowMs }
        if (bucket.size >= quota.perTypeLimit) {
            return deny(
                Result.Stage.P4_QUOTA,
                "配额用尽：${p.actionType} 在 ${quota.windowMs}ms 内已执行 ${bucket.size} 次",
            )
        }
        bucket += now
        return null
    }

    private fun deny(stage: Result.Stage, reason: String) = Result.Denied(stage, reason)
}

/**
 * 提案：agent 唯一的产出方式（`02-schemas.md` §1）。
 *
 * 这里是它的 Kotlin 形态——刻意与落盘的 JSON 一一对应，转换函数写在 [toEventPayload]，
 * 不做双向自动映射（反射式序列化在字段改名时会静默丢字段）。
 */
data class Proposal(
    val actionType: String,
    val params: JSONObject,
    val intent: String,
    val expectedEffect: String,
    val idempotencyKey: String,
    val evidenceRefs: List<String>,
    /** 权限层 P2 用来匹配授权范围的目标标识：路径、包名、地址等。 */
    val scope: String = "",
    val actor: String = "agent:unknown",
    val ts: Long = System.currentTimeMillis(),
) {
    fun toEventPayload(): JSONObject = JSONObject().apply {
        put("actor", actor)
        put("action", JSONObject().put("type", actionType).put("params", params))
        put("intent", intent)
        put("expected_effect", expectedEffect)
        put("idempotency_key", idempotencyKey)
        put("evidence_refs", org.json.JSONArray(evidenceRefs))
        if (scope.isNotEmpty()) put("scope", scope)
    }
}
