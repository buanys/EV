package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole

/**
 * Bounds the model-facing view of a session's history.
 *
 * The stored memory stays a complete append-only log - the model's view is what gets trimmed, and
 * the trim is tool-call aware: history is split into turns at each [AgentRole.USER] boundary, so an
 * assistant `tool_calls` message and the [AgentRole.TOOL] results answering it are kept or dropped
 * as one group. Sending one without the other would produce an invalid wire conversation that
 * strict providers reject.
 *
 * Newest turns win: trimming walks from the most recent turn backwards and always keeps at least
 * the latest turn, even when it alone exceeds the budget.
 */
internal object HistoryTrim {
    fun trim(
        history: List<AgentMessage>,
        maxTurns: Int,
        maxChars: Int,
    ): List<AgentMessage> {
        if (history.isEmpty()) return history
        val turns = splitTurns(history)
        val kept = ArrayDeque<List<AgentMessage>>()
        var chars = 0
        for (turn in turns.asReversed()) {
            if (kept.size >= maxTurns) break
            val turnChars = turn.sumOf { it.content.length }
            if (kept.isNotEmpty() && chars + turnChars > maxChars) break
            kept.addFirst(turn)
            chars += turnChars
        }
        return kept.flatten()
    }

    /** Groups messages into turns, each starting at a USER message (any leading strays form one). */
    private fun splitTurns(history: List<AgentMessage>): List<List<AgentMessage>> {
        val turns = mutableListOf<List<AgentMessage>>()
        var current = mutableListOf<AgentMessage>()
        for (message in history) {
            if (message.role == AgentRole.USER && current.isNotEmpty()) {
                turns += current
                current = mutableListOf()
            }
            current += message
        }
        if (current.isNotEmpty()) turns += current
        return turns
    }
}

/**
 * Truncates tool output for the next model request without splitting a UTF-16 surrogate pair:
 * a lone trailing high surrogate would be invalid text on the wire.
 */
internal fun truncateUtf16Safe(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    var end = maxChars
    if (text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end)
}
