package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * The conversation brain, published by an agent plugin into `ServiceDirectory`.
 *
 * The host does not know this contract: consumers look the service up optionally, so a missing
 * brain is a normal "not connected" state rather than a failed dependency. This module only
 * carries the shape; providing the implementation is a plugin's job.
 */
interface AgentService {
    /**
     * False while a capability the brain needs is missing - no model provider published, or the
     * provider serves no model. Callers keep their "not connected" state instead of showing an
     * error.
     */
    val ready: Boolean

    /**
     * Appends [text] to the conversation [sessionId] and returns the assistant turn.
     *
     * [model] selects a model from the provider; null means "whatever the brain is configured to
     * use". [systemPrompt] is an optional role/persona instruction for this turn: the brain places
     * it ahead of stored history and the current user message, and never persists it, so it cannot
     * leak into the conversation history or into later turns. A blank value is treated as null.
     *
     * A brain that is not [ready] still answers with a reply explaining what is missing, so a
     * caller that ignored [ready] cannot crash the host.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef? = null,
        systemPrompt: String? = null,
    ): AgentReply

    companion object {
        val Key = ServiceKey<AgentService>("agent/service")
    }
}
