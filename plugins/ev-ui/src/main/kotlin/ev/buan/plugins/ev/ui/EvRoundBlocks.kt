package ev.buan.plugins.ev.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.StepState
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * One round of the agent's work, as a collapsed line or an expanded trace.
 *
 * The visual weight is deliberately tiny: this is context for "what is it doing", not content the
 * user came for, so it stays in caption size, grey, and never competes with the reply bubble.
 */
@Composable
internal fun EvRoundBlock(
    round: RoundTrace,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val caption = EvThemeTokens.type.caption.style(tokens.textTertiary)
    val reduced = EvThemeTokens.reducedMotion

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(
                EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = round.caption(),
                style = caption,
                color = if (round.failed > 0) tokens.textSecondary else tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▾" else "▸",
                style = caption,
                color = tokens.textTertiary,
            )
        }
        if (expanded) {
            round.steps.forEach { step -> StepRow(step) }
        }
    }
}

/** Stored summary of a finished round, rendered with the same line the live block used. */
@Composable
internal fun EvTraceSummaryBlock(summary: TraceSummary, modifier: Modifier = Modifier) {
    val tokens = EvThemeTokens.colors
    val caption = EvThemeTokens.type.caption.style(tokens.textTertiary)
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "思考 1 次 · 调用 ${summary.toolCalls} 个工具" +
                if (summary.failed > 0) " · ${summary.failed} 个失败" else "",
            style = caption,
            color = if (summary.failed > 0) tokens.textSecondary else tokens.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        summary.firstError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = caption,
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun StepRow(step: ProgressStep) {
    val tokens = EvThemeTokens.colors
    val caption = EvThemeTokens.type.caption.style(tokens.textTertiary)
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = step.marker(),
                style = caption,
                color = if (step.state == StepState.Failed) tokens.textSecondary else tokens.textTertiary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${step.name} ${step.statusWord()}",
                style = caption,
                color = if (step.state == StepState.Failed) tokens.textSecondary else tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Only failures show detail: a successful tool's output is already reflected in the reply,
        // and a failed one is the thing a person actually wants to see.
        if (step.state == StepState.Failed || step.state == StepState.Cancelled) {
            step.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview.lineSequence().take(3).joinToString("\n"),
                    style = caption,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(start = 14.dp, top = 1.dp),
                )
            }
        }
    }
}

private fun RoundTrace.caption(): String {
    val base = "思考 1 次 · 调用 ${executed} 个工具"
    return when (state) {
        RoundState.Running -> "思考 1 次 · 运行中…"
        RoundState.Done -> if (failed > 0) "$base · $failed 个失败" else base
    }
}

private fun ProgressStep.marker(): String = when (state) {
    StepState.Running -> "✳"
    StepState.AwaitingApproval -> "⏸"
    StepState.Ok -> "✓"
    StepState.Failed -> "✕"
    StepState.Skipped -> "·"
    StepState.Cancelled -> "⊘"
}

private fun ProgressStep.statusWord(): String = when (state) {
    StepState.Running -> "运行中…"
    StepState.AwaitingApproval -> "等待你的批准"
    StepState.Ok -> "完成"
    StepState.Failed -> "失败"
    StepState.Skipped -> "未执行"
    StepState.Cancelled -> "已取消"
}
