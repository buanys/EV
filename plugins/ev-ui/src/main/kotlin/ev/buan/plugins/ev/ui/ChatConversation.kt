package ev.buan.plugins.ev.ui

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.StepState
import java.util.UUID

/**
 * One conversation: the drawer entry plus the bubbles it holds.
 *
 * [id] identifies the session both in the drawer and as the agent's conversation key, so it is
 * written to storage and restored unchanged; [updatedAt] orders the drawer and picks the session the
 * page opens on. [characterId] and [skillId] name the local character and skill whose text this
 * session sends with every turn - null means "none selected", and an id the library no longer has
 * behaves the same way.
 */
internal data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long,
    val characterId: String? = null,
    val skillId: String? = null,
)

/** The drawer label of a conversation the user has not typed into yet. */
internal const val DEFAULT_SESSION_TITLE = "新的对话"

/** Conversation state that outlives one frame: a bubble needs an identity the list can trust.
 *
 * Sessions are edited in place (regenerate cuts the tail, delete removes one bubble), so index-based
 * identity would make Compose reuse the wrong item state. [id] is minted once per message and is used
 * as the LazyColumn key; [events] is the agent turn that produced an assistant bubble, kept so tool
 * steps stay visible after the reply (without them, a regenerated or scrolled-away reply could not
 * show what it did).
 */
internal data class ChatMessage(
    val id: String,
    val text: String,
    val fromUser: Boolean,
    val events: List<AgentEvent> = emptyList(),
    /**
     * Why the agent turn ended, when it did not end because the model was done. Rendered under the
     * bubble; keeping it on the message means a reloaded session still explains itself.
     */
    val stopNote: String? = null,
    /**
     * Per-round summary of the tool work behind this reply.
     *
     * Only counts and one error line are stored: keeping every tool argument and result would make
     * a session file grow without bound, and the point of the summary is to answer "what happened"
     * at a glance, not to replay the payloads. Empty for messages written before this existed,
     * which keeps old transcripts rendering through the legacy `events` path.
     */
    val traces: List<TraceSummary> = emptyList(),
)

/**
 * What one round did, as stored in the transcript.
 *
 * [firstError] is one truncated line: enough to recognise the failure on reload, small enough that
 * a hundred failed rounds still cost a few kilobytes.
 */
internal data class TraceSummary(
    val round: Int,
    val toolCalls: Int,
    val failed: Int,
    val elapsedMs: Long,
    val firstError: String?,
)

/** The stored form of a live round; the reducer's state is the single source of these numbers. */
internal fun RoundTrace.toSummary(): TraceSummary = TraceSummary(
    round = round,
    toolCalls = executed,
    failed = failed,
    elapsedMs = steps.sumOf { it.elapsedMs ?: 0L },
    firstError = steps
        .firstOrNull { it.state == StepState.Failed }
        ?.preview
        ?.lineSequence()
        ?.firstOrNull()
        ?.take(FIRST_ERROR_CHARS),
)

/** One line, bounded: the whole point is that the stored trace stays tiny. */
internal const val FIRST_ERROR_CHARS = 100

/** A string that is stable for the lifetime of the message, however the list is reordered. */
internal fun newMessageId(): String = UUID.randomUUID().toString()

/** The user's turn: no events, the reply that follows owns them. */
internal fun userMessage(text: String): ChatMessage =
    ChatMessage(id = newMessageId(), text = text, fromUser = true)

/**
 * An assistant turn. [events] is whatever the agent reported: the "not connected" placeholder and
 * the failure text are UI states, so they carry none.
 */
internal fun assistantMessage(
    text: String,
    events: List<AgentEvent> = emptyList(),
    stopNote: String? = null,
    traces: List<TraceSummary> = emptyList(),
): ChatMessage = ChatMessage(
    id = newMessageId(),
    text = text,
    fromUser = false,
    events = events,
    stopNote = stopNote,
    traces = traces,
)

/**
 * Human wording for a non-FINISHED stop. FINISHED is the only reason that needs no line: the model
 * decided it was done, and saying so would just add noise under every answer.
 */
internal fun stopNoteOf(stop: AgentStop?): String? {
    if (stop == null || stop.reason == AgentStop.Reason.FINISHED) return null
    val reason = when (stop.reason) {
        AgentStop.Reason.ROUNDS -> "轮数用尽"
        AgentStop.Reason.TOOL_BUDGET -> "工具调用次数用尽"
        AgentStop.Reason.DEADLINE -> "超时"
        AgentStop.Reason.NO_PROGRESS -> "连续两轮没有进展"
        AgentStop.Reason.REPEATED_CALLS -> "重复调用同一工具"
        AgentStop.Reason.ERROR -> "出错"
        AgentStop.Reason.FINISHED -> return null
    }
    return "已停止：$reason（${stop.rounds} 轮）"
}

/** Removes exactly the bubble with [id]; an unknown id leaves the conversation untouched. */
internal fun withoutMessage(messages: List<ChatMessage>, id: String): List<ChatMessage> =
    messages.filterNot { it.id == id }

