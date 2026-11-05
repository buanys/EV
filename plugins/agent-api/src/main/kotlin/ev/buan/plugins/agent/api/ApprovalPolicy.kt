package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * 高危工具要不要问人的**预判**，与"怎么问"分开。
 *
 * 为什么需要它：审批的实现今天住在 ev-ui 里，于是"没有 UI 就没有审批腿"，
 * 任何 headless / 远程 / 后台形态都被迫对所有高危工具 fail-closed。
 * 这条策略把判断从展示里拆出来——装一个 policy（比如"工作区内的写操作直接放行"），
 * 不装 UI 也能跑；不装 policy 则退回旧行为，一律需要人。
 *
 * 判定只能比现状**更严或相同**，不能更松：`ASK` 是无 policy 时的默认值，
 * policy 的作用是把一部分 `ASK` 变成 `ALLOW`（明确安全）或 `DENY`（明确禁止）。
 */
interface ApprovalPolicy {
    /**
     * 对一次 `ToolSpec.dangerous` 调用的预判。
     *
     * 只在工具被标记为 dangerous 时调用；普通工具根本不会走到这里。
     * 实现应当快速且无副作用——它可能在同一轮里被调用几十次。
     */
    fun judge(toolName: String, argumentsJson: String): Verdict

    enum class Verdict {
        /** 明确允许，不问人。只在参数确实安全时使用——这是唯一会跳过人类的一档。 */
        ALLOW,

        /** 明确禁止，不问人。拒绝理由由 policy 自己写进工具结果。 */
        DENY,

        /** 仍需人类决定。没有 [ApprovalService] 时保持 fail-closed。 */
        ASK,
    }

    companion object {
        val Key = ServiceKey<ApprovalPolicy>("agent/approval/policy")
    }
}
