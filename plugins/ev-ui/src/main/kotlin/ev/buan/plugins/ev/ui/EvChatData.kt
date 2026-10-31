package ev.buan.plugins.ev.ui

import ev.buan.plugins.ev.ui.ui.EvHistoryGroup
import ev.buan.plugins.ev.ui.ui.EvSessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Conversation labels and their grouping for the drawer and the home hero.
 *
 * Moved out of EvUiPlugin.kt because it is data, not UI: two surfaces render these groups and the
 * plugin class should not be the place a date formatter lives.
 */
internal val HISTORY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What a conversation is called: the first line of its first user turn.
 *
 * Older sessions stored a hard-cut 18-character title ("…再跑一次 l"), which is both ugly and
 * identical across every conversation that opened with the same sentence. Deriving the label from
 * the message repairs the existing history instead of only fixing sessions created from now on.
 */
internal fun ChatSession.displayTitle(): String =
    messages.firstOrNull { it.fromUser }
        ?.text
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: title

internal fun historyGroups(
    sessions: List<ChatSession>,
    selectedId: String,
    query: String,
): List<EvHistoryGroup> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val visible = if (query.isBlank()) {
        sessions
    } else {
        sessions.filter { it.title.contains(query, ignoreCase = true) }
    }
    return visible
        .sortedByDescending { it.updatedAt }
        .groupBy { session ->
            val date = Instant.ofEpochMilli(session.updatedAt).atZone(zone).toLocalDate()
            when {
                date == today -> "今天"
                date == today.minusDays(1) -> "昨天"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
        }
        .map { (label, list) ->
            EvHistoryGroup(
                label = label,
                sessions = list.map { session ->
                    EvSessionSummary(
                        id = session.id,
                        title = session.displayTitle(),
                        selected = session.id == selectedId,
                        // The clock time is what separates three conversations that all start with
                        // the same sentence; a bare title makes them look like duplicate rows.
                        timeLabel = HISTORY_TIME.format(
                            Instant.ofEpochMilli(session.updatedAt).atZone(zone),
                        ),
                    )
                },
            )
        }
}

