package ev.buan.plugins.ev.ui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Markdown 的**文本 → 结构**那一半：分围栏、分块、表格、行内样式。
 *
 * 与渲染分开的理由是它可以被单独检验：这里的每个函数都是纯函数（字符串进、数据出），
 * 没有 Compose、没有主题、没有 `remember`。上一个版本把解析和布局写在同一文件里，
 * 想验证"这张表会不会被拆错"只能把整个界面跑起来看。
 *
 * 仍然是一个刻意做小的子集，不是 CommonMark：表格分隔行里的对齐标记接受但忽略，
 * 嵌套块和行内 HTML 原样保留，单元格里的 `|` 永远是分列符（没有转义）。
 */


internal val headingPattern = Regex("""^(#{1,6})(?!#)\s*(.+)$""")
internal val bulletPattern = Regex("""^(\s*)[-*]\s+(.*)$""")
internal val orderedPattern = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
internal val delimiterCellPattern = Regex(""":?-+:?""")

internal fun splitFences(text: String): List<MarkdownSegment> {
    // Normalise line endings up front: the block parser works line by line, and a stray CR would
    // end up inside rendered text.
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val parts = normalized.split("```")
    val segments = mutableListOf<MarkdownSegment>()
    parts.forEachIndexed { index, part ->
        val isCode = index % 2 == 1
        if (isCode) {
            // Drop an optional language line; keep the body verbatim otherwise.
            val body = part.substringAfter('\n', part).trimEnd()
            if (body.isNotBlank()) segments += MarkdownSegment.Code(body)
        } else {
            val blocks = splitBlocks(part.trim())
            if (blocks.isNotEmpty()) segments += MarkdownSegment.Prose(blocks)
        }
    }
    return segments
}

/**
 * Splits one prose run into blocks. Lines that match no rule are collected verbatim into a
 * paragraph, blank lines separate blocks, and a list run stays one block so its items sit tight.
 */
internal fun splitBlocks(text: String): List<MarkdownBlock> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        val tableEnd = tableEnd(lines, index)
        if (tableEnd > index) {
            flushParagraph()
            blocks += parseTable(lines.subList(index, tableEnd))
            index = tableEnd
            continue
        }

        val heading = headingPattern.matchEntire(line.trim())
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                text = heading.groupValues[2].trim(),
            )
            index++
            continue
        }

        val bullet = bulletPattern.matchEntire(line)
        val ordered = orderedPattern.matchEntire(line)
        if (bullet != null || ordered != null) {
            flushParagraph()
            val items = mutableListOf<ListItem>()
            while (index < lines.size) {
                val current = lines[index]
                val currentBullet = bulletPattern.matchEntire(current)
                val currentOrdered = orderedPattern.matchEntire(current)
                when {
                    currentBullet != null -> items += ListItem(
                        marker = "•",
                        depth = indentDepth(currentBullet.groupValues[1]),
                        text = currentBullet.groupValues[2],
                    )

                    currentOrdered != null -> items += ListItem(
                        marker = "${currentOrdered.groupValues[2]}.",
                        depth = indentDepth(currentOrdered.groupValues[1]),
                        text = currentOrdered.groupValues[3],
                    )

                    else -> break
                }
                index++
            }
            blocks += MarkdownBlock.Bullets(items)
            continue
        }

        paragraph += line
        index++
    }
    flushParagraph()
    return blocks
}

/**
 * Exclusive end index of a `| … |` table that starts at [start], or `-1` when those lines are not
 * one. A table is a row, a delimiter row of the same width, then any number of extra rows; a lone
 * pipe row with no delimiter under it stays ordinary text.
 */
internal fun tableEnd(lines: List<String>, start: Int): Int {
    if (start + 1 >= lines.size) return -1
    val header = splitTableRow(lines[start]) ?: return -1
    val delimiter = splitTableRow(lines[start + 1]) ?: return -1
    if (delimiter.size != header.size) return -1
    if (!delimiter.all { delimiterCellPattern.matches(it) }) return -1
    var end = start + 2
    while (end < lines.size && splitTableRow(lines[end]) != null) end++
    return end
}

internal fun splitTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (trimmed.length < 2) return null
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return null
    return trimmed.substring(1, trimmed.length - 1).split('|').map { it.trim() }
}

internal fun parseTable(lines: List<String>): MarkdownBlock.Table {
    val header = splitTableRow(lines[0]).orEmpty()
    val rows = lines.drop(2).map { line ->
        val cells = splitTableRow(line).orEmpty()
        // Short rows are padded and long ones truncated, so every rendered row has the header width.
        List(header.size) { index -> cells.getOrElse(index) { "" } }
    }
    return MarkdownBlock.Table(header = header, rows = rows)
}

/** Two leading spaces count as one nesting level, capped so deep indentation cannot run off-page. */
internal fun indentDepth(indent: String): Int = (indent.length / 2).coerceIn(0, 4)

/**
 * Folds every Unicode character whose name is a grave accent into the ASCII backtick.
 *
 * A model writing Chinese happily emits U+FF40 ｀ or U+02CB ˋ where an API doc means ` - the glyphs
 * are identical, and the inline-code rule only knows U+0060. Asking Unicode for the character name
 * is a stable way to catch the whole family without enumerating codepoints by hand.
 */
internal fun normalizeGraveAccents(input: String): String {
    if (input.none { it.code > 0x7F }) return input
    val out = StringBuilder(input.length)
    for (ch in input) {
        out.append(
            when {
                ch == '\uFF0A' -> '*'
                ch.code <= 0x7F -> ch
                isGraveAccent(ch) -> '`'
                else -> ch
            },
        )
    }
    val text = out.toString()
    // An odd count means a marker lost its partner: drop the leftovers rather than render them.
    return if (text.count { it == '`' } % 2 == 0) text else text.replace("`", "")
}

internal fun isGraveAccent(ch: Char): Boolean = try {
    val name = Character.getName(ch.code) ?: return false
    name == "MODIFIER LETTER GRAVE" || name.endsWith("GRAVE ACCENT")
} catch (_: IllegalArgumentException) {
    false
}

internal fun inlineMarkdown(text: String, codeColor: Color): AnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    return buildAnnotatedString {
        var cursor = 0
        for (match in pattern.findAll(text)) {
            append(text.substring(cursor, match.range.first))
            val bold = match.groupValues[1]
            val code = match.groupValues[2]
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                code.isNotEmpty() -> withStyle(
                    // DESIGN-EXTRACT 2.9 wants a 7% onSurface background behind inline code, but
                    // miuix's Text swaps SpanStyle colour and background (verified on device: a
                    // dark background renders as a light chip with dark text), so a background is
                    // not usable here. Monospace plus the secondary text step separates code from
                    // prose without a highlight that reads like a text selection.
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = codeColor,
                    ),
                ) { append(code) }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
}