/**
 * What a "regenerate" needs: the history cut back to the user turn that produced [assistantId] and
 * the prompt to send again.
 *
 * The user turn is reused rather than re-appended, so regenerating never adds a second question
 * bubble. Null when the id is unknown, names a user message, or no user turn precedes it.
 */
internal data class Regeneration(val messages: List<ChatMessage>, val prompt: String)

internal fun regenerationOf(messages: List<ChatMessage>, assistantId: String): Regeneration? {
    val assistantIndex = messages.indexOfFirst { it.id == assistantId }
    if (assistantIndex < 0 || messages[assistantIndex].fromUser || assistantIndex == 0) return null
    val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].fromUser } ?: return null
    return Regeneration(messages = messages.take(userIndex + 1), prompt = messages[userIndex].text)
}

/** One agent tool step: the request the model made and what the tool answered. */
internal data class ToolStep(
    val toolName: String?,
    /**
     * TOOL_CALL arguments, complete. The panel renders a [preview] and expands to the full text
     * on tap, so nothing the tool saw is hidden from the human reviewing it.
     */
    val arguments: String,
    /** TOOL_RESULT text, also complete; null while no result answered the call. */
    val result: String?,
)

/**
 * The tool work of one turn, ready to render.
 *
 * [thinkingCount] counts model rounds - a run of adjacent TOOL_CALL events is one round, because the
 * brain appends one completion's events together and a model may request several tools at once -
 * while [toolCount] counts distinct tools, which is what the caption claims.
 */
internal data class ToolSteps(
    val thinkingCount: Int,
    val toolCount: Int,
    val steps: List<ToolStep>,
) {
    /** An empty step list is not rendered at all: a plain answer must not grow a "0 tools" row. */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** The caption Ev uses; the one place the numbers are turned into text. */
    val label: String get() = "思考 $thinkingCount 次 · 调用 $toolCount 个工具"
}

/** Long enough to recognize a call in the collapsed card, short enough to stay one glance. */
internal const val TOOL_TEXT_LIMIT = 240

/**
 * The one-line glance at a longer text: trimmed, capped at [maxChars], ended with an ellipsis
 * when anything was cut. The full text always stays on the [ToolStep]; this is display-only.
 */
internal fun preview(text: String?, maxChars: Int = TOOL_TEXT_LIMIT): String {
    val limit = maxChars.coerceAtLeast(2)
    val trimmed = text?.trim().orEmpty()
    if (trimmed.length <= limit) return trimmed
    return trimmed.take(limit - 1).trimEnd() + "…"
}

/**
 * Flattens a turn's [events] into the steps a bubble shows under its text.
 *
 * Results are paired with their call by [AgentEvent.toolCallId], falling back to the tool name and
 * arrival order for providers that do not issue ids. A result with no call still becomes a step, so
 * a degraded tool (unknown name, missing registry) is visible instead of silently dropped. Steps
 * keep the complete arguments and results; trimming for display is the panel's job ([preview]).
 *
 * This is a pure function of the events: it renders, it does not decide anything, so a reply that
 * is merely text yields an empty [ToolSteps].
 */
internal fun toolStepsOf(events: List<AgentEvent>): ToolSteps {
    val steps = events
        .filter { it.kind == AgentEvent.Kind.TOOL_CALL }
        .map { call ->
            val name = call.toolName?.takeIf { it.isNotBlank() }
            MutableStep(name, call.toolCallId, arguments = call.argumentsJson?.trim().orEmpty(), result = null)
        }
        .toMutableList()

    events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.forEach { result ->
        val name = result.toolName?.takeIf { it.isNotBlank() }
        val byId = result.toolCallId?.let { id ->
            steps.indexOfFirst { it.result == null && it.callId == id }
        } ?: -1
        val index = if (byId >= 0) byId else steps.indexOfFirst { it.result == null && it.name == name }
        if (index >= 0) {
            steps[index] = steps[index].copy(result = result.text?.trim().orEmpty())
        } else {
            steps += MutableStep(name, callId = null, arguments = "", result = result.text?.trim().orEmpty())
        }
    }

    return ToolSteps(
        thinkingCount = thinkingRounds(events),
        toolCount = steps.mapNotNull { it.name }.distinct().size,
        steps = steps.map { ToolStep(it.name, it.arguments, it.result) },
    )
}

private data class MutableStep(
    val name: String?,
    val callId: String?,
    val arguments: String,
    val result: String?,
)

/** Rounds are maximal runs of adjacent TOOL_CALL events; TEXT/TOOL_RESULT/ERROR end a run. */
private fun thinkingRounds(events: List<AgentEvent>): Int {
    var rounds = 0
    var previousWasCall = false
    events.forEach { event ->
        val isCall = event.kind == AgentEvent.Kind.TOOL_CALL
        if (isCall && !previousWasCall) rounds++
        previousWasCall = isCall
    }
    return rounds
}
