package ev.buan.plugins.ev.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/** One dangerous call waiting for the human, rendered by the approval dialog. */
internal data class ApprovalPrompt(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
)

/**
 * The chat UI's voice in the agent loop: the [ApprovalService] the brain suspends on before it
 * runs a dangerous tool.
 *
 * Threading: [request] is called from the brain's IO dispatcher while [decide] comes from the UI
 * thread, so the queue is guarded by [lock]; [pending] is Compose state, published on whichever
 * thread mutates it (the snapshot system makes that safe) and read by the dialog.
 *
 * "ALLOW_SESSION" remembers the *tool name* for this gate's lifetime - the plugin's applied
 * period, i.e. until the UI is disabled or the process dies. It is deliberately not persisted: a
 * standing grant that survives reboots is a policy decision nobody has asked the user for yet.
 */
internal class ApprovalGate : ApprovalService {
    private val lock = Any()
    private val waiting = ArrayDeque<Entry>()
    private val sessionAllowed = mutableSetOf<String>()

    /** The prompts waiting for a tap, oldest first; the dialog shows [pending].firstOrNull(). */
    var pending by mutableStateOf<List<ApprovalPrompt>>(emptyList())
        private set

    override suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision {
        val entry = Entry(
            prompt = ApprovalPrompt(UUID.randomUUID().toString(), toolName, argumentsJson),
            answer = CompletableDeferred(),
        )
        synchronized(lock) {
            if (toolName in sessionAllowed) return ApprovalDecision.ALLOW_ONCE
            waiting += entry
            publish()
        }
        try {
            return entry.answer.await()
        } finally {
            // A cancelled turn (stop button, disabled plugin) must not leave a ghost prompt in
            // the queue: the dialog would otherwise ask about a call nobody is making any more.
            synchronized(lock) {
                if (waiting.remove(entry)) publish()
            }
        }
    }

    /**
     * Resolves exactly the prompt with [id]; an unknown id is a no-op (a double-tap or a stale
     * dialog must not answer someone else's question).
     */
    fun decide(id: String, decision: ApprovalDecision) {
        synchronized(lock) {
            val index = waiting.indexOfFirst { it.prompt.id == id }
            if (index < 0) return
            val entry = waiting.removeAt(index)
            if (decision == ApprovalDecision.ALLOW_SESSION) sessionAllowed += entry.prompt.toolName
            publish()
            entry.answer.complete(decision)
        }
    }

    /** Everything still waiting is denied; used when the gate is torn down mid-request. */
    fun denyAll() {
        synchronized(lock) {
            while (waiting.isNotEmpty()) waiting.removeFirst().answer.complete(ApprovalDecision.DENY)
            publish()
        }
    }

    /** Callers hold [lock]: Compose state writes join the caller's snapshot, UI or IO alike. */
    private fun publish() {
        pending = waiting.map { it.prompt }
    }

    private data class Entry(
        val prompt: ApprovalPrompt,
        val answer: CompletableDeferred<ApprovalDecision>,
    )
}
