package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolRegistry

/**
 * The capability plugins a brain can compose, as seen at one instant.
 *
 * Each field is nullable on purpose: model, tools, memory, the approval gate and the prompt
 * baseline are separate containers that the user may install, enable, disable or swap
 * independently. Null means "not available right now", which the brain degrades around instead of
 * treating as a broken dependency. A missing [approval] is the strictest case: dangerous tools
 * refuse to run at all. A missing [prompt] is the mildest: the brain sends its built-in baseline.
 */
data class BrainCapabilities(
    val model: ModelService?,
    val tools: ToolRegistry?,
    val memory: MemoryService?,
    val approval: ApprovalService? = null,
    val prompt: SystemPromptService? = null,
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
