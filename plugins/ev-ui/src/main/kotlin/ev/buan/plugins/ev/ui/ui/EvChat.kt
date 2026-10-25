package ev.buan.plugins.ev.ui.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.ev.ui.ToolSteps
import ev.buan.plugins.ev.ui.toolStepsOf
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text

/**
 * Message stream, restyled against docs/design/EV-UI设计规范.md (§7.1 聊天气泡, §7.2 折叠卡,
 * §6.3 动效清单). All values come from EvThemeTokens; nothing here invents a colour or a spring.
 *
 * The rules that matter: assistant output carries no bubble (it reads as a document, flush with
 * the page), user messages are surface3 blocks with an asymmetric tail corner, consecutive
 * same-side messages form a cluster (4dp gap, only the last keeps its tail), and every entrance
 * is a spring that honors the reduced-motion preference.
 */
object EvChatDefaults {
    /** Page margin of the message stream (spec §3.1). */
    val PagePadding = 16.dp

    /** User bubbles cap at 78% of the screen width (spec §7.1). */
    const val BubbleMaxWidthFraction = 0.78f

    /** Gap between two bubbles from the same side, and across a side switch (spec §3.1). */
    val SameSideGap = 4.dp
    val SwitchSideGap = 12.dp

    /** Touch target under a reply (spec §9, >= 44dp), and the glyph drawn inside it. */
    val ActionSize = 44.dp
    val ActionGlyph = 16.dp
}

/**
 * One message.
 *
 * User messages are inert: the actions below (copy / regenerate / delete) belong to the assistant
 * turn, because only it can be produced again or thrown away without losing what the user typed.
 *
 * [events] is the agent turn behind an assistant reply. When it contains tool work the message
 * shows a one-line caption ("思考 N 次 · 调用 N 个工具") that expands into the calls and their
 * results; a turn with no tool events shows no caption at all.
 *
 * [lastInCluster] keeps the user bubble's tail corner only on the final message of a run.
 * [streaming] appends the breathing accent cursor to an in-flight assistant reply (spec §7.1).
 */
@Composable
fun EvChatBubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    events: List<AgentEvent> = emptyList(),
    lastInCluster: Boolean = true,
    streaming: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val tokens = EvThemeTokens.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (fromUser) {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            Box(
                Modifier
                    .widthIn(max = screenWidth * EvChatDefaults.BubbleMaxWidthFraction)
                    .clip(EvThemeTokens.radii.userBubbleShape(lastInCluster))
                    .background(tokens.surface3)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(text = text, style = EvThemeTokens.type.body.style(tokens.textPrimary))
            }
        } else {
            // No bubble: assistant output stays flush with the page so long answers read like
            // text, not like cards stacked on cards.
            val steps = remember(events) { toolStepsOf(events) }
            var expanded by remember(steps) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    EvMarkdown(text = text, modifier = Modifier.weight(1f, fill = false))
                    if (streaming) EvStreamingCursor()
                }
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

/**
 * One-shot entrance for a message that arrived while the list was on screen: a snappy spring
 * from 24dp below, 0.9 scale and transparent, per spec §6.3. History that was already there
 * when the session opened passes [animate] = false and renders still. Reduced motion (spec §6.4)
 * degrades the whole thing to an instant appearance.
 */
@Composable
fun EvMessageEntry(
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = EvThemeTokens.reducedMotion
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, EvMotionTokens.snappy())
    }
    val p = progress.value
    Box(
        modifier.graphicsLayer {
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 24.dp.toPx()
            val scale = 0.9f + 0.1f * p
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}

/** The 2dp accent bar breathing at 500ms while a reply streams in (spec §7.1). */
@Composable
private fun EvStreamingCursor() {
    val reduced = EvThemeTokens.reducedMotion
    val alpha = if (reduced) {
        0.85f
    } else {
        val transition = rememberInfiniteTransition(label = "streamCursor")
        val a by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        a
    }
    Box(
        Modifier
            .padding(start = 3.dp, bottom = 3.dp)
            .width(2.dp)
            .height(18.dp)
            .background(EvThemeTokens.colors.accent.copy(alpha = alpha)),
    )
}

/** One quiet icon under a reply; [label] is the accessibility text. Touch target is 44dp (§9). */
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
            color = EvThemeTokens.colors.textTertiary,
            modifier = Modifier.size(EvChatDefaults.ActionGlyph),
        )
    }
}

