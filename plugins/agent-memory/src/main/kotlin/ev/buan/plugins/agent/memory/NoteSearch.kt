package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryHit
import ev.buan.plugins.agent.api.MemoryNote

/**
 * The L2 search index: a Chinese bigram inverted list, rebuilt in memory from the notes.
 *
 * Not SQLite FTS: the Android build ships `unicode61`, which does not segment Chinese, so a
 * "用户偏好" query would only match that exact string. Not a tokenizer dependency either - a
 * plugin gets a Cordis context, not a classpath of its own. Bigrams are the cheap middle: split
 * every CJK run into overlapping two-character terms, index ASCII words whole, and match query
 * terms against the postings.
 *
 * Not persisted: notes are a few thousand rows at most and building the map is a single pass, so
 * a cache file would add a corruption mode without saving measurable time. It is rebuilt lazily
 * on the first query after a write.
 */
internal class NoteSearch(notes: List<MemoryNote>) {

    private val notes: List<MemoryNote> = notes.toList()

    private val postings: Map<String, IntArray>

    init {
        val map = HashMap<String, MutableList<Int>>()
        this.notes.forEachIndexed { index, note ->
            termsOf(note).forEach { term ->
                map.getOrPut(term) { mutableListOf() }.add(index)
            }
        }
        postings = map.mapValues { (_, indexes) -> indexes.toIntArray() }
    }

    /**
     * Notes matching [query], best first.
     *
     * Deliberately not an intersection: one word the notebook has never seen would empty a query
     * that is otherwise precise ("用户 偏好 中文" should still find the two-term note). A note
     * scores by how many query terms it contains, with a whole bigram worth more than a single
     * CJK character, then by impact and recency.
     */
    fun find(query: String, limit: Int): List<MemoryHit> {
        val terms = NoteTerms.of(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val capped = limit.coerceIn(1, MAX_HITS)
        val scores = HashMap<Int, Int>()
        terms.forEach { term ->
            val weight = if (term.length == 1) SINGLE_WEIGHT else TERM_WEIGHT
            postings[term]?.forEach { index -> scores[index] = (scores[index] ?: 0) + weight }
        }
        return scores.entries
            .asSequence()
            .filter { notes[it.key].status == MemoryNote.STATUS_ACTIVE }
            .sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }
                    .thenByDescending { notes[it.key].impact }
                    .thenByDescending { notes[it.key].updatedAt }
                    .thenBy { notes[it.key].id },
            )
            .take(capped)
            .map { entry ->
                val note = notes[entry.key]
                MemoryHit(
                    id = note.id,
                    title = note.title,
                    kind = note.kind,
                    updatedAt = note.updatedAt,
                    score = entry.value,
                )
            }
            .toList()
    }

    private fun termsOf(note: MemoryNote): List<String> {
        val text = buildString {
            append(note.title).append('\n')
            append(note.body).append('\n')
            append(note.keywords.joinToString(" "))
        }
        // One note contributes each term once: otherwise a note that repeats a word ten times
        // outranks a note that actually answers the query.
        return NoteTerms.of(text).distinct()
    }

    private companion object {
        const val MAX_HITS = 20
        const val TERM_WEIGHT = 2
        const val SINGLE_WEIGHT = 1
    }
}

/** Text to search terms: CJK runs become overlapping bigrams, ASCII words stay whole. */
internal object NoteTerms {

    fun of(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.lowercase()
        val terms = ArrayList<String>()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            when {
                c.isCjk() -> {
                    var end = i
                    while (end < normalized.length && normalized[end].isCjk()) end++
                    val run = normalized.substring(i, end)
                    if (run.length == 1) {
                        terms += run
                    } else {
                        for (start in 0 until run.length - 1) {
                            terms += run.substring(start, start + 2)
                        }
                    }
                    i = end
                }
                c.isLetterOrDigit() -> {
                    var end = i
                    while (end < normalized.length &&
                        normalized[end].isLetterOrDigit() &&
                        !normalized[end].isCjk()
                    ) {
                        end++
                    }
                    val word = normalized.substring(i, end)
                    // A single letter is noise ("a", "的" already handled above).
                    if (word.length >= 2) terms += word
                    i = end
                }
                else -> i++
            }
        }
        return terms
    }

    private fun Char.isCjk(): Boolean =
        this in '\u3400'..'\u4dbf' || this in '\u4e00'..'\u9fff'
}
