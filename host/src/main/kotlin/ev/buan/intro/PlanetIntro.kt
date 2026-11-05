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
import java.io.File
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A planet drawn from lines, and the launch sequence built around it.
 *
 * The globe is a real sphere: orbits at many inclinations give it body, and the continents are
 * traced by marching squares over the same noise field the orbits sample. That last part matters -
 * an earlier version decided "land" per vertex along each orbit, so every continent came out
 * cross-hatched into a wire cage. Extracting the coastline first, then projecting it onto the
 * sphere, is what makes it read as a world with coastlines rather than a ball of string.
 *
 * The planet is the anchor: the sequence is one continuous camera move, and the transition into the
 * app is a dive through the surface.
 *
 *   IGNITE  orbits draw themselves into a sphere
 *   WORLD   continents are traced in
 *   ORBIT   modules come online, counted from the real load
 *   DIVE    the camera pushes through the surface
 *   ARRIVE  the wordmark
 */
private enum class Stage { Ignite, World, Orbit, Dive, Arrive }

private const val RINGS = 84
private const val STEPS = 72
private const val GOLDEN = 2.3999632f

/* ── noise ─────────────────────────────────────────────────────────────────── */

private fun h2(x: Int, y: Int, s: Int): Float {
    var h = x * 374761393 + y * 668265263 + s * 1274126177
    h = (h xor (h shr 13)) * 1274126177
    return ((h xor (h shr 16)) and 0x7fffffff) / 2147483647f
}

private fun sm(t: Float): Float = t * t * (3f - 2f * t)

private fun n2(x: Float, y: Float, s: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val u = sm(x - xi)
    val v = sm(y - yi)
    val a = h2(xi, yi, s)
    val b = h2(xi + 1, yi, s)
    val c = h2(xi, yi + 1, s)
    val d = h2(xi + 1, yi + 1, s)
    val top = a + (b - a) * u
    val bottom = c + (d - c) * u
    return top + (bottom - top) * v
}

/** Fractal noise on the sphere's own coordinates; continents therefore wrap with rotation. */
private fun terrain(x: Float, y: Float, z: Float): Float {
    val u = (atan2(z, x) / (2f * PI.toFloat())) + 0.5f
    val v = (asin(y.coerceIn(-1f, 1f)) / PI.toFloat()) + 0.5f
    var amp = 1f
    var f = 4.5f
    var sum = 0f
    var norm = 0f
    for (k in 0 until 5) {
        sum += n2(u * f, v * f, 17 + k * 31) * amp
        norm += amp
        amp *= 0.55f
        f *= 2f
    }
    return sum / norm
}

/* ── globe geometry ────────────────────────────────────────────────────────── */

internal class Planet {
    val vx = FloatArray(RINGS * STEPS)
    val vy = FloatArray(RINGS * STEPS)
    val vz = FloatArray(RINGS * STEPS)

    // Reused every frame: allocating Paths per frame cost tens of MB of GC churn.
    val seaBack = Path()
    val seaFront = Path()
    val grid = Path()
    val coast = Path()

    init {
        var i = 0
        for (r in 0 until RINGS) {
            // Evenly spaced inclinations with a golden-angle twist: no banding, no seam.
            val inc = kotlin.math.acos(1f - 2f * (r + 0.5f) / RINGS)
            val yaw = r * GOLDEN
            val ci = cos(inc)
            val si = sin(inc)
            val cy = cos(yaw)
            val sy = sin(yaw)
            for (s in 0 until STEPS) {
                val th = 2.0 * PI * s / STEPS
                val x0 = cos(th).toFloat()
                val y0 = sin(th).toFloat()
                val x1 = x0
                val y1 = y0 * ci
                val z1 = y0 * si
                vx[i] = x1 * cy - z1 * sy
                vy[i] = y1
                vz[i] = x1 * sy + z1 * cy
                i++
            }
        }
    }

    fun resetPaths() {
        seaBack.reset()
        seaFront.reset()
        grid.reset()
        coast.reset()
    }
}

/**
 * Coastlines, extracted once with marching squares over a lat/lon grid.
 *
 * Stored as flat pairs of (lon, lat) radians, two pairs per segment. The grid is coarse on purpose:
 * a coastline drawn at map resolution would shimmer as the sphere turns, and this is an emblem,
 * not an atlas.
 */
internal class Coastlines {
    private val cols = 144
    private val rows = 72
    private val threshold = 0.52f

    /** 4 floats per segment: lon1, lat1, lon2, lat2. */
    val seg = FloatArray(cols * rows * 4)
    var count = 0
        private set

    private fun lon(x: Int) = (x.toFloat() / cols) * 2f * PI.toFloat() - PI.toFloat()
    private fun lat(y: Int) = (y.toFloat() / rows) * PI.toFloat() - PI.toFloat() / 2f

