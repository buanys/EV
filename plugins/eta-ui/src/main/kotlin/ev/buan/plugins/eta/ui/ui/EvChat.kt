package ev.buan.plugins.eta.ui.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.eta.ui.ToolSteps
import ev.buan.plugins.eta.ui.toolStepsOf
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Chat pieces, following Eta's visual rules rather than its implementation.
 *
 * Taken from Eta: assistant replies carry no bubble (text sits on the page, wide and quiet), user
 * messages get a 20dp rounded block with a 6dp tail, max 320dp, and the composer is a 22dp pill
 * with a circular send affordance that only lights up when there is something to send.
 *
 * Deliberately not taken: streaming markdown, liquid-glass surfaces, tooltips and attachment
 * strips. Those need a renderer and a message model this host does not have yet.
 */
object EvChatDefaults {
    val BubbleMaxWidth = 320.dp
    val BubbleRadius = 20.dp
    val BubbleTailRadius = 6.dp
    val PagePadding = 20.dp
    val ComposerRadius = 22.dp

    /** Diameter of one icon button under an assistant reply, and of the glyph drawn inside it. */
    val ActionSize = 28.dp
    val ActionGlyph = 15.dp
}

/**
 * One message.
 *
 * User messages are inert: the actions below (copy / regenerate / delete) belong to the assistant
 * turn, because only it can be produced again or thrown away without losing what the user typed.
 *
 * [events] is the agent turn behind an assistant reply. When it contains tool work the bubble shows
 * a one-line caption ("思考 N 次 · 调用 N 个工具") that expands into the calls and their results;
 * a turn with no tool events shows no caption at all.
 */
@Composable
fun EvChatBubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    events: List<AgentEvent> = emptyList(),
    maxWidth: androidx.compose.ui.unit.Dp = EvChatDefaults.BubbleMaxWidth,
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (fromUser) {
            Box(
                Modifier
                    .widthIn(max = maxWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = EvChatDefaults.BubbleRadius,
                            topEnd = EvChatDefaults.BubbleRadius,
                            bottomEnd = EvChatDefaults.BubbleTailRadius,
                            bottomStart = EvChatDefaults.BubbleRadius,
                        ),
                    )
                    // Eta's own messages are a quiet grey block, not the accent colour: the accent
                    // belongs to the composer and model controls.
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackground,
                )
            }
        } else {
            // No bubble: Eta keeps assistant output flush with the page so long answers read like
            // text, not like cards stacked on cards.
            val steps = remember(events) { toolStepsOf(events) }
            var expanded by remember(steps) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                EvMarkdown(
                    text = text,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val clipboard = LocalClipboardManager.current
                    BubbleAction(EvGlyphKind.Doc, "复制") {
                        clipboard.setText(AnnotatedString(text))
                    }
                    if (onRegenerate != null) {
                        BubbleAction(EvGlyphKind.Refresh, "重新生成", onRegenerate)
                    }
                    if (onDelete != null) {
                        BubbleAction(EvGlyphKind.Trash, "删除", onDelete)
                    }
                    if (!steps.isEmpty) {
                        Spacer(Modifier.width(6.dp))
                        ToolStepToggle(
                            label = steps.label,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                        )
                    }
                }
                if (expanded && !steps.isEmpty) {
                    ToolStepPanel(steps, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** One quiet icon under a reply; [label] is the accessibility text, there is no tooltip in Eta. */
@Composable
private fun BubbleAction(kind: EvGlyphKind, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(EvChatDefaults.ActionSize)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        EvGlyph(
            kind,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(EvChatDefaults.ActionGlyph),
        )
    }
}

/** The caption that opens the step list; the whole chip is the tap target, not a tiny caret. */
@Composable
private fun ToolStepToggle(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .clickable(onClickLabel = if (expanded) "收起工具步骤" else "展开工具步骤", onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(
            EvGlyphKind.Sparkle,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * The expanded steps: one card per tool call, with a status the human can audit at a glance.
 *
 * Arguments and results render as [ev.buan.plugins.eta.ui.preview] text by default; a "展开全文"
 * toggle swaps in the complete text, so reviewing what a tool actually saw never means guessing
 * from a cut-off line. A call without a result says so explicitly instead of looking finished.
 */
@Composable
private fun ToolStepPanel(steps: ToolSteps, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        steps.steps.forEachIndexed { index, step ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.33.dp,
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                )
            }
            ToolStepCard(index + 1, step)
        }
    }
}

@Composable
private fun ToolStepCard(number: Int, step: ev.buan.plugins.eta.ui.ToolStep) {
    val answered = step.result != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$number. ${step.toolName ?: "未知工具"}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (answered) "完成" else "未返回",
            style = MiuixTheme.textStyles.footnote1,
            color = if (answered) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    if (step.arguments.isNotBlank()) {
        StepBlock(label = "参数", text = step.arguments)
    }
    StepBlock(
        label = "结果",
        text = step.result?.takeIf { it.isNotBlank() } ?: "（未返回结果）",
        expandable = answered && !step.result.isNullOrBlank(),
    )
}

/** One labelled mono block; long text starts collapsed behind [preview] and expands in place. */
@Composable
private fun StepBlock(label: String, text: String, expandable: Boolean = true) {
    val full = text.length > ev.buan.plugins.eta.ui.TOOL_TEXT_LIMIT
    var expanded by remember(text) { mutableStateOf(false) }
    val mono = MiuixTheme.textStyles.footnote1.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = if (expanded || !full || !expandable) text else ev.buan.plugins.eta.ui.preview(text),
        style = mono,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    if (full && expandable) {
        Text(
            text = if (expanded) "收起" else "展开全文（${text.length} 字）",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = if (expanded) "收起$label" else "展开完整$label") { expanded = !expanded }
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * The in-flight row: three dots breathing in sequence instead of a static "正在输入…", so a long
 * tool turn reads as ongoing work rather than a stuck label.
 */
@Composable
fun EvTypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
fun EvChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "说点什么…",
) {
    val canSend = value.isNotBlank()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.background),
    ) {
        HorizontalDivider(
            thickness = 0.33.dp,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(EvChatDefaults.ComposerRadius))
                .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                )
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↑",
                style = MiuixTheme.textStyles.subtitle,
                    color = if (canSend) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
