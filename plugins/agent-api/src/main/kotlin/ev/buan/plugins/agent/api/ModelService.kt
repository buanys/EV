package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * A chat-completion provider published by a model plugin.
 *
 * Deliberately narrow: one completion call plus the models it currently serves. HTTP, credentials
 * and storage stay inside the provider plugin; consumers only see this shape.
 */
interface ModelService {
    /** Models this provider can serve right now, as its own configuration defines them. */
    val models: List<ModelRef>

    /**
     * Runs one completion for [messages] on [model].
     *
     * [tools] are capability declarations the model may answer with
     * [AgentEvent.Kind.TOOL_CALL] events; executing them is the caller's job, not the provider's.
     * The returned [AgentReply.message] is the assistant message.
     */
    suspend fun complete(
        model: ModelRef,
        messages: List<AgentMessage>,
        tools: List<ToolSpec> = emptyList(),
    ): AgentReply

    companion object {
        val Key = ServiceKey<ModelService>("agent/model")
    }
}
