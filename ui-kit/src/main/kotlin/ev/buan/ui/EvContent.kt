package ev.buan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 文字档位：页面只说"这是什么"，不说"多大"。 */
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
            letterSpacing = token.letterSpacing,
            color = tone.color(),
        ),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * 一组行，合成一张卡片。
 *
 * 区块标题在卡片**外面**（小、疏、弱），卡片本身是一个连续表面——层次来自
 * 表面明度差，不来自边框。行与行之间没有分割线：留白已经把它们分开了，
 * 再加线只是把卡片切碎。
 */
@Composable
fun EvSection(
    title: String? = null,
    footnote: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = EvColors
    val radius = LocalEvRadiusTokens.current
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            EvText(
                title,
                kind = EvTextKind.Micro,
                tone = EvTone.Muted,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius.large))
                .background(colors.surface1),
            content = content,
        )
        if (footnote != null) {
            EvText(
                footnote,
                kind = EvTextKind.Micro,
                tone = EvTone.Muted,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
            )
        }
    }
}

/**
 * 一行：标题 + 可选摘要 + 可选右侧内容。
 *
 * 按压反馈是**缩放**（spring，可中断）加上表面提亮一级，不是涟漪：涟漪在这套扁平
 * 表面里像一圈脏水，而 0.985 的收缩在手指下面更接近"按下去了一点"。
 *
 * 最小高度 56dp 保证触摸目标，长文本按 maxLines 截断。
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
    val colors = EvColors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = EvMotionTokens.snappy(),
        label = "row-press",
    )
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(clickable)
            .background(if (pressed && onClick != null) colors.surface2 else Color.Transparent)
            .heightIn(min = 52.dp)
            .padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                EvText(
                    title,
                    kind = EvTextKind.Body,
                    tone = if (enabled) tone else EvTone.Muted,
                    maxLines = 1,
                )
                if (summary != null) {
                    EvText(summary, kind = EvTextKind.Caption, tone = EvTone.Muted, maxLines = 2)
                }
            }
            trailing()
        }
    }
}

/** 卡片内部的一条弱分割线；两边缩进，与文字对齐而不是切满整张卡。 */
@Composable
fun EvDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp)
            .heightIn(min = 1.dp)
            .background(EvColors.dividerGhost),
    )
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
