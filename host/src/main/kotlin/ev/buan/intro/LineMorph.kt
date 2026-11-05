package ev.buan.intro

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.HostRuntime
import ev.buan.ui.evSystemBarsPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One line. It never breaks, never leaves, and never cuts to another screen.
 *
 * The whole sequence is a single closed polyline whose vertices are interpolated between shapes:
 * circle, triangle, hexagon, five-point star, then a comb of teeth. Because every shape is the same
 * set of vertices at different positions, the line travels and re-forms itself. That is the
 * continuity an earlier version lacked, where each "beat" was a separate drawing swapped out.
 *
 *   SHAPES  circle -> triangle -> hexagon -> star, morphing continuously
 *   COMB    it unfolds into one tooth per real module; teeth light as the host reports them online
 *   RULE    the teeth close into one anchor rule; the wordmark arrives
 *   EXIT    the rule collapses into a point and hands over to the app
 *
 * Text appears once, at the end. Nothing flashes.
 */
private const val N = 480

private typealias Pt = Pair<Float, Float>

/* ── shape builders: every shape resamples to N points in roughly [-1, 1] ──── */

private fun circlePts(): List<Pt> = (0 until 72).map {
    val a = 2 * PI * it / 72
    Pt(cos(a).toFloat(), sin(a).toFloat())
}

private fun polygonPts(sides: Int, startDeg: Float): List<Pt> = (0 until sides).map {
    val a = (startDeg + 360.0 * it / sides) * PI / 180.0
    Pt(cos(a).toFloat(), sin(a).toFloat())
}

