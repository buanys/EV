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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * COSMOGRAPH - the launch animation as one continuous line simulation on pure black.
 *
 * Every strand obeys the same law: a divergence-free curl-noise field plus a gravity well at the
 * centre. That shared law is why the field gathers into filaments instead of scattering like dust,
 * and it is the whole point - the field reads as alive because everything follows one rule.
 *
 *   GENESIS  bodies ignite
 *   FILAMENT the field spins the strands into a web
 *   CAPTURE  the well opens; strands are absorbed as plugins really load
 *   COLLAPSE the remaining web falls into the core
 *   ONLINE   the wordmark condenses out of it
 *
 * One canvas, one clock, no page cuts.
 */
private enum class Phase { Genesis, Filament, Capture, Collapse, Online }

private const val BODIES = 320
private const val TRAIL = 56
private const val WEB_SEEDS = 18
private const val WEB_STEPS = 24

private fun hash2(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1274126177
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0x7fffffff) / 2147483647f
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun noise(x: Float, y: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val u = smooth(x - xi)
    val v = smooth(y - yi)
    val a = hash2(xi, yi, seed)
    val b = hash2(xi + 1, yi, seed)
    val c = hash2(xi, yi + 1, seed)
    val d = hash2(xi + 1, yi + 1, seed)
    val top = a + (b - a) * u
    val bottom = c + (d - c) * u
    return top + (bottom - top) * v
}

private class CosmoField {
    private val px = FloatArray(BODIES)
    private val py = FloatArray(BODIES)
    private val vx = FloatArray(BODIES)
    private val vy = FloatArray(BODIES)
    private val trail = Array(BODIES) { FloatArray(TRAIL * 2) }
    private val absorbed = BooleanArray(BODIES)
    private var head = 0
    private var time = 0f
    private var frames = 0
    private var fx = 0f
    private var fy = 0f
    private var web: Path? = null
    private var webAt = -9999

    var pull = 0f
        private set
    var absorbedCount = 0
        private set

    init {
        var seed = 918273
        fun rnd(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 8) and 0xFFFF) / 65535f
        }
        for (i in 0 until BODIES) {
            val a = rnd() * 6.28318f
            val r = 0.10f + sqrt(rnd()) * 0.38f
            px[i] = 0.5f + r * cos(a)
            py[i] = 0.5f + r * sin(a)
            for (k in 0 until TRAIL) {
                trail[i][k * 2] = px[i]
                trail[i][k * 2 + 1] = py[i]
            }
        }
    }

    /** Divergence-free direction at a point: the curl of a scalar potential. */
    private fun curl(x: Float, y: Float) {
        val scale = 3.4f
        val e = 0.006f
        val nx = x * scale
        val ny = y * scale
        val drift = (time * 0.35f).toInt() and 7
        val p1 = noise(nx, ny + e, 7 + drift) - noise(nx, ny - e, 7 + drift)
        val p2 = noise(nx + e, ny, 7 + drift) - noise(nx - e, ny, 7 + drift)
        val q1 = noise(nx, ny + e, 41 + drift) - noise(nx, ny - e, 41 + drift)
        val q2 = noise(nx + e, ny, 41 + drift) - noise(nx - e, ny, 41 + drift)
        var dx = (p1 + q1 * 0.45f) / (2f * e)
        var dy = (-p2 - q2 * 0.45f) / (2f * e)
        val m = hypot(dx, dy)
        if (m > 0.000001f) {
            dx /= m
            dy /= m
        }
        fx = dx
        fy = dy
    }

    fun step(dt: Float, targetPull: Float) {
        time += dt
        frames++
        pull += (targetPull - pull) * (dt * 2.0f).coerceAtMost(1f)
        val want = (BODIES * pull).toInt()
        head = (head + 1) % TRAIL

        for (i in 0 until BODIES) {
            if (absorbed[i]) continue
            if (i < want) {
                absorbed[i] = true
                absorbedCount++
                continue
            }

            curl(px[i], py[i])
            // Fast enough that a trail spans a real arc of the screen rather than a smudge.
            val speed = 0.17f
            var tx = fx * speed
            var ty = fy * speed

            val dx = 0.5f - px[i]
            val dy = 0.5f - py[i]
            val d = hypot(dx, dy) + 0.0001f
            val gravity = 0.42f * pull * pull
            tx += (dx / d) * gravity
            ty += (dy / d) * gravity
            val swirl = 0.55f * pull
            tx += (-dy / d) * swirl
            ty += (dx / d) * swirl

            val steer = (dt * 3.2f).coerceAtMost(1f)
            vx[i] += (tx - vx[i]) * steer
            vy[i] += (ty - vy[i]) * steer
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt

            if (px[i] < -0.02f || px[i] > 1.02f || py[i] < -0.02f || py[i] > 1.02f) {
                px[i] = px[i].coerceIn(0.01f, 0.99f)
                py[i] = py[i].coerceIn(0.01f, 0.99f)
                vx[i] = -vx[i] * 0.5f
                vy[i] = -vy[i] * 0.5f
            }

            trail[i][head * 2] = px[i]
            trail[i][head * 2 + 1] = py[i]
        }
    }

    /** Static streamline lattice: the permanent web the bodies run along. */
    private fun buildWeb(w: Float, h: Float): Path {
        val p = Path()
        for (sx in 0 until WEB_SEEDS) {
            for (sy in 0 until WEB_SEEDS) {
                var x = (sx + 0.5f) / WEB_SEEDS
                var y = (sy + 0.5f) / WEB_SEEDS
                p.moveTo(x * w, y * h)
                for (k in 0 until WEB_STEPS) {
                    curl(x, y)
                    x += fx * 0.012f
                    y += fy * 0.012f
                    if (x < 0f || x > 1f || y < 0f || y > 1f) break
                    p.lineTo(x * w, y * h)
                }
            }
        }
        return p
    }

    fun draw(scope: DrawScope, coreProgress: Float, fade: Float) {
        val w = scope.size.width
        val h = scope.size.height
        val scale = minOf(w, h)

        if (web == null || frames - webAt > 240) {
            web = buildWeb(w, h)
            webAt = frames
        }
        web?.let {
            scope.drawPath(it, EvCyber.Cyan.copy(alpha = 0.055f * fade), style = Stroke(1f))
        }

        val trails = Path()
        val heads = Path()
        for (i in 0 until BODIES) {
            if (absorbed[i]) continue
            val tr = trail[i]
            trails.moveTo(tr[head * 2] * w, tr[head * 2 + 1] * h)
            for (k in 1 until TRAIL) {
                val idx = ((head + k) % TRAIL) * 2
                trails.lineTo(tr[idx] * w, tr[idx + 1] * h)
            }
            heads.addOval(Rect(center = Offset(px[i] * w, py[i] * h), radius = scale * 0.0013f))
        }

        // A wide soft pass under a thin bright pass: on black that is what reads as light.
        scope.drawPath(trails, EvCyber.Cyan.copy(alpha = 0.05f * fade), style = Stroke(3.4f, cap = StrokeCap.Round))
        scope.drawPath(trails, EvCyber.Cyan.copy(alpha = 0.20f * fade), style = Stroke(1.15f))
        scope.drawPath(heads, Color.White.copy(alpha = 0.65f * fade))

        if (coreProgress > 0f) {
            val c = Offset(w / 2f, h / 2f)
            val r = scale * (0.02f + 0.20f * coreProgress)
            scope.drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        EvCyber.Cyan.copy(alpha = 0.5f * coreProgress * fade),
                        EvCyber.Cyan.copy(alpha = 0.14f * coreProgress * fade),
                        Color.Transparent,
                    ),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            scope.drawCircle(
                color = Color.White.copy(alpha = 0.35f * coreProgress * fade),
                radius = r * 0.10f,
                center = c,
            )
        }
    }
}

