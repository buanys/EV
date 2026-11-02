package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * The agent's behaviour baseline, assembled by a prompt plugin.
 *
 * Before this contract existed the brain took one optional string from the caller and sent it as
 * the whole system message ([ev.buan.plugins.agent.api.AgentService.send]); with no character
 * selected the agent had no instructions at all. The layer list replaces that string with an
 * ordered, inspectable set: each layer has an `id` so a log can say which one was used, and a
 * `priority` so the brain knows what to drop when the prompt budget is tight.
 *
 * Contract rules:
 * - [SystemPromptService.layers] must be **synchronous and deterministic**. The same
 *   [PromptContext] must produce the same layers, because a prompt that cannot be reproduced
 *   cannot be debugged.
 * - The brain sorts by [PromptLayer.priority] descending, ties broken by [PromptLayer.id], and
 *   drops whole layers from the bottom when over budget. A layer is never truncated: half a rule
 *   is worse than no rule.
 * - A missing, throwing or empty implementation is not an error. The brain falls back to its own
 *   built-in baseline, so a prompt plugin can never take a turn down.
 */
fun interface SystemPromptService {
    /** Layers for one model request, in no particular order. */
    fun layers(ctx: PromptContext): List<PromptLayer>

    companion object {
        val Key = ServiceKey<SystemPromptService>("agent/system-prompt")
    }
}

/**
 * Everything a prompt plugin may know about the request it is writing for.
 *
 * Deliberately small: the session id and the clock. Device facts (battery, foreground app,
 * network) are reachable through `host/device-context` by any plugin that wants them, and putting
 * them here would make every prompt plugin depend on that service too.
 */
data class PromptContext(
    val sessionId: String,
    /** Wall clock at the start of the turn, so the `env` layer can state the current time. */
    val nowMillis: Long,
)

/**
 * One block of the system message.
 *
 * [id] is stable vocabulary, not prose: `identity`, `stop`, `tools`, `style`, `persona`, `env`.
 * The brain adds the `persona` layer itself from the caller's character prompt, so a plugin that
 * also emits that id gets both - use another id rather than fighting it.
 */
data class PromptLayer(
    val id: String,
    /** Short human label for logs and a future "which layers ran" panel. */
    val title: String,
    val body: String,
    /** Larger wins; the brain drops the smallest layers first when over budget. */
    val priority: Int,
    /**
     * Marks content that changes on its own clock (the current time, battery, foreground app).
     *
     * A volatile layer is **not** part of the system message. Prefix caching compares a request
     * from its first token, and the system message is that first token: one changing minute there
     * invalidates the cache for the whole conversation history behind it. The brain therefore
     * appends volatile layers to the tail of the request instead, where a change costs only the
     * tokens after it.
     */
    val volatile: Boolean = false,
)
