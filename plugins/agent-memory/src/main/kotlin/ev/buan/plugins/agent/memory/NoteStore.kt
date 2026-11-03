package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryHit
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.plugins.agent.api.MemoryStoreService
import org.cordis.api.UserDataStorage

/**
 * L2 as one JSON document in the user-data area, plus the lazily rebuilt search index.
 *
 * The notebook lives beside conversation history but in its own file: history is append-only and
 * per session, notes are cross-session and edited in place. Sharing one document would make every
 * note edit rewrite every transcript.
 *
 * Every operation serialises on one JVM monitor. [UserDataStorage] is blocking by contract, and
 * `remember` is a read-modify-write of the whole document; a coroutine mutex would add a
 * dispatcher and claim a suspension point that does not exist.
 *
 * A missing file is an empty notebook. An unreadable one throws instead of returning empty: the
 * next write would otherwise replace the user's notes with whatever the model happened to add.
 */
internal class NoteStore(private val storage: UserDataStorage) : MemoryStoreService {

    private val lock = Any()

    /** Loaded once and kept in step with writes; null means "not read from disk yet". */
    private var cache: List<MemoryNote>? = null

    /** Rebuilt on the first query after a write; see [NoteSearch]. */
    private var search: NoteSearch? = null

    override fun notes(): List<MemoryNote> = synchronized(lock) {
        snapshot().sortedWith(
            compareByDescending<MemoryNote> { it.updatedAt }.thenByDescending { it.createdAt },
        )
    }

    override fun remember(text: String, kind: String, title: String?): MemoryNote =
        synchronized(lock) {
            val body = text.trim()
            require(body.isNotEmpty()) { "记忆内容不能为空" }
            val now = System.currentTimeMillis()
            val existing = snapshot()
            val note = MemoryNote(
                id = nextId(now, existing),
                title = (title?.trim()?.takeIf { it.isNotEmpty() } ?: deriveTitle(body))
                    .take(TITLE_CHARS),
                kind = normalizeKind(kind),
                body = body.take(MAX_BODY_CHARS),
                createdAt = now,
                updatedAt = now,
            )
            commit(existing + note)
            note
        }

    override fun update(
        id: String,
        title: String?,
        body: String?,
        keywords: List<String>?,
    ): Boolean = synchronized(lock) {
        val existing = snapshot()
        val current = existing.firstOrNull { it.id == id } ?: return@synchronized false
        val newTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_CHARS)
        val newBody = body?.let { it.trim().take(MAX_BODY_CHARS) }
        if (newTitle == null && newBody == null && keywords == null) {
            // Nothing to change, but the id is real; report success rather than a misleading false.
            return@synchronized true
        }
        val updated = current.copy(
            title = newTitle ?: current.title,
            body = newBody?.takeIf { it.isNotEmpty() } ?: current.body,
            keywords = keywords
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.take(MAX_KEYWORDS)
                ?: current.keywords,
            // Editing a distilled note transfers ownership: the model read the context the
            // distill pass only saw a transcript of, so its edit wins on the next pass too.
            origin = MemoryNote.ORIGIN_MODEL,
            updatedAt = System.currentTimeMillis(),
        )
        commit(existing.map { if (it.id == id) updated else it })
        true
    }

    override fun archive(id: String): Boolean = synchronized(lock) {
        val existing = snapshot()
        val current = existing.firstOrNull { it.id == id } ?: return@synchronized false
        commit(
            existing.map {
                if (it.id == id) {
                    current.copy(
                        status = MemoryNote.STATUS_ARCHIVED,
                        pinned = false,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            },
        )
        true
    }

    override fun recall(query: String, limit: Int): List<MemoryHit> = synchronized(lock) {
        index().find(query, limit)
    }

    override fun read(id: String): MemoryNote? = synchronized(lock) {
        snapshot().firstOrNull { it.id == id }
    }

    override fun pin(id: String, pinned: Boolean): Boolean = synchronized(lock) {
        val existing = snapshot()
        val current = existing.firstOrNull { it.id == id } ?: return@synchronized false
        val nextOrder = if (pinned) {
            (existing.maxOfOrNull { it.pinOrder } ?: 0) + 1
        } else {
            current.pinOrder
        }
        commit(
            existing.map {
                if (it.id == id) {
                    current.copy(
                        pinned = pinned,
                        pinOrder = nextOrder,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            },
        )
        true
    }

    override fun residentBlock(): String = synchronized(lock) { Resident.render(snapshot()) }

    private fun index(): NoteSearch = search ?: NoteSearch(snapshot()).also { search = it }

    private fun snapshot(): List<MemoryNote> =
        cache ?: load().also { cache = it }

    private fun load(): List<MemoryNote> {
        val bytes = storage.read(NoteDocument.PATH) ?: return emptyList()
        return NoteDocument.decode(bytes)
    }

    /** Writes first, then updates memory: a failed write must not leave the cache ahead of disk. */
    private fun commit(notes: List<MemoryNote>) {
        storage.atomicWrite(NoteDocument.PATH, NoteDocument.encode(notes))
        cache = notes
        search = null
    }

    private fun nextId(now: Long, notes: List<MemoryNote>): String {
        val base = "m_" + now.toString(36)
        val taken = notes.mapTo(HashSet()) { it.id }
        if (base !in taken) return base
        var suffix = 1
        while ("${base}_$suffix" in taken) suffix++
        return "${base}_$suffix"
    }

    private fun deriveTitle(body: String): String {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val stop = firstLine.indexOfFirst { it in "。！？!?；;，," }
        val sentence = if (stop > 0) firstLine.take(stop) else firstLine
        return sentence.take(TITLE_CHARS).ifEmpty { body.take(TITLE_CHARS) }
    }

    private fun normalizeKind(kind: String): String =
        kind.trim().lowercase().ifEmpty { MemoryNote.KIND_FACT }

    private companion object {
        /** Matches the UI's one-line row and the resident block's prefix. */
        const val TITLE_CHARS = 40

        /** A note is a fact, not a transcript; anything longer belongs in a file tool. */
        const val MAX_BODY_CHARS = 2_000

        const val MAX_KEYWORDS = 8
    }
}