private fun starPts(points: Int): List<Pt> = (0 until points * 2).map {
    val a = (-90.0 + 180.0 * it / points) * PI / 180.0
    val r = if (it % 2 == 0) 1.0 else 0.46
    Pt((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
}

/** The comb: a baseline with one tooth per module, traced out and back so the loop stays closed. */
private fun combPts(teeth: Int): List<Pt> {
    val pts = ArrayList<Pt>()
    val toothH = 0.55f
    pts.add(Pt(-1f, 0f))
    for (t in 0 until teeth) {
        val x = if (teeth == 1) 0f else -1f + 2f * t / (teeth - 1)
        pts.add(Pt(x, 0f))
        pts.add(Pt(x, -toothH))
        pts.add(Pt(x, 0f))
    }
    pts.add(Pt(1f, 0f))
    return pts
}

private fun rulePts(): List<Pt> = listOf(Pt(-1f, 0f), Pt(1f, 0f))

/**
 * Resample a polyline (closed by joining the last point to the first) into exactly [n] points
 * spaced by arc length. Equal spacing is what lets two different shapes interpolate point by point
 * without the line bunching up in the corners.
 */
private fun resample(pts: List<Pt>, n: Int): FloatArray {
    val m = pts.size
    if (m == 0) return FloatArray(n * 2)
    val lens = FloatArray(m)
    var total = 0f
    for (i in 0 until m) {
        val a = pts[i]
        val b = pts[(i + 1) % m]
        val d = hypot(b.first - a.first, b.second - a.second)
        lens[i] = d
        total += d
    }
    val out = FloatArray(n * 2)
    if (total <= 0f) return out
    var seg = 0
    var segStart = 0f
    for (k in 0 until n) {
        val target = total * k / n
        while (seg < m - 1 && segStart + lens[seg] < target) {
            segStart += lens[seg]
            seg++
        }
        val a = pts[seg]
        val b = pts[(seg + 1) % m]
        val f = if (lens[seg] <= 0f) 0f else ((target - segStart) / lens[seg]).coerceIn(0f, 1f)
        out[k * 2] = a.first + (b.first - a.first) * f
        out[k * 2 + 1] = a.second + (b.second - a.second) * f
    }
    return out
}

/** Zero velocity at both ends, so a shape settles into place instead of snapping. */
private fun ease(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
    val k = t.coerceIn(0f, 1f)
    val out = FloatArray(a.size)
    for (i in a.indices) out[i] = a[i] + (b[i] - a[i]) * k
    return out
}

/* ── timeline ──────────────────────────────────────────────────────────────── */

private const val SHAPE_COUNT = 4
private const val HOLD = 0.85f
private const val MORPH = 0.95f
private const val SLOT = HOLD + MORPH
private const val SHAPES_END = SHAPE_COUNT * SLOT
private const val RULE_MORPH = 0.8f

@Composable
fun LineMorph(runtime: HostRuntime, onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val progress by runtime.loadProgress.collectAsState()

    val circle = remember { resample(circlePts(), N) }
    val triangle = remember { resample(polygonPts(3, -90f), N) }
    val hexagon = remember { resample(polygonPts(6, -60f), N) }
    val star = remember { resample(starPts(5), N) }
    val rule = remember { resample(rulePts(), N) }

    var elapsed by remember { mutableFloatStateOf(0f) }
    var loadedAt by remember { mutableFloatStateOf(-1f) }
    var tick by remember { mutableIntStateOf(0) }
    var lastSlot by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        if (reduced) onDone() else runCatching { runtime.start() }
    }

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

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        combine(runtime.loadProgress, runtime.stateFlow()) { p, host -> p.finished && host != null }
            .first { it }
        loadedAt = elapsed
    }

    val total = progress.names.size.coerceAtLeast(1)
    val done = progress.done
    // One tooth per real module. Rebuilt only when the module count changes.
    val comb = remember(total) { resample(combPts(total.coerceIn(1, 40)), N) }

    val e = elapsed
    val lockAt = if (loadedAt < 0f) Float.MAX_VALUE else loadedAt + 1.5f
    val ruleT = if (lockAt == Float.MAX_VALUE) 0f else ((e - lockAt) / RULE_MORPH).coerceIn(0f, 1f)
    val exitAt = lockAt + RULE_MORPH + 2.8f
    val collapse = if (exitAt == Float.MAX_VALUE) 0f else ((e - exitAt) / 0.7f).coerceIn(0f, 1f)
    val textAlpha = (if (ruleT <= 0f) 0f else ((e - lockAt) / 0.8f).coerceIn(0f, 1f)) * (1f - collapse)

    val slot = (e / SLOT).toInt().coerceAtMost(SHAPE_COUNT)
    LaunchedEffect(slot) {
        if (reduced) return@LaunchedEffect
        if (slot != lastSlot) {
            context.tick()
            lastSlot = slot
        }
    }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        while (elapsed < exitAt + 0.8f) delay(16)
        onDone()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick >= 0) {
                val shapes = listOf(circle, triangle, hexagon, star)
                val pts: FloatArray
                val inComb = e >= SHAPES_END

                if (!inComb) {
                    val k = (e / SLOT).toInt().coerceIn(0, SHAPE_COUNT - 1)
                    val local = (e - k * SLOT) / SLOT
                    pts = if (k == 0) {
                        // The first shape draws itself in from a single point.
                        lerp(rule, shapes[0], ease(local / 0.45f))
                    } else {
                        val from = shapes[k - 1]
                        val to = shapes[k]
                        val m = ease(((local - HOLD / SLOT) / (MORPH / SLOT)).coerceIn(0f, 1f))
                        lerp(from, to, m)
                    }
                } else {
                    pts = if (ruleT <= 0f) comb else lerp(comb, rule, ease(ruleT))
                }

                val r = minOf(size.width, size.height) * 0.34f * (1f - collapse * 0.86f)
                val alpha = (1f - collapse).coerceIn(0f, 1f)
                val litTeeth = inComb && ruleT <= 0f

                if (litTeeth) {
                    // Comb: teeth light as the host reports modules. Two paths, two draw calls.
                    val dim = Path()
                    val lit = Path()
                    for (i in 0 until N) {
                        val j = (i + 1) % N
                        val litSeg = (i.toFloat() / N * total) < done
                        val path = if (litSeg) lit else dim
                        path.moveTo(center.x + pts[i * 2] * r, center.y + pts[i * 2 + 1] * r)
                        path.lineTo(center.x + pts[j * 2] * r, center.y + pts[j * 2 + 1] * r)
                    }
                    drawPath(dim, Color.White.copy(alpha = 0.14f * alpha), style = Stroke(1f))
                    drawPath(lit, EvCyber.Cyan.copy(alpha = 0.85f * alpha), style = Stroke(1.5f))
                } else {
                    val path = Path()
                    path.moveTo(center.x + pts[0] * r, center.y + pts[1] * r)
                    for (i in 1 until N) path.lineTo(center.x + pts[i * 2] * r, center.y + pts[i * 2 + 1] * r)
                    path.close()
                    drawPath(path, Color.White.copy(alpha = 0.30f * alpha), style = Stroke(2.0f))
                    drawPath(path, EvCyber.Cyan.copy(alpha = 0.85f * alpha), style = Stroke(3.2f))
                }

                // The head: a bright segment running along the line forever, so it always looks like
                // a drawing still in progress rather than a finished diagram.
                val head = (((e * 0.36f) % 1f) * N).toInt().coerceIn(0, N - 1)
                val glow = Path()
                for (k in 0 until 24) {
                    val i = ((head - k) % N + N) % N
                    val j = (i + 1) % N
                    glow.moveTo(center.x + pts[i * 2] * r, center.y + pts[i * 2 + 1] * r)
                    glow.lineTo(center.x + pts[j * 2] * r, center.y + pts[j * 2 + 1] * r)
                }
                drawPath(glow, EvCyber.Cyan.copy(alpha = 0.40f * alpha), style = Stroke(5.5f))
                drawPath(glow, Color.White.copy(alpha = 0.9f * alpha), style = Stroke(2.1f))
                drawCircle(
                    Color.White.copy(alpha = alpha),
                    radius = 2.6f,
                    center = Offset(center.x + pts[head * 2] * r, center.y + pts[head * 2 + 1] * r),
                )
            }
        }

        if (textAlpha > 0f) FinalText(done, total, textAlpha)
    }
}

@Composable
private fun FinalText(done: Int, total: Int, alpha: Float) {
    Box(Modifier.fillMaxSize().evSystemBarsPadding().padding(horizontal = 26.dp, vertical = 28.dp)) {
        Column(
            Modifier.align(Alignment.Center).padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "WELCOME TO",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.55f * alpha),
                    fontSize = 11.sp,
                    letterSpacing = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            BasicText(
                "EV",
                style = TextStyle(
                    color = Color.White.copy(alpha = alpha),
                    fontSize = 132.sp,
                    fontWeight = FontWeight.Light,
                ),
            )
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, bottom = 170.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            "万物皆插件".forEach { ch ->
                BasicText(
                    ch.toString(),
                    style = TextStyle(color = Color.White.copy(alpha = 0.62f * alpha), fontSize = 15.sp),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        BasicText(
            "${done.coerceAtMost(total)} MODULES",
            modifier = Modifier.align(Alignment.BottomEnd),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.25f * alpha),
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

private fun Context.tick() {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(10, 90))
    }
}
