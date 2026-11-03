package ev.buan.plugins.agent.memory

import android.util.Log
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.ModelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cordis.api.UserDataStorage

/**
 * Distils uncovered conversation into notes (L1 → L2, M2).
 *
 * **L1 is the session history itself.** The design doc imagined a separate draft book, but the
 * stored history is already the complete record: every user line, every assistant line, every
 * tool call and result, with nothing trimmed (trimming happens when the brain builds a request,
 * not when memory stores). A second copy would double the write cost and could drift. The only
 * thing this class needs is the *position*, which is the message index in that history.
 *
 * **When it runs.** Not on a timer - Android kills background timers. A distillation is attempted
 * on the first message after the session has been idle for [IDLE_MS], which is exactly the moment
 * the user came back; the batch then covers everything before that message. So the notes are
 * written while the user is waiting for the *next* answer, not while a turn is running.
 *
 * **Two-phase, but with no partial writes.** A `pending` record is persisted before the model
 * call and cleared after the batch is committed. A pending record older than
 * [PENDING_TIMEOUT_MS] means the process died mid-call: it is dropped and the same message range
 * is retried. There is nothing to roll back, because notes are written only after the whole batch
 * parsed, validated and de-duplicated - a crash leaves the notebook untouched, not half-updated.
 *
 * A failure never surfaces to the user and never blocks a turn: the cursor simply does not move,
 * and the next idle moment retries.
 */
internal class Distiller(    private val storage: UserDataStorage,
    private val memory: StorageMemoryService,
    private val notes: NoteStore,
    private val model: () -> ModelService?,
    private val scope: CoroutineScope,
) {

    private val lock = Any()

    /** Lazily loaded cursor; null means "not read from disk yet". */
    private var cached: DistillState? = null

    /** Only one batch at a time: two model calls over the same range would waste tokens. */
    private var running = false

    /**
     * Called after every stored message. Cheap and synchronous: it only compares two timestamps.
     * The expensive part (history read, model call) is launched separately.
     */
    fun onMessageStored(sessionId: String) {
        val now = System.currentTimeMillis()
        val start = synchronized(lock) {
            val current = snapshot()
            val idle = now - current.lastAt(sessionId) >= IDLE_MS
            persist(current.withLastAt(sessionId, now))
            idle && !running
        }
        if (start) scope.launch { distill(sessionId) }
    }

    private suspend fun distill(sessionId: String) {
        val modelService = model() ?: return
        val target = modelService.models.firstOrNull() ?: return
        val history = memory.history(sessionId)
        if (history.isEmpty()) return

        val batch = synchronized(lock) {
            if (running) return
            val current = snapshot()
            val pending = current.pending[sessionId]
            if (pending != null && System.currentTimeMillis() - pending.startedAt < PENDING_TIMEOUT_MS) {
                // Another batch is genuinely in flight; do not stack a second model call on it.
                return
            }
            val covered = current.covered(sessionId)
            if (history.size - covered < MIN_MESSAGES) return
            val next = DistillState.Pending(
                from = covered + 1,
                to = history.size,
                startedAt = System.currentTimeMillis(),
            )
            running = true
            persist(current.withPending(sessionId, next))
            next
        }

        val transcript = DistillPrompt.transcript(history, batch.from, batch.to)
        if (transcript.isBlank()) {
            finish(sessionId, batch, drafts = null)
            return
        }

        val drafts = try {
            val reply = modelService.complete(
                model = target,
                messages = listOf(
                    AgentMessage(AgentRole.SYSTEM, DistillPrompt.system(notes.titles())),
                    AgentMessage(AgentRole.USER, transcript),
                ),
            )
            DistillPrompt.parse(
                reply = reply.message.content,
                sessionId = sessionId,
                from = batch.from,
                to = batch.to,
                existingTitles = notes.titles(),
            )
        } catch (cancelled: CancellationException) {
            finish(sessionId, batch, drafts = null)
            throw cancelled
        } catch (error: Throwable) {
            // A provider outage, a rate limit, a malformed answer: keep the cursor where it is so
            // the same range is retried later, and never let it reach the conversation. The state
            // is cleared before logging on purpose - a logging failure must not leave `pending`
            // behind and block every later batch.
            finish(sessionId, batch, drafts = null)
            Log.w(TAG, "提炼失败（${batch.from}..${batch.to}）：${error.message}")
            return
        }

        finish(sessionId, batch, drafts)
    }

    /**
     * Commits [drafts] and moves the cursor, or clears the pending record on failure.
     *
     * An empty draft list is still a success: the model decided nothing in that range is worth
     * keeping, and re-running it every idle moment would cost tokens forever.
     */
    private fun finish(sessionId: String, batch: DistillState.Pending, drafts: List<DistillDraft>?) {
        synchronized(lock) {
            running = false
            val current = snapshot()
            if (drafts == null) {
                persist(current.withPending(sessionId, null))
                return
            }
            val added = notes.addDistilled(drafts, sessionId)
            persist(
                current
                    .withCovered(sessionId, batch.to)
                    .withPending(sessionId, null),
            )
            Log.i(TAG, "提炼完成：${batch.from}..${batch.to}，新增 $added 条")
        }
    }

    /**
     * Read-only view for the memory page: how much of the notebook rides in every prompt, and how
     * far the distillation cursor has got.
     *
     * [covered] is summed across sessions, which is what the page can honestly say: it is a count
     * of messages already folded into notes, not a per-session progress bar.
     */
    fun status(): DistillStatus = synchronized(lock) {
        val current = snapshot()
        DistillStatus(
            covered = current.coveredUpTo.values.sum(),
            running = running || current.pending.isNotEmpty(),
            lastAt = current.lastMessageAt.values.maxOrNull() ?: 0L,
        )
    }

    private fun snapshot(): DistillState = cached ?: load().also { cached = it }

    private fun load(): DistillState {
        val bytes = storage.read(DistillState.PATH) ?: return DistillState()
        return DistillState.decode(bytes)
    }

    /** The cursor is a convenience, not the truth: losing it costs one retry, never data. */
    private fun persist(next: DistillState) {
        cached = next
        runCatching { storage.atomicWrite(DistillState.PATH, next.encode()) }
            .onFailure { Log.w(TAG, "提炼游标写入失败：${it.message}") }
    }

    private companion object {
        const val TAG = "agent-memory"

        /** The session must have been quiet this long before a batch runs. */
        const val IDLE_MS = 15 * 60 * 1000L

        /** Nothing to distil below this many uncovered messages. */
        const val MIN_MESSAGES = 20

        /** A pending batch older than this is a crash, not a slow model. */
        const val PENDING_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

/** Snapshot for the memory page: see [Distiller.status]. */
internal data class DistillStatus(
    /** Messages already folded into notes, summed across sessions. */
    val covered: Int,
    /** A batch is in flight, or a pending cursor says one was interrupted. */
    val running: Boolean,
    /** When the most recently active session last stored a message. */
    val lastAt: Long,
)
