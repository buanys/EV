package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * One block of prose. Fenced code is not a block here: it becomes its own slab, because a code
 * block is a layout element rather than a run of styled text.
 *
 * The parser below is a hand-rolled subset, not CommonMark. It covers the shapes assistant replies
 * actually use: paragraph text, `#`/`##`/`###` headings, `-`/`*`/`1.` list items and `| … |` tables.
 */
private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock

    data class Bullets(val items: List<ListItem>) : MarkdownBlock
}

private data class ListItem(
    /** Rendered marker: a bullet glyph, or the original ordered number with its dot. */
    val marker: String,
    /** Nesting level derived from leading spaces, so indented lists stay indented. */
    val depth: Int,
    val text: String,
)

private sealed interface MarkdownSegment {
    data class Prose(val blocks: List<MarkdownBlock>) : MarkdownSegment
    data class Code(val text: String) : MarkdownSegment
}

/**
 * A deliberately small Markdown subset for assistant replies: fenced code blocks become quiet
 * monospace slabs, `**bold**` / `` `inline code` `` lose their markers and get real styling, and
 * headings, lists and tables get a real layout instead of arriving as raw punctuation.
 *
 * Everything here is a rendering subset, not a parser: alignment markers in table delimiter rows
 * are accepted but ignored (columns are equal width), nested blocks and inline HTML are left alone,
 * and a `|` inside a cell is always a column break because there is no escape handling.
 */
@Composable
fun EvMarkdown(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { splitFences(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Code -> CodeSlab(segment.text)

                is MarkdownSegment.Prose -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    segment.blocks.forEach { block -> MarkdownBlockView(block) }
                }
            }
        }
    }
}

@Composable
private fun CodeSlab(text: String) {
    // Spec §7.1: surface2 slab, small radius, monospace, copy affordance in the top corner.
    val tokens = EvThemeTokens.colors
    val clipboard = LocalClipboardManager.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(EvThemeTokens.radii.smallShape)
            .background(tokens.surface2),
    ) {
        Text(
            text = text,
            style = EvThemeTokens.type.caption.style(tokens.textPrimary).copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 44.dp),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "复制代码") { clipboard.setText(AnnotatedString(text)) },
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(
                EvGlyphKind.Doc,
                color = tokens.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val tokens = EvThemeTokens.colors
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text, tokens.accent),
            style = EvThemeTokens.type.body.style(tokens.textPrimary),
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        is MarkdownBlock.Heading -> {
            // The markers are gone, so weight carries the level; size never exceeds the title
            // step of the type scale (spec §7.1).
            val style = when (block.level) {
                1 -> EvThemeTokens.type.title.style(tokens.textPrimary).copy(fontWeight = FontWeight.Bold)
                2 -> EvThemeTokens.type.body.style(tokens.textPrimary).copy(fontWeight = FontWeight.SemiBold)
                else -> EvThemeTokens.type.body.style(tokens.textPrimary).copy(fontWeight = FontWeight.Medium)
            }
            Text(
                text = inlineMarkdown(block.text, tokens.accent),
                style = style,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }

        is MarkdownBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = (item.depth * 12).dp, end = 2.dp),
                ) {
                    Text(
                        text = item.marker,
                        style = EvThemeTokens.type.body.style(tokens.textTertiary),
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(
                        text = inlineMarkdown(item.text, tokens.accent),
                        style = EvThemeTokens.type.body.style(tokens.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MarkdownBlock.Table -> MarkdownTable(block)
    }
}

/**
 * Tables are laid out as equal-width columns: a real grid would need measured column widths, which
 * a reply that is still streaming does not have. The header keeps a light fill so the first row
 * still reads as a header once the pipes are gone.
 */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val tokens = EvThemeTokens.colors
    val columns = table.header.size
    Column(
        Modifier
            .fillMaxWidth()
            .clip(EvThemeTokens.radii.smallShape)
            .background(tokens.surface1),
    ) {
        MarkdownTableRow(table.header, columns, header = true)
        table.rows.forEach { row ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(1.dp)
                    .background(tokens.dividerGhost),
            )
            MarkdownTableRow(row, columns, header = false)
        }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, columns: Int, header: Boolean) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (header) tokens.surface2 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        for (index in 0 until columns) {
            Text(
                text = inlineMarkdown(cells.getOrElse(index) { "" }, tokens.accent),
                style = EvThemeTokens.type.caption.style(tokens.textPrimary).copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                // The divider gutter sits between columns, never after the last one.
                modifier = Modifier.weight(1f).padding(end = if (index == columns - 1) 0.dp else 8.dp),
            )
        }
    }
}

private val headingPattern = Regex("""^(#{1,6})(?!#)\s*(.+)$""")
private val bulletPattern = Regex("""^(\s*)[-*]\s+(.*)$""")
private val orderedPattern = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val delimiterCellPattern = Regex(""":?-+:?""")

private fun splitFences(text: String): List<MarkdownSegment> {
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
private fun splitBlocks(text: String): List<MarkdownBlock> {
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
private fun tableEnd(lines: List<String>, start: Int): Int {
    if (start + 1 >= lines.size) return -1
    val header = splitTableRow(lines[start]) ?: return -1
    val delimiter = splitTableRow(lines[start + 1]) ?: return -1
    if (delimiter.size != header.size) return -1
    if (!delimiter.all { delimiterCellPattern.matches(it) }) return -1
    var end = start + 2
    while (end < lines.size && splitTableRow(lines[end]) != null) end++
    return end
}

private fun splitTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (trimmed.length < 2) return null
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return null
    return trimmed.substring(1, trimmed.length - 1).split('|').map { it.trim() }
}

private fun parseTable(lines: List<String>): MarkdownBlock.Table {
    val header = splitTableRow(lines[0]).orEmpty()
    val rows = lines.drop(2).map { line ->
        val cells = splitTableRow(line).orEmpty()
        // Short rows are padded and long ones truncated, so every rendered row has the header width.
        List(header.size) { index -> cells.getOrElse(index) { "" } }
    }
    return MarkdownBlock.Table(header = header, rows = rows)
}

/** Two leading spaces count as one nesting level, capped so deep indentation cannot run off-page. */
private fun indentDepth(indent: String): Int = (indent.length / 2).coerceIn(0, 4)

private fun inlineMarkdown(text: String, codeColor: Color): AnnotatedString {
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
                    SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor),
                ) { append(code) }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
}
