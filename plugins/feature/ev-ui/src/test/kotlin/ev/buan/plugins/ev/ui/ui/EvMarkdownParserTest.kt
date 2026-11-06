package ev.buan.plugins.ev.ui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器的行为钉子。
 *
 * 这些用例只在这个文件存在之后才有意义：解析原本和布局写在一起，想验证"这张表会不会被拆错"
 * 得把整个界面跑起来。现在是纯函数，字符串进、结构出。
 *
 * 覆盖面刻意按**模型真实会吐出来的形状**挑，而不是按 CommonMark 的条目：中文段落里的
 * 全角重音符、缩进乱七八糟的列表、附注在单元格里的 `|`——这些才是实际会碰到的东西。
 */
class EvMarkdownParserTest {

    // ── 围栏 ────────────────────────────────────────────────────────────────

    @Test
    fun `fenced code becomes its own segment between prose`() {
        val segments = splitFences("说明\n```\nval a = 1\n```\n结尾")
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Prose)
        assertEquals("val a = 1", (segments[1] as MarkdownSegment.Code).text)
        assertTrue(segments[2] is MarkdownSegment.Prose)
    }

    @Test
    fun `unclosed fence still yields a code segment`() {
        // 模型被截断时会留下半截围栏。丢掉它等于把答案截在代码块开头。
        val segments = splitFences("```kotlin\nfun main() {}")
        assertEquals(1, segments.size)
        assertEquals("fun main() {}", (segments[0] as MarkdownSegment.Code).text)
    }

    @Test
    fun `crlf line endings do not leak into rendered text`() {
        val segments = splitFences("a\r\nb")
        val prose = segments.single() as MarkdownSegment.Prose
        val paragraph = prose.blocks.single() as MarkdownBlock.Paragraph
        assertEquals("a\nb", paragraph.text)
    }

    // ── 块 ──────────────────────────────────────────────────────────────────

    @Test
    fun `headings carry their level`() {
        val blocks = splitBlocks("## 二级\n正文")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Heading(2, "二级"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("正文"), blocks[1])
    }

    @Test
    fun `seven hashes is not a heading`() {
        // `#{1,6}(?!#)`：七个以上是普通段落，不是七级标题。
        val blocks = splitBlocks("####### 太深了")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `bullet and ordered lists keep nesting depth`() {
        val blocks = splitBlocks("- 一\n  - 二\n1. 三")
        val bullets = blocks.single() as MarkdownBlock.Bullets
        assertEquals(listOf("一", "二", "三"), bullets.items.map { it.text })
        assertEquals(listOf(0, 1, 0), bullets.items.map { it.depth })
        assertEquals("•", bullets.items[0].marker)
        assertEquals("1.", bullets.items[2].marker)
    }

    @Test
    fun `indent depth is capped so deep nesting cannot run off page`() {
        assertEquals(0, indentDepth(""))
        assertEquals(1, indentDepth("  "))
        assertEquals(4, indentDepth("          "))
    }

    // ── 表格 ────────────────────────────────────────────────────────────────

    @Test
    fun `table splits header from rows and ignores alignment markers`() {
        val table = parseTable(listOf("| a | b |", "|---|:--|", "| 1 | 2 |"))
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `a pipe inside cell text always breaks the column`() {
        // 没有转义处理是刻意的：见解析文件的注释。这条用例把"刻意"钉住，
        // 免得以后有人顺手加了个转义规则又没说。
        assertEquals(listOf("a", "b"), splitTableRow("| a | b |"))
        assertNull(splitTableRow("没有竖线"))
    }

    @Test
    fun `table end stops at the first non row line`() {
        val lines = listOf("| a |", "|---|", "| 1 |", "", "正文")
        assertEquals(3, tableEnd(lines, 0))
    }

    @Test
    fun `a lone pipe row without a delimiter is not a table`() {
        // 只把 `|` 当表格的判据会让普通文本里的竖线凭空变成表格。
        assertEquals(-1, tableEnd(listOf("| a |", "| 1 |"), 0))
    }

    // ── 行内 ────────────────────────────────────────────────────────────────

    @Test
    fun `bold and inline code lose their markers`() {
        val text = inlineMarkdown("这里 **粗** 和 `代码`", Color.Gray).text
        assertEquals("这里 粗 和 代码", text)
    }

    @Test
    fun `bold keeps a bold span`() {
        val annotated = inlineMarkdown("**粗**", Color.Gray)
        val weights = annotated.spanStyles.map { it.item.fontWeight }
        assertTrue(weights.contains(FontWeight.Bold))
    }

    // ── 全角重音符 ──────────────────────────────────────────────────────────

    @Test
    fun `fullwidth grave accent is folded into the ascii backtick`() {
        // 中文输入法里 ｀ 与 ` 字形相同，模型经常混用；不折叠的话它会作为可见符号漏进答案。
        assertEquals("看 `x`", normalizeGraveAccents("看 ｀x｀"))
    }

    @Test
    fun `unpaired markers are dropped instead of rendered`() {
        assertEquals("只有一半", normalizeGraveAccents("只有一半`"))
    }

    @Test
    fun `ascii text passes through untouched and fast`() {
        assertEquals("plain `code`", normalizeGraveAccents("plain `code`"))
    }

    @Test
    fun `fullwidth asterisk is folded too`() {
        assertEquals("*粗*", normalizeGraveAccents("＊粗＊"))
    }
}
