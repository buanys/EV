package ev.buan.plugins.ev.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.plugins.ev.ui.ui.EvChatBubble
import ev.buan.plugins.ev.ui.ui.EvChatDefaults
import ev.buan.plugins.ev.ui.ui.EvMessageEntry
import ev.buan.plugins.ev.ui.ui.EvTypingIndicator
import ev.buan.plugins.ev.ui.ui.EvHomeHero
import ev.buan.plugins.ev.ui.ui.EvSessionSummary
import ev.buan.plugins.ev.ui.ui.EvSuggestion
import ev.buan.ui.EvThemeTokens
import java.time.Instant
import java.time.ZoneId
import top.yukonga.miuix.kmp.basic.Text

/**
 * The conversation transcript.
 *
 * Split out of EvUiPlugin's surface so the page keeps routing and state while this keeps only
 * layout: every value it needs is passed in, and the two mutating callbacks are the only way back
 * out. That also makes the per-message rhythm testable by reasoning rather than by reading an
 * 800-line function.
 */
@Composable
internal fun EvMessageList(
    sessionId: String,
    messages: List<ChatMessage>,
    listState: LazyListState,
    liveRounds: List<RoundTrace>,
    /** Assistant text of the round in flight, growing as the model writes it. */
    streamingText: String,
    replying: Boolean,
    awaitingModel: Boolean,
    finalizing: Boolean,
    workExpansion: SnapshotStateMap<String, Boolean>,
    onRegenerate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Messages present when the session opened render still; only arrivals
    // after this snapshot play the entrance spring (spec §6.3).
    val seenIds = remember(sessionId) {
        messages.mapTo(hashSetOf()) { it.id }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = EvChatDefaults.PagePadding,
            end = EvChatDefaults.PagePadding,
            top = 8.dp,
            // The composer floats over the last stretch of the list, so this is the space that
            // keeps the final message readable above it while still letting earlier content
            // slide underneath the field.
            bottom = 116.dp,
        ),
    ) {
        // Top-aligned, oldest first: a short conversation grows down from under
        // the top bar instead of floating above the composer (spec §3.4).
        val ordered = messages
        itemsIndexed(ordered, key = { _, it -> it.id }) { index, message ->
            // Variable rhythm instead of a uniform gap (spec §3.1): same-side
            // neighbours sit 4dp apart, a side switch opens 12dp.
            val previous = ordered.getOrNull(index - 1)
            val next = ordered.getOrNull(index + 1)
            val gap = when {
                index == 0 -> 0.dp
                previous?.fromUser == message.fromUser -> EvChatDefaults.SameSideGap
                else -> EvChatDefaults.SwitchSideGap
            }
            EvMessageEntry(
                animate = message.id !in seenIds,
                modifier = Modifier.padding(top = gap),
            ) {
              Column {
                // One block for the whole turn: the summary line plus a
                // timeline, rebuilt from the events that were stored with it.
                val doneSteps = workStepsOfEvents(message.events)
                if (doneSteps.isNotEmpty()) {
                    val workKey = message.id
                    val workOpen = workExpansion[workKey] == true
                    EvWorkProcess(
                        steps = doneSteps,
                        expanded = workOpen,
                        onToggle = { workExpansion[workKey] = !workOpen },
                        label = workLabel(doneSteps, active = false),
                        active = false,
                    )
                    // The collapsed row is 10dp of padding tall; without this
                    // the answer underneath starts flush against it and the two
                    // read as one mashed block.
                    Spacer(Modifier.height(10.dp))
                }
                EvChatBubble(
                    text = message.text,
                    fromUser = message.fromUser,
                    // The timeline above owns the tool work now; the bubble
                    // no longer repeats it.
                    events = emptyList(),
                    lastInCluster = message.fromUser && next?.fromUser != true,
                    streaming = replying && index == ordered.lastIndex && !message.fromUser,
                    onRegenerate = if (message.fromUser) null else {
                        { onRegenerate(message.id) }
                    },
                    onDelete = if (message.fromUser) null else {
                        { onDelete(message.id) }
                    },
                )
                message.stopNote?.let { note ->
                    Text(
                        text = note,
                        style = EvThemeTokens.type.caption.style(
                            EvThemeTokens.colors.textTertiary,
                        ),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
              }
            }
        }
        // The in-flight turn shows the same block, expanded and saying what
        // it is doing right now instead of how much it did.
        val liveSteps = liveRounds.flatMap { it.steps }
        if (liveSteps.isNotEmpty()) {
            val workKey = "live"
            val workOpen = workExpansion[workKey] ?: true
            item(key = "live-work") {
                EvWorkProcess(
                    steps = liveSteps,
                    expanded = workOpen,
                    onToggle = { workExpansion[workKey] = !workOpen },
                    label = workLabel(liveSteps, active = true),
                    active = true,
                    modifier = Modifier.padding(
                        start = EvChatDefaults.PagePadding,
                        end = EvChatDefaults.PagePadding,
                    ),
                )
            }
        }
        // The answer being typed, before it becomes a stored message. It sits after the work
        // timeline for the same reason the stored layout does: the tool work explains the reply,
        // so it reads above it.
        if (streamingText.isNotBlank()) {
            item(key = "live-text") {
                EvChatBubble(
                    text = streamingText,
                    fromUser = false,
                    events = emptyList(),
                    lastInCluster = false,
                    streaming = true,
                    onRegenerate = null,
                    onDelete = null,
                    modifier = Modifier.padding(top = EvChatDefaults.SwitchSideGap),
                )
            }
        }
        // Dots mean one thing only: the model is thinking. While a tool runs, or while text is
        // already appearing, there is real progress on screen, so they disappear.
        if (replying && awaitingModel && streamingText.isBlank()) {
            item { EvTypingIndicator(label = if (finalizing) "正在总结…" else null) }
        }
    }
}

/**
 * The empty conversation: what the agent can do right now, and the three places the person left
 * off. Recents are computed here rather than in the surface so the surface only passes raw state.
 */
@Composable
internal fun EvChatEmptyState(
    sessions: List<ChatSession>,
    activeSessionId: String,
    capabilityLine: String,
    suggestions: List<EvSuggestion>,
    onPick: (EvSuggestion) -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val recents = sessions
        .filter { it.id != activeSessionId && it.messages.isNotEmpty() }
        .sortedByDescending { it.updatedAt }
        .take(3)
        .map { session ->
            EvSessionSummary(
                id = session.id,
                title = session.displayTitle(),
                selected = false,
                timeLabel = HISTORY_TIME.format(
                    Instant.ofEpochMilli(session.updatedAt).atZone(zone),
                ),
            )
        }
    EvHomeHero(
        suggestions = suggestions,
        onPick = onPick,
        status = capabilityLine,
        recents = recents,
        onResume = onResume,
        modifier = modifier,
    )
}