    private fun value(x: Int, y: Int): Float {
        val lo = lon(x)
        val la = lat(y)
        val cx = cos(la) * cos(lo)
        val cy = sin(la)
        val cz = cos(la) * sin(lo)
        return terrain(cx, cy, cz)
    }

    init {
        val grid = FloatArray((cols + 1) * (rows + 1))
        for (y in 0..rows) for (x in 0..cols) grid[y * (cols + 1) + x] = value(x, y)

        fun at(x: Int, y: Int) = grid[y * (cols + 1) + x]

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                // Cells touching the poles span every longitude at once, so their interpolated
                // crossings jump across the whole map and paint a spike through the sphere.
                if (y < 3 || y > rows - 4) continue
                val v00 = at(x, y)
                val v10 = at(x + 1, y)
                val v11 = at(x + 1, y + 1)
                val v01 = at(x, y + 1)
                val inside = (if (v00 > threshold) 1 else 0) +
                    (if (v10 > threshold) 2 else 0) +
                    (if (v11 > threshold) 4 else 0) +
                    (if (v01 > threshold) 8 else 0)
                if (inside == 0 || inside == 15) continue

                // Crossing points on the four cell edges, interpolated to the threshold.
                val pTop = Pair(
                    lon(x) + (lon(x + 1) - lon(x)) * frac(v00, v10),
                    lat(y),
                )
                val pRight = Pair(
                    lon(x + 1),
                    lat(y) + (lat(y + 1) - lat(y)) * frac(v10, v11),
                )
                val pBottom = Pair(
                    lon(x) + (lon(x + 1) - lon(x)) * frac(v01, v11),
                    lat(y + 1),
                )
                val pLeft = Pair(
                    lon(x),
                    lat(y) + (lat(y + 1) - lat(y)) * frac(v00, v01),
                )

                val edges = when (inside) {
                    1, 14 -> listOf(pLeft, pTop)
                    2, 13 -> listOf(pTop, pRight)
                    3, 12 -> listOf(pLeft, pRight)
                    4, 11 -> listOf(pRight, pBottom)
                    6, 9 -> listOf(pTop, pBottom)
                    7, 8 -> listOf(pLeft, pBottom)
                    5 -> listOf(pLeft, pTop, pRight, pBottom)
                    else -> listOf(pLeft, pBottom, pTop, pRight)
                }
                var k = 0
                while (k + 1 < edges.size && count * 4 + 3 < seg.size) {
                    val a = edges[k]
                    val b = edges[k + 1]
                    seg[count * 4] = a.first
                    seg[count * 4 + 1] = a.second
                    seg[count * 4 + 2] = b.first
                    seg[count * 4 + 3] = b.second
                    count++
                    k += 2
                }
            }
        }
    }

    private fun frac(a: Float, b: Float): Float {
        val d = b - a
        return if (kotlin.math.abs(d) < 1e-6f) 0.5f else ((threshold - a) / d).coerceIn(0f, 1f)
    }
}

/* ── drawing ───────────────────────────────────────────────────────────────── */

