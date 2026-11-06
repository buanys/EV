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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock

    data class Bullets(val items: List<ListItem>) : MarkdownBlock
}

internal data class ListItem(
    /** Rendered marker: a bullet glyph, or the original ordered number with its dot. */
    val marker: String,
    /** Nesting level derived from leading spaces, so indented lists stay indented. */
    val depth: Int,
    val text: String,
)

internal sealed interface MarkdownSegment {
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
    // Models emit punctuation that looks like the ASCII marker but is not: fullwidth grave, modifier
    // letter grave, reversed prime, Greek varia... The inline-code rule only matches U+0060, so
    // anything else leaks into the answer as a visible mark. Rather than chasing codepoints one at
    // a time, ask Unicode which characters are actually named "grave" and fold all of them.
    val normalized = remember(text) { normalizeGraveAccents(text) }
    val segments = remember(normalized) { splitFences(normalized) }
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
                // Tertiary on a surface1 slab was invisible at 14dp; the copy affordance has to be
                // findable without becoming a second focal point.
                color = tokens.textSecondary,
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
            text = inlineMarkdown(block.text, tokens.textSecondary),
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
                text = inlineMarkdown(block.text, tokens.textSecondary),
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
                        text = inlineMarkdown(item.text.replace("`", ""), tokens.textSecondary),
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
                text = inlineMarkdown(
                    cells.getOrElse(index) { "" },
                    tokens.textSecondary,
                ),
                style = EvThemeTokens.type.caption.style(tokens.textPrimary).copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                // The divider gutter sits between columns, never after the last one.
                modifier = Modifier.weight(1f).padding(end = if (index == columns - 1) 0.dp else 8.dp),
            )
        }
    }
}
