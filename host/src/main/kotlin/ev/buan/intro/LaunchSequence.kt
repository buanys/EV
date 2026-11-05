package ev.buan.intro

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.HostRuntime
import ev.buan.ui.evSystemBarsPadding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The launch sequence: one line that changes shape, and words that hand the anchor to each other.
 *
 * The whole sequence is a single line seen at increasing weight:
 *
 *   LINE    one hairline grows out of the centre; one phrase. Almost nothing on screen.
 *   MULTI   the line multiplies and turns; the phrase changes. Still light.
 *   SPHERE  the lines wrap into a globe; the theme arrives. Middle weight.
 *   TICKS   the globe flattens into a rule of ticks - one tick per real module, lighting as the
 *           host reports each one online. This is the load indicator: progress is the line itself,
 *           not a bar bolted on beside it.
 *   LOCKUP  the ticks close into one anchor rule; the wordmark and the epigraph lock in.
 *
 * Because every beat is the same element at a different weight, there is nothing to cut between.
 *
 * The epigraph 万物皆插件 sits as a vertical column on the right, next to the wordmark: a seal
 * beside the name, lighter and half a beat later, so the name stays the heaviest thing on screen.
 */
private enum class Beat { Line, Multi, Sphere, Ticks, Lockup }

/* Words drawn from what EV actually is: its features really are containers in EV/mods. */
private val PHRASES = listOf(
    "界面是插件",
    "工具是插件",
    "记忆是插件",
    "模型是插件",
    "连系统也是插件",
)

private const val EPIGRAPH = "万物皆插件"

private const val T_LINE_END = 1.5f
private const val T_MULTI_END = 3.2f
private const val T_SPHERE_END = 5.6f

