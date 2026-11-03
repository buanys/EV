package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * The model's own notebook (L2) and the resident block rendered from it (L3).
 *
 * Why this is not [MemoryService]: that one owns conversation history and answers "what was said
 * in this session". This one owns distilled notes and answers "what do we already know", across
 * sessions. Keeping them apart means a turn can read history without paying for an index, and a
 * note can outlive the conversation that produced it.
 *
 * Every method is synchronous. [SystemPromptService.layers] has to be synchronous and
 * deterministic, and L3 is rendered from an in-memory snapshot on every turn; a `suspend` here
 * would turn prompt assembly into a coroutine the brain cannot replay.
 */
interface MemoryStoreService {

    /**
     * Adds a note owned by the model.
     *
     * [kind] is normalised by the implementation; an unknown value is kept as-is rather than
     * rejected, so a future kind written by a newer model does not fail this call. [title] is
     * derived from the text when absent.
     */
    fun remember(
        text: String,
        kind: String = MemoryNote.KIND_FACT,
        title: String? = null,
    ): MemoryNote

    /**
     * Changes the editable fields of [id]; a distilled note becomes [MemoryNote.ORIGIN_MODEL],
     * because the model editing a note means it now owns it.
     *
     * Null means "leave unchanged". Returns false when [id] is unknown.
     */
    fun update(
        id: String,
        title: String? = null,
        body: String? = null,
        keywords: List<String>? = null,
    ): Boolean

    /**
     * Archives [id] instead of deleting it.
     *
     * L2 notes are referenced by `sources` from other notes, and (from M2 on) by L1 ids that must
     * stay resolvable. A physical delete would turn a traceability chain into dead ends, so only
     * the UI's "clean up" is allowed to remove bytes.
     */
    fun archive(id: String): Boolean

    /** Active notes matching [query], best match first. */
    fun recall(query: String, limit: Int = 5): List<MemoryHit>

    /** The full note, including its `sources`, or null when [id] is unknown. */
    fun read(id: String): MemoryNote?

    /** Adds ([pinned] = true) or removes a note from the resident block. */
    fun pin(id: String, pinned: Boolean): Boolean

    /** Every stored note, most recently updated first. Used by the plugin's own page. */
    fun notes(): List<MemoryNote>

    /**
     * The L3 text for the system prompt, or an empty string when nothing is pinned.
     *
     * Rendered from memory on every turn: the prompt service must not touch disk.
     */
    fun residentBlock(): String

    companion object {
        val Key = ServiceKey<MemoryStoreService>("agent/memory-store")
    }
}

/**
 * One notebook entry.
 *
 * [kind] and [status] are plain strings, not enums: this contract is compiled into every plugin
 * that depends on it, and adding an enum value would crash an already-built `when` on the other
 * side of the plugin boundary. Implementations normalise; consumers compare.
 */
data class MemoryNote(
    val id: String,
    val title: String,
    val kind: String = KIND_FACT,
    val body: String = "",
    val keywords: List<String> = emptyList(),
    /** L1 ids this note was distilled from; empty for a note the model wrote itself. */
    val sources: List<String> = emptyList(),
    val sessions: List<String> = emptyList(),
    val status: String = STATUS_ACTIVE,
    /** Set when this note replaced another one; the old note is then [STATUS_SUPERSEDED]. */
    val supersedes: String? = null,
    /** Model-adjustable weight, used to order the resident block. */
    val impact: Double = 1.0,
    val origin: String = ORIGIN_MODEL,
    val pinned: Boolean = false,
    /** Larger pins first; ties fall back to [impact] and [updatedAt]. */
    val pinOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val KIND_PREFERENCE = "preference"
        const val KIND_FACT = "fact"
        const val KIND_TASK = "task"
        const val KIND_CONVENTION = "convention"

        const val STATUS_ACTIVE = "active"
        const val STATUS_SUPERSEDED = "superseded"
        const val STATUS_ARCHIVED = "archived"

        const val ORIGIN_MODEL = "model"
        const val ORIGIN_DISTILLED = "distilled"

        /** The kinds whose spelling is fixed by this contract; other values are allowed. */
        val KINDS = listOf(KIND_PREFERENCE, KIND_FACT, KIND_TASK, KIND_CONVENTION)
    }
}

/**
 * One search result.
 *
 * The body is deliberately absent: `recall` is cheap and returns candidates, `read` pays for the
 * full text. Returning bodies here would make the model's first call expensive for no reason.
 */
data class MemoryHit(
    val id: String,
    val title: String,
    val kind: String,
    val updatedAt: Long,
    val score: Int,
)
