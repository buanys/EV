package ev.buan.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item

/** 字阶。七级，来自 [EvTypeTokens]，页面只说"这是什么"，不说"多大"。 */
enum class EvTextKind { Display, Headline, Title, Body, Label, Caption, Micro }

/**
 * 语义，不是颜色。
 *
 * 五个档位是有意的上限：再多，"这个到底该用哪个"就变成每次都要想的问题。
 * 需要更醒目就用 [EvButton]，而不是给文字换颜色。
 */
enum class EvTone { Normal, Muted, Accent, Warning, Danger, OnAccent }

/**
 * 页面里唯一的文字入口。
 *
 * 字号来自 [EvTypeTokens]、颜色来自 [LocalEvColorTokens]，两者都由 [EvTheme] 从 spec 算出来。
 * 页面传 `kind` 和 `tone`，永远不传 `TextStyle` 或 `Color`——这是"框架统一渲染"的最小体现。
 */
@Composable
fun EvText(
    text: String,
    modifier: Modifier = Modifier,
    kind: EvTextKind = EvTextKind.Body,
    tone: EvTone = EvTone.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    val type = LocalEvTypeTokens.current
    val token = when (kind) {
        EvTextKind.Display -> type.display
        EvTextKind.Headline -> type.headline
        EvTextKind.Title -> type.title
        EvTextKind.Body -> type.body
        EvTextKind.Label -> type.label
        EvTextKind.Caption -> type.caption
        EvTextKind.Micro -> type.micro
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontSize = token.fontSize,
            lineHeight = token.lineHeight,
            fontWeight = token.fontWeight,
            color = tone.color(),
        ),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * 一组行，可选小标题与脚注。
 *
 * 直接用 COUI 的 [Group]：它负责圆角、卡片底色、标题与脚注的排布，
 * 这些都不该由页面或本文件重新发明。
 */
@Composable
fun EvSection(
    title: String? = null,
    footnote: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Group(
        modifier = modifier.fillMaxWidth(),
        title = title,
        footer = footnote,
        content = content,
    )
}

/**
 * 一行：标题 + 可选摘要 + 可选右侧内容。
 *
 * 用 COUI 的 [Item]，因此自带按压反馈（`couiButtonFeedback(list = true)`）。
 * `tone` 只影响标题颜色——摘要与右侧内容各有各的语义，不该被一行染成一个颜色。
 */
@Composable
fun EvRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    tone: EvTone = EvTone.Normal,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Item(
        modifier = modifier,
        title = { EvText(title, kind = EvTextKind.Body, tone = tone, maxLines = 1) },
        summary = summary?.let { { EvText(it, kind = EvTextKind.Caption, tone = EvTone.Muted, maxLines = 2) } },
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
    )
}

/** 分隔线。COUI 的 [Divider] 自带两边缩进，与 [Item] 的左右对齐。 */
@Composable
fun EvDivider(modifier: Modifier = Modifier) {
    Divider(modifier = modifier)
}

/** 语义 → 颜色。集中在一处，新增语义只改这里。 */
@Composable
internal fun EvTone.color(): Color {
    val colors = EvColors
    return when (this) {
        EvTone.Normal -> colors.textPrimary
        EvTone.Muted -> colors.textTertiary
        EvTone.Accent -> colors.accent
        EvTone.Warning -> colors.warning
        EvTone.Danger -> colors.danger
        EvTone.OnAccent -> colors.onAccent
    }
}