internal fun DrawScope.drawPlanet(
    planet: Planet,
    coast: Coastlines,
    center: Offset,
    radius: Float,
    spin: Float,
    zoom: Float,
    coastAlpha: Float,
    ringReveal: Float,
    fade: Float,
) {
    val tilt = 0.36f
    val cs = cos(spin)
    val sn = sin(spin)
    val ct = cos(tilt)
    val st = sin(tilt)
    val camZ = 8.5f
    val r = radius * zoom

    planet.resetPaths()

    if (fade > 0f) {
        // Atmosphere and a faint body fill: the sphere is a solid object, not a cloud of lines.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    EvCyber.Cyan.copy(alpha = 0.05f * fade),
                    Color.Transparent,
                ),
                center = center,
                radius = r * 1.25f,
            ),
            radius = r * 1.25f,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF04181C).copy(alpha = 0.85f * fade),
                    Color(0xFF01070A).copy(alpha = 0.9f * fade),
                ),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }

    // Latitude skeleton: what makes the sphere legible as a globe.
    for (latIndex in 0 until 13) {
        val latRad = (-75f + latIndex * 12.5f) * (PI.toFloat() / 180f)
        val cy0 = sin(latRad)
        val cr = cos(latRad)
        var started = false
        for (s in 0 until STEPS) {
            val th = 2.0 * PI * s / STEPS
            val x = (cr * cos(th)).toFloat()
            val y = cy0
            val z = (cr * sin(th)).toFloat()
            val rx = x * cs - z * sn
            val rz = x * sn + z * cs
            val ry = y * ct - rz * st
            val rz2 = y * st + rz * ct
            if (rz2 < 0f) {
                val sx = center.x + rx * r
                val sy = center.y + ry * r
                if (!started) {
                    planet.grid.moveTo(sx, sy)
                    started = true
                } else {
                    planet.grid.lineTo(sx, sy)
                }
            }
        }
    }

    // Orbits: the body of the planet.
    val ringsToDraw = (RINGS * ringReveal).toInt().coerceIn(0, RINGS)
    for (ring in 0 until ringsToDraw) {
        var startedFront = false
        var startedBack = false
        for (s in 0 until STEPS) {
            val idx = ring * STEPS + s
            val x = planet.vx[idx]
            val y = planet.vy[idx]
            val z = planet.vz[idx]
            val rx = x * cs - z * sn
            val rz = x * sn + z * cs
            val ry = y * ct - rz * st
            val rz2 = y * st + rz * ct
            val persp = camZ / (camZ + rz2)
            val sx = center.x + rx * persp * r
            val sy = center.y + ry * persp * r
            if (rz2 < 0f) {
                if (!startedFront) {
                    planet.seaFront.moveTo(sx, sy)
                    startedFront = true
                } else {
                    planet.seaFront.lineTo(sx, sy)
                }
            } else {
                if (!startedBack) {
                    planet.seaBack.moveTo(sx, sy)
                    startedBack = true
                } else {
                    planet.seaBack.lineTo(sx, sy)
                }
            }
        }
    }

    // Coastlines: each segment is projected independently, so a coastline that crosses the limb
    // simply disappears over the edge instead of dragging a line across the disc. Projected inline
    // (no per-segment object) because this runs for a few thousand segments every frame.
    for (i in 0 until coast.count) {
        val lon1 = coast.seg[i * 4]
        val lat1 = coast.seg[i * 4 + 1]
        val lon2 = coast.seg[i * 4 + 2]
        val lat2 = coast.seg[i * 4 + 3]

        val cl1 = cos(lat1)
        val x1 = cl1 * cos(lon1)
        val y1 = sin(lat1)
        val z1 = cl1 * sin(lon1)
        val rx1 = x1 * cs - z1 * sn
        val rz1 = x1 * sn + z1 * cs
        val ry1 = y1 * ct - rz1 * st
        val rz1b = y1 * st + rz1 * ct
        if (rz1b >= 0f) continue

        val cl2 = cos(lat2)
        val x2 = cl2 * cos(lon2)
        val y2 = sin(lat2)
        val z2 = cl2 * sin(lon2)
        val rx2 = x2 * cs - z2 * sn
        val rz2 = x2 * sn + z2 * cs
        val ry2 = y2 * ct - rz2 * st
        val rz2b = y2 * st + rz2 * ct
        if (rz2b >= 0f) continue

        val p1 = camZ / (camZ + rz1b)
        val p2 = camZ / (camZ + rz2b)
        if (kotlin.math.abs(rx1 - rx2) + kotlin.math.abs(ry1 - ry2) > 0.09f) continue
        planet.coast.moveTo(center.x + rx1 * p1 * r, center.y + ry1 * p1 * r)
        planet.coast.lineTo(center.x + rx2 * p2 * r, center.y + ry2 * p2 * r)
    }

    // Paint order: far side, skeleton, near side, then coastlines on top.
    drawPath(planet.seaBack, EvCyber.Cyan.copy(alpha = 0.075f * fade), style = Stroke(0.7f))
    drawPath(planet.grid, EvCyber.Cyan.copy(alpha = 0.11f * fade), style = Stroke(0.8f))
    drawPath(planet.seaFront, EvCyber.Cyan.copy(alpha = 0.20f * fade), style = Stroke(0.85f))
    if (coastAlpha > 0f) {
        drawPath(planet.coast, EvCyber.Cyan.copy(alpha = 0.30f * coastAlpha * fade), style = Stroke(3.6f))
        drawPath(planet.coast, Color(0xFFE6FFFF).copy(alpha = coastAlpha * fade), style = Stroke(1.25f))
    }

    // The limb: a hard edge that states "solid body".
    drawCircle(
        color = EvCyber.Cyan.copy(alpha = 0.45f * fade),
        radius = r,
        center = center,
        style = Stroke(1.1f),
    )
    // Terminator: a soft shadow on one side, which is what a lit planet actually looks like.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f * fade)),
            center = Offset(center.x - r * 0.35f, center.y - r * 0.25f),
            radius = r * 1.25f,
        ),
        radius = r,
        center = center,
    )
}

/* ── sequence ──────────────────────────────────────────────────────────────── */