@Composable
fun CosmographIntro(runtime: HostRuntime, onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val field = remember { CosmoField() }
    var phase by remember { mutableStateOf(Phase.Genesis) }
    var tick by remember { mutableIntStateOf(0) }
    var wordmark by remember { mutableStateOf(0f) }

    val progress by runtime.loadProgress.collectAsState()

    LaunchedEffect(Unit) {
        if (reduced) {
            onDone()
        } else {
            runCatching { runtime.start() }
        }
    }

    // One clock for the whole sequence; dt is capped so a stalled frame cannot teleport the field.
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0166f else ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                val target = when (phase) {
                    Phase.Genesis, Phase.Filament -> 0f
                    Phase.Capture -> progress.done.toFloat() / progress.names.size.coerceAtLeast(1)
                    Phase.Collapse, Phase.Online -> 1f
                }
                field.step(dt.coerceIn(0f, 0.05f), target)
                tick++
            }
        }
    }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(1100)
        phase = Phase.Filament
        context.pulse()
        delay(1700)
        phase = Phase.Capture
        context.pulse()

        // Capture lasts exactly as long as the plugins take. No timeout on purpose.
        combine(runtime.loadProgress, runtime.stateFlow()) { p, host -> p.finished && host != null }
            .first { it }
        delay(400)
        phase = Phase.Collapse
        context.pulse()
        delay(1000)
        phase = Phase.Online
        context.pulse()

        val start = System.nanoTime()
        while (wordmark < 1f) {
            wordmark = (((System.nanoTime() - start) / 1_000_000_000.0).toFloat() / 0.8f).coerceIn(0f, 1f)
            delay(16)
        }
        delay(850)
        onDone()
    }

    val fade = 1f - wordmark

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick >= 0) {
                field.draw(this, progress.done.toFloat() / progress.names.size.coerceAtLeast(1), fade)
            }
        }

        Column(
            Modifier.fillMaxSize().evSystemBarsPadding().padding(horizontal = 26.dp, vertical = 26.dp),
        ) {
            BasicText(
                "EV",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.45f * fade),
                    fontSize = 11.sp,
                    letterSpacing = 8.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                phaseLabel(phase),
                style = TextStyle(
                    color = EvCyber.Cyan.copy(alpha = 0.6f * fade),
                    fontSize = 10.sp,
                    letterSpacing = 5.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                "${progress.done} / ${progress.names.size}",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.3f * fade),
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        if (wordmark > 0f) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    "WELCOME TO",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.55f * wordmark),
                        fontSize = 11.sp,
                        letterSpacing = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                BasicText(
                    "EV",
                    style = TextStyle(
                        color = Color.White.copy(alpha = wordmark),
                        fontSize = 140.sp,
                        fontWeight = FontWeight.Light,
                    ),
                )
            }
        }
    }
}

private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.Genesis -> "GENESIS"
    Phase.Filament -> "FILAMENT"
    Phase.Capture -> "CAPTURE"
    Phase.Collapse -> "COLLAPSE"
    Phase.Online -> "ONLINE"
}

private fun Context.pulse() {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
