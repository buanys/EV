package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.ApprovalPolicy
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.api.HudService

/**
 * The capability plugins a brain can compose, as seen at one instant.
 *
 * Each field is nullable on purpose: model, tools, memory, the approval gate and the prompt
 * baseline are separate containers that the user may install, enable, disable or swap
 * independently. Null means "not available right now", which the brain degrades around instead of
 * treating as a broken dependency. A missing [approval] is the strictest case: dangerous tools
 * refuse to run at all. A missing [prompt] is the mildest: the brain sends its built-in baseline.
 *
 * ## 历史的两个来源
 *
 * [sessionLog] 是权威，[memory] 是派生物。两者都在时写两份、读前者——这样日志插件被禁用
 * 的机器不会退回"没有历史"，而启用之后新会话立刻可回放。两个都没有时退化成单轮对话，
 * 与这个类之前的语义一致。
 */
data class BrainCapabilities(
    val model: ModelService?,
    val tools: ToolRegistry?,
    val memory: MemoryService?,
    val approval: ApprovalService? = null,
    val prompt: SystemPromptService? = null,
    /** 屏幕上的进度提示层。null 表示宿主没有提供——那只是少了一层可见性，不影响执行。 */
    val hud: HudService? = null,
    /**
     * 会话事件流：模型可见历史的真相源。
     *
     * null 表示没装 agent-log，或它还没激活；此时 [memory] 的 history 是唯一历史。
     */
    val sessionLog: SessionLog? = null,
    /**
     * 高危工具的预判策略，把"要不要问人"从 UI 里拆出来。
     *
     * null 表示没有 policy，一律走 [ApprovalService]（没有 gate 就 fail-closed），
     * 这就是 policy 出现之前的行为。
     */
    val policy: ApprovalPolicy? = null,
)

/**
 * Resolves [BrainCapabilities] at call time rather than at activation.
 *
 * A plugin enabled after this brain (or after the chat page was opened) must become visible, and a
 * plugin disabled afterwards must disappear - caching the three lookups would freeze whichever set
 * happened to be published first.
 */
fun interface CapabilityProvider {
    fun current(): BrainCapabilities
}