@Composable
fun PlanetIntro(runtime: HostRuntime, onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val planet = remember { Planet() }
    val coast = remember { Coastlines() }
    val progress by runtime.loadProgress.collectAsState()

    var stage by remember { mutableStateOf(Stage.Ignite) }
    var tick by remember { mutableIntStateOf(0) }
    var modsOnDisk by remember { mutableIntStateOf(-1) }

    var ringReveal by remember { mutableFloatStateOf(0f) }
    var coastAlpha by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var fade by remember { mutableFloatStateOf(1f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var wordmark by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        modsOnDisk = with(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                File("/sdcard/EV/mods").listFiles()
                    ?.count { it.isFile && it.name.endsWith(".jar", true) } ?: 0
            }.getOrDefault(0)
        }
    }

    LaunchedEffect(Unit) {
        if (reduced) onDone() else runCatching { runtime.start() }
    }

    // One clock. The camera move stays continuous because nothing here is a page transition.
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0166f else ((now - last) / 1_000_000_000.0).toFloat()
                last = now
                val d = dt.coerceIn(0f, 0.05f)
                spin += d * (0.55f - 0.36f * (zoom - 1f).coerceIn(0f, 1f))
                when (stage) {
                    Stage.Ignite -> ringReveal = (ringReveal + d * 0.62f).coerceAtMost(1f)
                    Stage.World -> coastAlpha = (coastAlpha + d * 0.8f).coerceAtMost(1f)
                    Stage.Orbit -> Unit
                    Stage.Dive -> {
                        zoom *= (1f + d * 2.9f)
                        fade = (1f - (zoom - 1f) / 6.5f).coerceIn(0f, 1f)
                    }
                    Stage.Arrive -> Unit
                }
                tick++
            }
        }
    }

    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(200)
        while (stage == Stage.Ignite && ringReveal < 1f) delay(40)
        stage = Stage.World
        context.pulse()
        delay(1500)
        stage = Stage.Orbit
        context.pulse()

        // Orbit lasts exactly as long as the plugins take.
        combine(runtime.loadProgress, runtime.stateFlow()) { p, host -> p.finished && host != null }
            .first { it }
        delay(700)
        stage = Stage.Dive
        context.pulse()
        delay(1250)
        fade = 0f
        stage = Stage.Arrive
        context.pulse()

        val start = System.nanoTime()
        while (wordmark < 1f) {
            wordmark = (((System.nanoTime() - start) / 1_000_000_000.0).toFloat() / 0.7f).coerceIn(0f, 1f)
            delay(16)
        }
        delay(800)
        onDone()
    }

    val done = progress.done
    val total = progress.names.size

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick >= 0) {
                drawPlanet(
                    planet = planet,
                    coast = coast,
                    center = center,
                    radius = minOf(size.width, size.height) * 0.33f,
                    spin = spin,
                    zoom = zoom,
                    coastAlpha = coastAlpha,
                    ringReveal = if (stage == Stage.Ignite) ringReveal else 1f,
                    fade = fade,
                )
            }
        }

        Column(
            Modifier.fillMaxSize().evSystemBarsPadding().padding(horizontal = 28.dp, vertical = 30.dp),
        ) {
            BasicText(
                "EV",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.5f * fade),
                    fontSize = 11.sp,
                    letterSpacing = 8.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.weight(1f))

            if (stage == Stage.World && fade > 0f) {
                BasicText(
                    "由 %d 个模块构成的世界".format(if (modsOnDisk >= 0) modsOnDisk else 0),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.8f * coastAlpha * fade),
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            if (stage == Stage.Orbit && fade > 0f) {
                BasicText(
                    // Before the host publishes its module list the honest reading is "scanning".
                    if (total == 0) "SCANNING MODULES" else "%02d / %02d  ONLINE".format(done, total),
                    style = TextStyle(
                        color = EvCyber.Cyan.copy(alpha = 0.85f * fade),
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            BasicText(
                stageLabel(stage),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.28f * fade),
                    fontSize = 9.sp,
                    letterSpacing = 4.sp,
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
                        color = Color.White.copy(alpha = 0.5f * wordmark),
                        fontSize = 11.sp,
                        letterSpacing = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                BasicText(
                    "EV",
                    style = TextStyle(
                        color = Color.White.copy(alpha = wordmark),
                        fontSize = 132.sp,
                        fontWeight = FontWeight.Light,
                    ),
                )
            }
        }
    }
}

private fun stageLabel(stage: Stage): String = when (stage) {
    Stage.Ignite -> "IGNITE"
    Stage.World -> "WORLD"
    Stage.Orbit -> "ORBIT"
    Stage.Dive -> "DIVE"
    Stage.Arrive -> "ARRIVE"
}

private fun Context.pulse() {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
