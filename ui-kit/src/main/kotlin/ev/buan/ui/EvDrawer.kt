package ev.buan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

object EvDrawerDefaults {
    /** 78% of the screen, never wider than 320dp (spec §7.4). */
    const val WidthFraction = 0.78f
    val MaxWidth = 320.dp
    val NavItemHeight = 52.dp
}

data class EvDrawerItem(
    val label: String,
    val glyph: EvGlyphKind,
    val badge: String? = null,
    val onClick: () -> Unit,
)

data class EvHistoryGroup(val label: String, val titles: List<String>, val selected: String? = null)

/**
 * Slide-over navigation on the motion language (spec §6.3, §7.4).
 *
 * One spring-driven progress value runs everything: the drawer slides in, the content behind it
 * scales to 0.94 and dims under the scrim - the "space" between the two layers is what makes the
 * drawer feel physical instead of pasted on. The drawer follows a closing drag with the finger
 * and flings to its resting state on release; with reduced motion every layer degrades to a
 * plain fade.
 */
/**
 * Finger travel to progress multiplier. 1.0 is literal (one drawer width of travel = fully closed),
 * which reads as sluggish; dismissal is a gesture, not a measurement, so it is amplified.
 */
private const val DRAG_GAIN = 1.6f

@Composable
fun EvDrawerScaffold(
    open: Boolean,
    onClose: () -> Unit,
    drawer: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = EvThemeTokens.colors
    val reduced = EvThemeTokens.reducedMotion
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth = minOf(screenWidth * EvDrawerDefaults.WidthFraction, EvDrawerDefaults.MaxWidth)
    val widthPx = with(density) { drawerWidth.toPx() }
    val scope = rememberCoroutineScope()

    val progress = remember { Animatable(if (open) 1f else 0f) }
    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
        )
    }
    val p = progress.value

    /** Explicit dismissal (tap on the scrim): no velocity heuristic, it always closes. */
    fun close() {
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
            )
            onClose()
        }
    }

    fun settle(velocity: Float = 0f) {
        scope.launch {
            val closing = velocity < -450f || (velocity <= 450f && progress.value < 0.35f)
            progress.animateTo(
                targetValue = if (closing) 0f else 1f,
                animationSpec = EvMotionTokens.reducedAware(reduced) { EvMotionTokens.gentle() },
                initialVelocity = if (widthPx > 0) velocity / widthPx else 0f,
            )
            if (closing) onClose()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Content layer: recedes slightly as the drawer covers it, giving the pair real depth.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val scale = 1f - 0.06f * p
                scaleX = scale
                scaleY = scale
                translationX = widthPx * 0.06f * p
            },
        ) {
            content()
        }

        if (p > 0.001f || open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * p))
                    .pointerInput(widthPx) {
                        detectTapGestures(onTap = { close() })
                    },
            )
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset { IntOffset((-widthPx * (1f - p)).roundToInt(), 0) }
                    .clip(
                        RoundedCornerShape(
                            topEnd = EvThemeTokens.radii.large,
                            bottomEnd = EvThemeTokens.radii.large,
                        ),
                    )
                    .background(tokens.surface1)
                    .pointerInput(widthPx) {
                        var tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = { tracker = VelocityTracker() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (dragAmount < 0 || progress.value < 1f) {
                                    // Full drawer width made every drag feel heavy: 50dp of finger
                                    // travel only moved progress by ~0.16. A gain above 1 restores
                                    // the "it follows my thumb" feel without changing the travel.
                                    scope.launch {
                                        progress.snapTo(
                                            (progress.value + dragAmount * DRAG_GAIN / widthPx)
                                                .coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            },
                            onDragEnd = { settle(tracker.calculateVelocity().x) },
                            onDragCancel = { settle() },
                        )
                    },
                content = drawer,
            )
        }
    }
}

@Composable
fun EvDrawerHeader(title: String, badge: String, onNewChat: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tokens.surface2),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Sparkle, color = tokens.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = EvThemeTokens.type.title.style(tokens.textPrimary))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(EvThemeTokens.radii.smallShape)
                .background(tokens.accentSoft)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(badge, style = EvThemeTokens.type.micro.style(tokens.accent))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(tokens.surface2)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            EvGlyph(EvGlyphKind.Chat, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun EvDrawerNavItem(item: EvDrawerItem) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().height(EvDrawerDefaults.NavItemHeight)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvGlyph(item.glyph, color = tokens.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            item.label,
            style = EvThemeTokens.type.label.style(tokens.textPrimary),
            modifier = Modifier.weight(1f),
        )
        if (item.badge != null) {
            Box(
                Modifier.clip(CircleShape).background(tokens.accent)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(item.badge, style = EvThemeTokens.type.micro.style(tokens.onAccent))
            }
        }
    }
}

@Composable
fun EvDrawerSection(label: String) {
    Text(
        text = label,
        style = EvThemeTokens.type.micro.style(EvThemeTokens.colors.textTertiary),
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun EvDrawerHistory(groups: List<EvHistoryGroup>) {
    val tokens = EvThemeTokens.colors
    groups.forEach { group ->
        Text(
            text = group.label,
            style = EvThemeTokens.type.micro.style(tokens.textTertiary),
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        group.titles.forEach { title ->
            val selected = title == group.selected
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(EvThemeTokens.radii.smallShape)
                    .background(if (selected) tokens.accentSoft else Color.Transparent)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The 3dp accent bar marks the selected session (spec §7.4).
                Box(
                    Modifier.width(3.dp).height(16.dp)
                        .clip(CircleShape)
                        .background(if (selected) tokens.accent else Color.Transparent),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    style = EvThemeTokens.type.label.style(
                        if (selected) tokens.accent else tokens.textPrimary,
                    ),
                )
            }
        }
    }
}

/** Footer: current identity, then the search pill and settings affordance. */
@Composable
fun EvDrawerFooter(identity: String, onSearch: () -> Unit, onSettings: () -> Unit) {
    val tokens = EvThemeTokens.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(tokens.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("E", style = EvThemeTokens.type.label.style(tokens.onAccent))
            }
            Spacer(Modifier.width(12.dp))
            Text(identity, style = EvThemeTokens.type.label.style(tokens.textPrimary))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(40.dp)
                    .clip(EvThemeTokens.radii.fullShape)
                    .background(tokens.surface2)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EvGlyph(EvGlyphKind.Search, color = tokens.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("搜索对话", style = EvThemeTokens.type.caption.style(tokens.textTertiary))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(tokens.surface2)
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                EvGlyph(EvGlyphKind.Gear, color = tokens.textPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
