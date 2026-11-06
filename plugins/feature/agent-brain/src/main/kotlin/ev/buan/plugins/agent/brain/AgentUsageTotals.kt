package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentUsage

/**
 * Aggregates the token usage of every model call in one agent turn.
 *
 * A tool turn makes several completions. `promptTokens` comes from the last call that reported
 * usage (its prompt contains the whole conversation plus the tool results), while
 * `completionTokens` and `totalTokens` are summed over the calls. Calls that reported nothing are
 * skipped; a turn in which no call reported usage has no usage at all (null), never a row of
 * zeroes that would look like real accounting.
 */
internal object AgentUsageTotals {
    fun aggregate(usages: List<AgentUsage?>): AgentUsage? {
        val reported = usages.filterNotNull()
        if (reported.isEmpty()) return null
        return AgentUsage(
            promptTokens = reported.last().promptTokens,
            completionTokens = sumOrNull(reported.map { it.completionTokens }),
            totalTokens = sumOrNull(reported.map { it.totalTokens }),
        )
    }

    /** Null when nobody reported the field, so it stays distinguishable from a reported zero. */
    private fun sumOrNull(values: List<Int?>): Int? {
        val known = values.filterNotNull()
        return if (known.isEmpty()) null else known.sum()
    }
}
