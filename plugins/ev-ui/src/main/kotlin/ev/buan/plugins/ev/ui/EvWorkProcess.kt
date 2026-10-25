package ev.buan.plugins.ev.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.StepState
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens
import top.yukonga.miuix.kmp.basic.Text

/**
 * What the agent did before it answered, as one collapsible block.
 *
 * The whole turn shares a single header: a long task is twenty rounds and sixty calls, and one
 * line per round turns the transcript into a wall of near-identical summaries. What a person wants
 * at a glance is "how much work", and on demand "which work, in what order".
 *
 * Auto-collapse keys off [active] (is this the turn still running), not off "any tool running":
 * between a thinking step ending and a tool starting nothing is running for a moment, and
 * collapsing on that flickers the block open and shut on every round.
 */
@Composable
internal fun EvWorkProcess(
    steps: List<ProgressStep>,
    expanded: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    failed: Int = steps.count { it.state == StepState.Failed },
) {
    if (steps.isEmpty()) return
    val tokens = EvThemeTokens.colors
    val caption = EvThemeTokens.type.caption.style(tokens.textTertiary)
    val reduced = EvThemeTokens.reducedMotion

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = caption,
                color = if (failed > 0) tokens.textSecondary else tokens.textTertiary,
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
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(EvMotionTokens.fadeFast()) + expandVertically(
                EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            ),
            exit = fadeOut(EvMotionTokens.fadeFast()) + shrinkVertically(
                EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            ),
        ) {
            Column(Modifier.padding(bottom = 4.dp)) {
                groupWorkSteps(steps).forEachIndexed { index, step ->
                    WorkStepNode(
                        step = step,
                        isLast = index == steps.lastIndex,
                        compact = steps.size > 6,
                    )
                }
            }
        }
    }
}

/** The header line: what it is doing now, or what it did. */
internal fun workLabel(steps: List<ProgressStep>, active: Boolean): String {
    if (steps.isEmpty()) return "思考中…"
    val running = steps.lastOrNull { it.state == StepState.Running || it.state == StepState.AwaitingApproval }
    if (active && running != null) {
        val index = steps.indexOf(running) + 1
        return if (running.state == StepState.AwaitingApproval) {
            "第 $index 步 · ${running.name} · 等待你的批准"
        } else {
            "第 $index 步 · ${running.name} 运行中…"
        }
    }
    val calls = steps.count { it.state != StepState.Skipped }
    val failed = steps.count { it.state == StepState.Failed }
    val base = "调用 $calls 个工具"
    return if (failed > 0) "$base · $failed 个失败" else base
}

/**
 * One node of the timeline: a single call, or a run of consecutive calls to the same tool.
 *
 * Grouping is what makes a thirty-file task readable: thirty identical `file_write` rows say
 * nothing that `file_write ×30` does not, and expanding the group still shows every one.
 */
internal sealed interface WorkStep {
    val key: String

    data class One(val step: ProgressStep) : WorkStep {
        override val key: String = step.id
    }

    data class Group(val name: String, val calls: List<ProgressStep>) : WorkStep {
        override val key: String = "group-" + calls.first().id
        val running: Boolean get() = calls.any { it.state == StepState.Running }
        val failed: Int get() = calls.count { it.state == StepState.Failed }
    }
}

/** Consecutive same-tool calls collapse into one node; order is preserved. */
internal fun groupWorkSteps(steps: List<ProgressStep>): List<WorkStep> = buildList {
    val run = mutableListOf<ProgressStep>()

    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> add(WorkStep.One(run.single()))
            else -> add(WorkStep.Group(run.first().name, run.toList()))
        }
        run.clear()
    }

    steps.forEach { step ->
        if (run.isNotEmpty() && run.first().name != step.name) flush()
        run += step
    }
    flush()
}

/**
 * Rebuilds the timeline from a stored reply.
 *
 * Events are the only record of the tool work that survives a reload, so this is what makes a
 * finished turn expandable after the app restarts. A result's state is inferred from its text:
 * the events predate the `StepState` field, and "did it fail" is the one thing the text always
 * makes obvious.
 */
internal fun workStepsOfEvents(events: List<AgentEvent>): List<ProgressStep> {
    val calls = events.filter { it.kind == AgentEvent.Kind.TOOL_CALL }
    if (calls.isEmpty()) return emptyList()
    return calls.map { call ->
        val result = events.firstOrNull {
            it.kind == AgentEvent.Kind.TOOL_RESULT && it.toolCallId == call.toolCallId
        }
        ProgressStep(
            id = call.toolCallId ?: call.toolName.orEmpty(),
            name = call.toolName?.takeIf { it.isNotBlank() } ?: "unknown",
            argsJson = call.argumentsJson.orEmpty(),
            state = when {
                result == null -> StepState.Ok
                looksFailed(result.text.orEmpty()) -> StepState.Failed
                else -> StepState.Ok
            },
            startedAt = 0L,
            preview = result?.text,
        )
    }
}

private fun looksFailed(text: String): Boolean =
    text.startsWith("工具调用失败") ||
        text.startsWith("已拦截") ||
        text.contains("超时") ||
        text.startsWith("用户拒绝") ||
        text.startsWith("没有名为")

@Composable
private fun WorkStepNode(step: WorkStep, isLast: Boolean, compact: Boolean) {
    val tokens = EvThemeTokens.colors
    val caption = EvThemeTokens.type.caption.style(tokens.textTertiary)
    val failed = when (step) {
        is WorkStep.One -> step.step.state == StepState.Failed
        is WorkStep.Group -> step.failed > 0
    }

    Row(Modifier.fillMaxWidth()) {
        // The spine: a dot per node and a line down to the next one.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp).padding(top = 5.dp),
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (failed) tokens.textSecondary else tokens.textTertiary.copy(alpha = 0.6f),
                    ),
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(1.dp)
                        .size(width = 1.dp, height = 16.dp)
                        .background(tokens.textTertiary.copy(alpha = 0.25f)),
                )
            }
        }
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 6.dp)) {
            when (step) {
                is WorkStep.One -> StepLine(step.step, caption, compact)
                is WorkStep.Group -> {
                    Text(
                        text = step.name + " ×" + step.calls.size +
                            if (step.failed > 0) " · ${step.failed} 个失败" else "",
                        style = caption,
                        color = if (step.failed > 0) tokens.textSecondary else tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepLine(step: ProgressStep, caption: androidx.compose.ui.text.TextStyle, compact: Boolean) {
    val tokens = EvThemeTokens.colors
    Text(
        text = step.name + " " + step.state.word(),
        style = caption,
        color = if (step.state == StepState.Failed) tokens.textSecondary else tokens.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // Failures show why; successes would just repeat what the answer already says.
    if (step.state == StepState.Failed && !compact) {
        step.preview?.takeIf { it.isNotBlank() }?.let { preview ->
            Spacer(Modifier.width(4.dp))
            Text(
                text = preview.lineSequence().take(2).joinToString(" ").take(160),
                style = caption,
                color = tokens.textTertiary,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private fun StepState.word(): String = when (this) {
    StepState.Running -> "运行中…"
    StepState.AwaitingApproval -> "等待你的批准"
    StepState.Ok -> "完成"
    StepState.Failed -> "失败"
    StepState.Skipped -> "未执行"
    StepState.Cancelled -> "已取消"
}
