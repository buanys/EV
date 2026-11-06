package ev.buan.plugins.ev.ui.ui

/**
 * A description used as a row's summary; a very long one is clipped rather than laid out.
 *
 * This lived in the tools screen until that panel moved into agent-tools. The character and skill
 * screens still need it, and it was never about tools in the first place - it is how any row in
 * this plugin folds a paragraph into one line.
 */
internal fun descriptionSummary(description: String, maxChars: Int = 120): String? {
    val trimmed = description.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars - 1).trimEnd() + "…"
}
