package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.PromptContext
import ev.buan.plugins.agent.api.PromptLayer
import ev.buan.plugins.agent.api.SystemPromptService

/**
 * Shaping one request: which model answers it, and what prompt goes in front of the history.
 *
 * The prompt is the only part of a turn that performs no action, so it lives apart from the loop
 * that runs actions; [AgentBrainService.turn] is what puts the two halves together. The constants
 * below travel with [composePrompt] because nothing else reads them - [MAX_ROUNDS],
 * [MAX_TOOL_CALLS] and [DEADLINE_MS] mirror [Budget]'s defaults only to describe the budget in
 * the baseline text.
 */

/** Null when the caller asked for a model this provider does not serve. */
internal fun selectModel(available: List<ModelRef>, requested: ModelRef?): ModelRef? =
    if (requested == null) available.firstOrNull()
    else available.firstOrNull { it.model == requested.model }

/**
 * The two halves of one request's prompt: the stable system message, and the volatile tail.
 *
 * [volatile] is null when no layer asked for it, which is the case for the built-in baseline -
 * a request whose tail never changes must not grow a "runtime info" section for nothing.
 */
internal data class ComposedPrompt(val system: String?, val volatile: String?)

/**
 * The prompt for one turn: the prompt plugin's layers, plus the caller's character.
 *
 * Three degradations, all deliberate:
 * - no plugin, an empty list, or a plugin that throws -> [BUILTIN_LAYERS], because a turn
 *   without a baseline is how the agent ends up giving advice instead of acting;
 * - a blank character prompt contributes nothing, it does not remove the baseline;
 * - layers that do not fit [MAX_SYSTEM_PROMPT_CHARS] are dropped whole, smallest priority
 *   first, because half a rule is worse than no rule.
 */
internal fun composePrompt(
    provider: SystemPromptService?,
    characterPrompt: String?,
    sessionId: String,
): ComposedPrompt {
    val fromPlugin = try {
        provider?.layers(PromptContext(sessionId = sessionId, nowMillis = System.currentTimeMillis()))
    } catch (error: Throwable) {
        // A broken prompt plugin must not take the turn down; the baseline still applies.
        null
    }
    val layers = if (fromPlugin.isNullOrEmpty()) BUILTIN_LAYERS else fromPlugin
    val stable = layers.filterNot { it.volatile }.toMutableList()
    characterPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
        stable += PromptLayer(PERSONA_ID, "角色", it, PERSONA_PRIORITY)
    }
    val volatile = layers.filter { it.volatile }
        .joinToString("\n") { it.body.trim() }
        .takeIf { it.isNotEmpty() }
        ?.let { "$VOLATILE_HEADER\n$it" }
    return ComposedPrompt(system = assemble(stable), volatile = volatile)
}

/** Priority descending, id ascending for ties, so the same layers always render the same way. */
private fun assemble(layers: List<PromptLayer>): String? {
    val ordered = layers.sortedWith(
        compareByDescending<PromptLayer> { it.priority }.thenBy { it.id },
    )
    val kept = mutableListOf<String>()
    var chars = 0
    for (layer in ordered) {
        val body = layer.body.trim()
        if (body.isEmpty()) continue
        val cost = body.length + PROMPT_LAYER_OVERHEAD_CHARS
        // The first layer is kept unconditionally: a prompt trimmed to nothing is worse than
        // one that overspends by a few hundred characters.
        if (kept.isNotEmpty() && chars + cost > MAX_SYSTEM_PROMPT_CHARS) continue
        kept += body
        chars += cost
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

internal fun promptChars(messages: List<AgentMessage>): Int =
    messages.sumOf { it.content.length + PER_MESSAGE_OVERHEAD_CHARS }

// ---------------------------------------------------------------- 提示词私有的常量

/** Model calls one user turn may make. A guard against loops, not a task-size estimate. */
private const val MAX_ROUNDS = 20

/** Tool invocations one user turn may make. */
private const val MAX_TOOL_CALLS = 60

/** Wall-clock budget, excluding time the human spends on approval. */
private const val DEADLINE_MS = 300_000L

/** Counting overhead per message (role markers, separators) when budgeting a prompt. */
private const val PER_MESSAGE_OVERHEAD_CHARS = 32

/** Room for the whole system message; see [composeSystemPrompt]. */
private const val MAX_SYSTEM_PROMPT_CHARS = 8_000

/** Counting a heading separator per layer when budgeting the system message. */
private const val PROMPT_LAYER_OVERHEAD_CHARS = 2

private const val PERSONA_ID = "persona"

/**
 * Header on the volatile tail, so the model can tell it apart from what the user typed.
 *
 * The tail rides inside the user message (a second system message mid-conversation is
 * rejected by some OpenAI-compatible servers), which makes this line the only thing
 * separating "the user said" from "the runtime appended".
 */
private const val VOLATILE_HEADER = "（运行时信息，不是用户输入）"

/** Between `style` (75) and `env` (40): a persona shapes tone, not behaviour. */
private const val PERSONA_PRIORITY = 60

/**
 * What the agent is told when no prompt plugin is installed.
 *
 * Short on purpose: it only has to stop the worst failure - an agent that talks about the
 * device instead of operating it, and one that never says when it is done. Everything
 * richer belongs to `agent-prompt`, which the user can replace.
 */
private val BUILTIN_LAYERS: List<PromptLayer> = listOf(
    PromptLayer(
        id = "identity",
        title = "身份与位置",
        priority = 100,
        body = "你运行在一台 Android 手机上的 agent 宿主里，可以用工具真的操作它。" +
            "说\"已完成\"之前必须真的调用过工具并看到结果；还没动手就说明打算怎么做。",
    ),
    PromptLayer(
        id = "stop",
        title = "什么时候停",
        priority = 90,
        body = "一轮对话有预算：最多 $MAX_ROUNDS 次模型往返、$MAX_TOOL_CALLS 次工具调用、" +
            "${DEADLINE_MS / 60_000} 分钟。做完就停；做不到要说清卡在哪，不要含糊收尾。",
    ),
)