@Composable
fun LaunchSequence(runtime: HostRuntime, onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val planet = remember { Planet() }
    val coast = remember { Coastlines() }
    val progress by runtime.loadProgress.collectAsState()

    var elapsed by remember { mutableFloatStateOf(0f) }
    var loadedAt by remember { mutableFloatStateOf(-1f) }
    var tick by remember { mutableIntStateOf(0) }
    var lastBeat by remember { mutableStateOf(Beat.Line) }

    LaunchedEffect(Unit) {
        if (reduced) onDone() else runCatching { runtime.start() }
    }

    // The clock. Every visual value is derived from it, so the sequence cannot drift apart.
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0166f else ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                elapsed += dt.coerceIn(0f, 0.05f)
                tick++
            }
        }
    }

    // The load gate: capture the moment the host finished, so the last beats can be timed from it.
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        combine(runtime.loadProgress, runtime.stateFlow()) { p, host -> p.finished && host != null }
            .first { it }
        loadedAt = elapsed
    }

    val e = elapsed
    // The tick beat exists to show the real load, but a fast load must not skip it: the rule needs
    // a minimum time on screen to be read, even when everything is already online.
    val tickElapsed = e - T_SPHERE_END
    val canLock = loadedAt >= 0f && tickElapsed > 1.8f
    val beat = when {
        e < T_LINE_END -> Beat.Line
        e < T_MULTI_END -> Beat.Multi
        e < T_SPHERE_END -> Beat.Sphere
        !canLock -> Beat.Ticks
        else -> Beat.Lockup
    }

    // A light haptic on each beat change, heavier as the sequence gains weight.
    LaunchedEffect(beat) {
        if (reduced) return@LaunchedEffect
        if (beat != lastBeat) {
            context.tick(if (beat == Beat.Lockup) 22L else 12L)
            lastBeat = beat
        }
        if (beat == Beat.Lockup) {
            // Give the finished lockup a beat to be read, then hand over to the app.
            kotlinx.coroutines.delay(2600)
            onDone()
        }
    }

    val total = progress.names.size
    val done = progress.done

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick >= 0) {
                when (beat) {
                    Beat.Line -> drawLineBeat(e / T_LINE_END)
                    Beat.Multi -> drawMultiBeat((e - T_LINE_END) / (T_MULTI_END - T_LINE_END))
                    Beat.Sphere -> {
                        val local = (e - T_MULTI_END) / (T_SPHERE_END - T_MULTI_END)
                        // The last third of the beat is the globe flattening toward a line: the
                        // transition into the tick rule is this same object being squashed.
                        val flatten = ((local - 0.66f) / 0.34f).coerceIn(0f, 1f)
                        val k = 1f - flatten * 0.97f
                        val reveal = (local / 0.35f).coerceIn(0f, 1f)
                        scale(1f, k, pivot = center) {
                            drawPlanet(
                                planet = planet,
                                coast = coast,
                                center = center,
                                radius = minOf(size.width, size.height) * 0.36f,
                                spin = e * 0.55f,
                                zoom = 1f,
                                coastAlpha = (local * 1.6f).coerceIn(0f, 1f),
                                ringReveal = reveal,
                                fade = 1f - flatten * 0.55f,
                            )
                        }
                    }
                    Beat.Ticks -> drawTicks(
                        names = progress.names,
                        done = done,
                        current = progress.current,
                        local = if (loadedAt < 0f) -1f else (e - loadedAt),
                    )
                    Beat.Lockup -> drawAnchorRule()
                }
            }
        }

        // ── text layer ────────────────────────────────────────────────────────
        when (beat) {
            Beat.Line -> PhraseText(
                text = PHRASES[0],
                alpha = (e / 0.6f).coerceIn(0f, 1f) * (1f - ((e - 1.15f) / 0.35f).coerceIn(0f, 1f)),
                size = 15,
            )
            Beat.Multi -> {
                // One phrase at a time, each handing over to the next.
                val local = (e - T_LINE_END) / (T_MULTI_END - T_LINE_END)
                val slot = (local * 3f).toInt().coerceIn(0, 2)
                PhraseText(
                    text = PHRASES[1 + slot],
                    alpha = 1f,
                    size = 16,
                )
            }
            Beat.Sphere -> {
                val local = (e - T_MULTI_END) / (T_SPHERE_END - T_MULTI_END)
                if (local < 0.62f) {
                    PhraseText(text = PHRASES[4], alpha = 1f, size = 16)
                } else {
                    // The theme itself, arriving as the globe completes.
                    PhraseText(
                        text = EPIGRAPH,
                        alpha = ((local - 0.62f) / 0.2f).coerceIn(0f, 1f),
                        size = 22,
                    )
                }
            }
            Beat.Ticks -> {
                Column(
                    Modifier.fillMaxSize().evSystemBarsPadding().padding(horizontal = 28.dp, vertical = 30.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    BasicText(
                        text = if (total == 0) "SCANNING" else "%02d / %02d".format(done, total),
                        style = TextStyle(
                            color = EvCyber.Cyan.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            letterSpacing = 3.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        text = progress.current ?: "…",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
            Beat.Lockup -> Lockup(done)
        }
    }
}

/* ── text helpers ──────────────────────────────────────────────────────────── */

/** A phrase floating under the line, centred. */
@Composable
private fun PhraseText(text: String, alpha: Float, size: Int) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
                fontSize = size.sp,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The final lockup: name heaviest and centred, epigraph as a vertical seal on the right, both
 * resting on the anchor rule the ticks closed into.
 */
@Composable
private fun Lockup(modules: Int) {
    Box(Modifier.fillMaxSize().evSystemBarsPadding().padding(horizontal = 26.dp, vertical = 28.dp)) {
        // Shifted above the anchor rule so nothing crosses the letters.
        Column(
            Modifier.align(Alignment.Center).padding(bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "WELCOME TO",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                "EV",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 132.sp,
                    fontWeight = FontWeight.Light,
                ),
            )
        }

        // Vertical epigraph: one character per row, wide tracking, like a seal beside a signature.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, bottom = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EPIGRAPH.forEach { ch ->
                BasicText(
                    ch.toString(),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 15.sp,
                    ),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        BasicText(
            "$modules MODULES",
            modifier = Modifier.align(Alignment.BottomEnd),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.25f),
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/* ── line beats ────────────────────────────────────────────────────────────── */

/** Beat one: a single hairline growing out of the centre. */
private fun DrawScope.drawLineBeat(t: Float) {
    val p = t.coerceIn(0f, 1f)
    val half = size.width * 0.5f * p
    drawLine(
        color = Color.White.copy(alpha = 0.20f + 0.35f * p),
        start = Offset(center.x - half, center.y),
        end = Offset(center.x + half, center.y),
        strokeWidth = 1f,
    )
    // A brighter seed at the centre, where the line came from.
    drawCircle(Color.White.copy(alpha = 0.7f * (1f - p) + 0.2f), radius = 2.2f, center = center)
}

/** Beat two: the line multiplies and turns, still light. */
private fun DrawScope.drawMultiBeat(t: Float) {
    val p = t.coerceIn(0f, 1f)
    val count = 5
    val span = size.width * 0.5f * (0.5f + 0.5f * p)
    for (i in 0 until count) {
        val angle = (-0.24f + i * 0.12f) + p * 0.10f
        val dx = cos(angle) * span
        val dy = sin(angle) * span
        drawLine(
            color = Color.White.copy(alpha = 0.12f + 0.10f * p),
            start = Offset(center.x - dx, center.y - dy),
            end = Offset(center.x + dx, center.y + dy),
            strokeWidth = 1f,
        )
    }
    drawCircle(Color.White.copy(alpha = 0.5f), radius = 2f, center = center)
}

/**
 * Beat four: the rule of ticks. One tick per real module; a tick lights when the host reports that
 * module online, and the module's name appears above the rule. Progress is the line.
 */
private fun DrawScope.drawTicks(
    names: List<String>,
    done: Int,
    current: String?,
    local: Float,
) {
    val y = center.y
    val margin = size.width * 0.08f
    val span = size.width - margin * 2f
    val n = if (names.isEmpty()) 1 else names.size

    // Base rule: the same gesture as beat one, now carrying the whole system.
    drawLine(
        color = Color.White.copy(alpha = 0.16f),
        start = Offset(margin, y),
        end = Offset(margin + span, y),
        strokeWidth = 1f,
    )

    // A second, dimmer rule below reads as a reflection and gives the beat vertical body.
    drawLine(
        color = Color.White.copy(alpha = 0.05f),
        start = Offset(margin, y + 46f),
        end = Offset(margin + span, y + 46f),
        strokeWidth = 1f,
    )

    for (i in 0 until n) {
        val x = if (n == 1) center.x else margin + span * (i.toFloat() / (n - 1))
        val lit = i < done
        val isCurrent = names.getOrNull(i) != null && names.getOrNull(i) == current
        val h = when {
            lit -> 34f
            isCurrent -> 20f
            else -> 9f
        }
        // Bar above the rule, and its short mirror below.
        drawLine(
            color = if (lit) EvCyber.Cyan.copy(alpha = 0.9f)
            else if (isCurrent) Color.White.copy(alpha = 0.6f)
            else Color.White.copy(alpha = 0.14f),
            start = Offset(x, y),
            end = Offset(x, y - h),
            strokeWidth = if (lit) 1.8f else 1f,
        )
        drawLine(
            color = if (lit) EvCyber.Cyan.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
            start = Offset(x, y),
            end = Offset(x, y + h * 0.35f),
            strokeWidth = 1f,
        )
        if (lit) {
            // Head glow on the newest ticks only, so the lit end reads as recent activity.
            val fresh = (i - done + 4).coerceIn(0, 4) / 4f
            drawCircle(EvCyber.Cyan.copy(alpha = 0.25f * fresh), radius = 5f, center = Offset(x, y - h))
        }
    }

    // The module currently being written: a packet travelling along the rule.
    if (current != null && done < n) {
        val px = margin + span * (done.toFloat() / (n - 1).coerceAtLeast(1))
        drawCircle(Color.White.copy(alpha = 0.95f), radius = 2.6f, center = Offset(px, y))
        drawCircle(EvCyber.Cyan.copy(alpha = 0.22f), radius = 11f, center = Offset(px, y))
    }

    // A slow highlight sweeping the loaded part of the rule: activity, not fabricated data.
    if (done > 0) {
        val litEnd = margin + span * ((done - 1).toFloat() / (n - 1).coerceAtLeast(1))
        val sweep = if (local < 0f) 0f else ((local * 0.5f) % 1f)
        val sx = margin + (litEnd - margin) * sweep
        drawCircle(EvCyber.Cyan.copy(alpha = 0.16f), radius = 16f, center = Offset(sx, y))
    }
}

/**
 * The final anchor: every tick has closed into one rule. It sits below the wordmark, never through
 * it - an earlier version drew the rule at the vertical centre and it cut the EV in half.
 */
private fun DrawScope.drawAnchorRule() {
    val y = center.y + size.height * 0.20f
    val margin = size.width * 0.08f
    drawLine(
        color = Color.White.copy(alpha = 0.22f),
        start = Offset(margin, y),
        end = Offset(size.width - margin, y),
        strokeWidth = 1f,
    )
    // End caps: the rule reads as an instrument mark, not as a divider.
    drawLine(
        color = EvCyber.Cyan.copy(alpha = 0.55f),
        start = Offset(margin, y - 7f),
        end = Offset(margin, y + 7f),
        strokeWidth = 1.3f,
    )
    drawLine(
        color = EvCyber.Cyan.copy(alpha = 0.55f),
        start = Offset(size.width - margin, y - 7f),
        end = Offset(size.width - margin, y + 7f),
        strokeWidth = 1.3f,
    )
}

private fun Context.tick(ms: Long) {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(ms, 110))
    }
}