/**
 * The collapsed tool-work caption (spec §7.2): one quiet row - micro glyph, caption text,
 * trailing caret - all tertiary. The expanded card below it carries the detail.
 */
@Composable
private fun ToolStepToggle(label: String, expanded: Boolean, onToggle: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier
            .clip(EvThemeTokens.radii.smallShape)
            .clickable(onClickLabel = if (expanded) "收起工具步骤" else "展开工具步骤", onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(
            EvGlyphKind.Sparkle,
            color = tokens.textTertiary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            style = EvThemeTokens.type.caption.style(tokens.textTertiary),
        )
    }
}

/**
 * The expanded steps: one card per tool call, with a status the human can audit at a glance.
 *
 * Arguments and results render as [ev.buan.plugins.ev.ui.preview] text by default; a "展开全文"
 * toggle swaps in the complete text, so reviewing what a tool actually saw never means guessing
 * from a cut-off line. A call without a result says so explicitly instead of looking finished.
 */
@Composable
private fun ToolStepPanel(steps: ToolSteps, modifier: Modifier = Modifier) {
    val tokens = EvThemeTokens.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(EvThemeTokens.radii.mediumShape)
            .background(tokens.surface1)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        steps.steps.forEachIndexed { index, step ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.33.dp,
                    color = tokens.dividerGhost,
                )
            }
            ToolStepCard(index + 1, step)
        }
    }
}

@Composable
private fun ToolStepCard(number: Int, step: ev.buan.plugins.ev.ui.ToolStep) {
    val tokens = EvThemeTokens.colors
    val answered = step.result != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$number. ${step.toolName ?: "未知工具"}",
            style = EvThemeTokens.type.caption.style(tokens.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (answered) "完成" else "未返回",
            style = EvThemeTokens.type.caption.style(
                if (answered) tokens.success else tokens.textTertiary,
            ),
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
    val tokens = EvThemeTokens.colors
    val full = text.length > ev.buan.plugins.ev.ui.TOOL_TEXT_LIMIT
    var expanded by remember(text) { mutableStateOf(false) }
    val mono = EvThemeTokens.type.caption.style(tokens.textSecondary).copy(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = label, style = EvThemeTokens.type.micro.style(tokens.textTertiary))
    Spacer(Modifier.height(2.dp))
    Text(
        text = if (expanded || !full || !expandable) text else ev.buan.plugins.ev.ui.preview(text),
        style = mono,
    )
    if (full && expandable) {
        Text(
            text = if (expanded) "收起" else "展开全文（${text.length} 字）",
            style = EvThemeTokens.type.caption.style(tokens.accent),
            modifier = Modifier
                .clip(EvThemeTokens.radii.smallShape)
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
fun EvTypingIndicator(modifier: Modifier = Modifier, label: String? = null) {
    val reduced = EvThemeTokens.reducedMotion
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by if (reduced) {
                remember { mutableStateOf(0.6f) }
            } else {
                transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, delayMillis = index * 200),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
            }
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(EvThemeTokens.colors.textTertiary.copy(alpha = alpha)),
            )
        }
        // The dots alone cannot distinguish "waiting for the model" from "writing the summary",
        // and the wrap-up call is the one moment the user is most likely to think it hung.
        if (label != null) {
            Text(
                text = label,
                style = EvThemeTokens.type.caption.style(EvThemeTokens.colors.textTertiary),
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}
