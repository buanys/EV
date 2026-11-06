package ev.buan.plugins.agent.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.ui.EvCard
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvThemeTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text

/** A section label: quiet, numbered, and never competing with the card it introduces. */
@Composable
internal fun SectionLabel(title: String, trailing: String) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = type.caption.style(tokens.textSecondary).copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
        )
        Text(text = trailing, style = type.caption.style(tokens.textTertiary))
    }
}

@Composable
internal fun EmptyCard(
    icon: EvGlyphKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val type = EvThemeTokens.type
    EvCard(modifier = modifier, insideMargin = PaddingValues(24.dp)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EvGlyph(kind = icon, color = tokens.textTertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(text = title, style = type.label.style(tokens.textSecondary))
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = type.caption.style(tokens.textTertiary),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun LoadingRow() {
    Text(
        text = "读取中…",
        style = EvThemeTokens.type.body.style(EvThemeTokens.colors.textTertiary),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** The hairline inside a card: indented, ghost weight, never full-bleed. */
@Composable
internal fun Hairline(startIndent: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(EvThemeTokens.colors.dividerGhost),
    )
}

internal fun kindLabel(kind: String): String = when (kind) {
    MemoryNote.KIND_PREFERENCE -> "偏好"
    MemoryNote.KIND_FACT -> "事实"
    MemoryNote.KIND_TASK -> "任务"
    MemoryNote.KIND_CONVENTION -> "约定"
    else -> kind
}

internal fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val target = Date(millis)
    val today = day.format(Date())
    if (day.format(target) == today) return "今天 ${clock.format(target)}"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (day.format(target) == day.format(yesterday.time)) return "昨天 ${clock.format(target)}"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(target)
}
