package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryNote

/**
 * Renders L3: the pinned notes that ride along in every system prompt.
 *
 * Why 3000 characters and not the 8000 the design doc first proposed: the brain's whole system
 * message budget is 8000 (`AgentBrainService.MAX_SYSTEM_PROMPT_CHARS`), and dropping is by whole
 * layer from the lowest priority. An L3 that can reach 8000 would evict `tools` and `stop` before
 * it ever got trimmed itself - the memory would silence the instructions that make the agent act.
 * 3000 leaves room for identity, stop, tools, style and a persona.
 *
 * Ordering is by pin order first, so the user's explicit choice beats any automatic weight; the
 * rest is deterministic (impact, recency, id) because the prompt must be reproducible.
 */
internal object Resident {

    /** Hard cap on the rendered block. See the class comment for why it is not 8000. */
    const val BUDGET_CHARS = 3_000

    /** One line should stay scannable; the full text is one `read_memory` away. */
    private const val BODY_CHARS = 120
    private const val MAX_LINE_CHARS = 220

    /**
     * Groups in render order. A note whose kind is not listed here still renders, under `其他`:
     * the contract allows unknown kinds, and hiding one because the UI does not know its name
     * would silently lose the model's note.
     */
    private val GROUPS = listOf(
        MemoryNote.KIND_PREFERENCE to "关于用户",
        MemoryNote.KIND_TASK to "进行中",
        MemoryNote.KIND_CONVENTION to "约定",
        MemoryNote.KIND_FACT to "事实",
    )

    private const val OTHER = "其他"

    /** The block, or an empty string when nothing is pinned. */
    fun render(notes: List<MemoryNote>): String {
        val pinned = notes
            .filter { it.pinned && it.status == MemoryNote.STATUS_ACTIVE }
            .sortedWith(
                compareByDescending<MemoryNote> { it.pinOrder }
                    .thenByDescending { it.impact }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id },
            )
        if (pinned.isEmpty()) return ""

        val out = StringBuilder()
        var used = 0
        var skipped = 0
        val budget: (Int) -> Boolean = { cost ->
            if (used + cost > BUDGET_CHARS) {
                skipped++
                false
            } else {
                used += cost
                true
            }
        }
        GROUPS.forEach { (kind, heading) ->
            val items = pinned.filter { it.kind == kind }
            if (items.isNotEmpty()) renderGroup(out, items, heading, budget)
        }
        val others = pinned.filter { note -> GROUPS.none { it.first == note.kind } }
        if (others.isNotEmpty()) renderGroup(out, others, OTHER, budget)

        if (skipped > 0) {
            val hint = "（另有 $skipped 条常驻记忆未展开，用 recall 查）"
            if (used + hint.length + 1 <= BUDGET_CHARS) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(hint)
                used += hint.length + 1
            }
        }
        return out.toString().trimEnd()
    }

    /**
     * Appends one group. The heading is written only after a line inside it was accepted, so a
     * group whose entries all overflow does not leave an empty title behind.
     *
     * [accept] receives the cost of a line and answers whether it fits; it is also how the caller
     * counts what was skipped, without this helper knowing about budgets.
     */
    private inline fun renderGroup(
        out: StringBuilder,
        items: List<MemoryNote>,
        heading: String,
        accept: (Int) -> Boolean,
    ) {
        val header = "【$heading】"
        var opened = false
        items.forEach { note ->
            val line = lineOf(note)
            val cost = if (opened) line.length + 1 else header.length + 2 + line.length + 1
            if (!accept(cost)) return@forEach
            if (!opened) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(header).append('\n')
                opened = true
            }
            out.append(line).append('\n')
        }
    }

    private fun lineOf(note: MemoryNote): String {
        val title = note.title.trim().take(MAX_LINE_CHARS)
        val body = note.body.trim().replace('\n', ' ')
        val detail = when {
            body.isEmpty() -> ""
            body == note.title.trim() -> ""
            else -> "：" + body.take(BODY_CHARS)
        }
        return "- $title$detail".take(MAX_LINE_CHARS + BODY_CHARS + 2)
    }
}
