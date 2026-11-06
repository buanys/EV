package ev.buan.plugins.agent.api

/**
 * Token accounting reported by a model provider for one completion.
 *
 * Every field is nullable on purpose: OpenAI-compatible servers disagree on what they return.
 * Some omit `usage` entirely (streaming without `include_usage`), some report only `total_tokens`,
 * and a failed turn may have no numbers at all. A null field means "the provider did not report
 * it", never zero - a UI must render it as unknown instead of showing a wrong count.
 *
 * The brain aggregates usage across the model calls of one turn (a tool turn may make several);
 * downstream consumers can then show prompt/completion/total for the whole turn.
 */
data class AgentUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)
