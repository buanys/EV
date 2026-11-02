package ev.buan.plugins.ev.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.cordis.api.ServiceDirectory

/**
 * Turn machinery: which conversation is active, which one a turn is running for, and what that turn
 * is doing right now.
 *
 * Moved out of the plugin class so the surface only renders these values and the logic that mutates
 * them lives in one place that does not need a Compose scope to be read. The Compose state types are
 * used because the surface observes them directly; there is no second copy of any of this state.
 */
internal class ChatSessionController(
    private val sessions: SnapshotStateList<ChatSession>,
    private val store: ChatSessionStore?,
    private val directory: ServiceDirectory?,
    private val characters: List<EvCharacter>,
    private val skills: List<EvSkill>,
    private val scope: CoroutineScope,
    private val newSession: () -> ChatSession,
    private val unconnectedPrefix: String,
) {
    /** The conversation this surface shows. Switching it is a UI decision, so the caller sets it. */
    var activeSessionId by mutableStateOf(sessions.first().id)
        private set

    /**
     * The session a turn is running for, shared across surfaces: two surfaces must not both send
     * into one conversation, and the typing row has to be visible in whichever one is looking.
     */
    var replyingSessionId by mutableStateOf<String?>(null)
        private set

    /** Live rounds of the in-flight turn; empty when nothing is running. */
    var liveRounds by mutableStateOf<List<RoundTrace>>(emptyList())
        private set

    /**
     * Three dots mean exactly one thing: the model is thinking. While a tool runs there is real
     * progress to show, so the dots disappear instead of animating over work that has an answer.
     */
    var awaitingModel by mutableStateOf(false)
        private set

    /** True during the wrap-up summary call, so the dots can say what they are waiting for. */
    var finalizing by mutableStateOf(false)
        private set

    /**
     * The assistant text of the round in flight, as the model writes it.
     *
     * Kept apart from [liveRounds] because it is not a step: it is the answer being typed. It is
     * cleared when the turn settles, and the stored message takes its place — the bubble the user
     * is reading must not be the source of truth for what happened.
     */
    var streamingText by mutableStateOf("")
        private set

    /** Last completed turn's token spend, shown in the model capsule's detail card. */
    var lastTurnTokens by mutableStateOf<Int?>(null)
        private set

    /** The job behind each in-flight turn, so the composer's stop button can cancel exactly one. */
    private val replyJobs = mutableMapOf<String, Job>()

    val activeSession: ChatSession
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()

    val isReplying: Boolean
        get() = replyingSessionId == activeSession.id

    /** Selects a conversation; used by the drawer and by "new conversation". */
    fun select(id: String) {
        activeSessionId = id
    }

    fun persist() {
        store?.save(sessions.toList())
    }

    fun mutate(id: String, transform: (ChatSession) -> ChatSession) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessions[index] = transform(sessions[index])
            // Every conversation edit - send, reply, delete, regenerate - ends up here.
            persist()
        }
    }

    fun append(sessionId: String, message: ChatMessage) {
        mutate(sessionId) { session ->
            session.copy(
                messages = session.messages + message,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Runs one agent turn for [prompt] and appends the assistant bubble.
     *
     * Shared by send and regenerate: a regenerated reply is a normal turn, the only difference is
     * that its user bubble already exists. The agent is looked up on every turn, never cached: the
     * brain may be installed, enabled or disabled after this page was opened.
     *
     * The turn carries the session's character as `systemPrompt` when one is selected. The prompt is
     * *not* added to the bubble text and the brain does not store it, so a role changes what the
     * model sees without touching the transcript or the memory file.
     */
    fun requestReply(sessionId: String, prompt: String) {
        if (replyingSessionId == sessionId) return
        replyingSessionId = sessionId
        liveRounds = emptyList()
        streamingText = ""
        awaitingModel = true
        finalizing = false
        val job = scope.launch {
            try {
                val agent = directory?.lookup(AgentService.Key)
                if (agent == null) {
                    delay(420)
                    append(sessionId, assistantMessage(unconnectedPrefix + prompt))
                } else {
                    var finished: AgentReply? = null
                    var rounds = emptyList<RoundTrace>()
                    agent.sendStreaming(
                        sessionId = sessionId,
                        text = prompt,
                        systemPrompt = sessionSystemPrompt(
                            characters = characters,
                            skills = skills,
                            session = sessions.firstOrNull { it.id == sessionId },
                        ),
                    ).collect { progress ->
                        when (progress) {
                            is AgentProgress.RoundStarted -> {
                                awaitingModel = false
                                // A new round starts a new answer: the previous round's text
                                // either became a stored message or was scaffolding around a
                                // tool call, and either way it must not run into this one.
                                streamingText = ""
                                rounds = reduce(rounds, progress)
                                liveRounds = rounds
                            }

                            is AgentProgress.TextDelta -> {
                                awaitingModel = false
                                streamingText += progress.text
                            }

                            is AgentProgress.ToolStarted,
                            is AgentProgress.ToolAwaitingApproval,
                            is AgentProgress.ToolFinished,
                            -> {
                                awaitingModel = false
                                rounds = reduce(rounds, progress)
                                liveRounds = rounds
                            }

                            is AgentProgress.RoundFinished -> {
                                // The next thing that happens is another model call.
                                awaitingModel = true
                                rounds = reduce(rounds, progress)
                                liveRounds = rounds
                            }

                            is AgentProgress.Finalizing -> {
                                awaitingModel = true
                                finalizing = true
                                rounds = reduce(rounds, progress)
                                liveRounds = rounds
                            }

                            is AgentProgress.Done -> {
                                finished = progress.reply
                                // The stored message takes over in the same frame; leaving the
                                // live text would render the answer twice.
                                streamingText = ""
                            }
                        }
                    }
                    val reply = finished ?: error("智能体没有返回最终结果")
                    val answer = reply.message.content.ifBlank { "（模型没有返回文本）" }
                    lastTurnTokens = reply.usage?.totalTokens
                    val message = assistantMessage(
                        answer,
                        // Events stay: they are the only record of the tool work that survives a
                        // reload, and the timeline is rebuilt from them.
                        events = reply.events,
                        stopNote = stopNoteOf(reply.stop),
                        traces = rounds.map { it.toSummary() },
                    )
                    append(sessionId, message)
                    // Hand over from the live blocks to the stored summary immediately, so the same
                    // round is never rendered twice.
                    liveRounds = emptyList()
                }
            } catch (cancel: CancellationException) {
                // The stop button lands here: close the spinners first, otherwise they would keep
                // turning forever because no ToolFinished will ever arrive.
                liveRounds = cancelOutstanding(liveRounds)
                append(sessionId, assistantMessage("（已停止）"))
                throw cancel
            } catch (error: Throwable) {
                append(
                    sessionId,
                    assistantMessage("（对话失败）${error.message ?: error::class.simpleName}"),
                )
            } finally {
                if (replyingSessionId == sessionId) replyingSessionId = null
                liveRounds = emptyList()
                streamingText = ""
                awaitingModel = false
                finalizing = false
                replyJobs.remove(sessionId)
            }
        }
        replyJobs[sessionId] = job
    }

    /** Cancels the in-flight turn of the active session; the brain unwinds as a cancellation. */
    fun stopReply() {
        val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
        replyJobs[target.id]?.cancel()
    }

    /** One fresh conversation, selected immediately - shared by the drawer and the top bar. */
    fun newChat() {
        val fresh = newSession()
        sessions += fresh
        persist()
        activeSessionId = fresh.id
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
        if (replyingSessionId == target.id) return
        mutate(target.id) { session ->
            session.copy(
                messages = session.messages + userMessage(body),
                title = if (session.messages.none { it.fromUser }) body else session.title,
                updatedAt = System.currentTimeMillis(),
            )
        }
        requestReply(target.id, body)
    }

    /**
     * Drops exactly one bubble. The agent's own memory keeps whatever that turn appended: the
     * contract has no per-message delete, so this is a view-level edit.
     *
     * No-op while this session is replying - deleting a message under an in-flight turn would
     * interleave a reply with a conversation it was not generated from.
     */
    fun delete(messageId: String) {
        val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
        if (replyingSessionId == target.id) return
        mutate(target.id) { session ->
            session.copy(
                messages = withoutMessage(session.messages, messageId),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Asks the same question again: the history is cut back to its user bubble (kept, not
     * re-appended) and a fresh reply is requested for it.
     */
    fun regenerate(messageId: String) {
        val target = sessions.firstOrNull { it.id == activeSessionId } ?: return
        if (replyingSessionId == target.id) return
        val plan = regenerationOf(target.messages, messageId) ?: return
        mutate(target.id) { session ->
            session.copy(messages = plan.messages, updatedAt = System.currentTimeMillis())
        }
        requestReply(target.id, plan.prompt)
    }
}
