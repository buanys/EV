package ev.buan.intro

import ev.buan.LaunchScope
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.HostRuntime
import ev.buan.hasAllFilesAccess
import ev.buan.ui.evSystemBarsPadding
import kotlinx.coroutines.delay

/**
 * EV's startup screen, ported from dsh-startup-screen (MIT) to Compose.
 *
 * Four beats, each with one job:
 *
 *   PERMISSION  the gate; leaves as soon as file access is really granted
 *   READING     the plugin load happens here and only here; the meter follows the real modules
 *   VERIFIED    entered only after the load returned - nothing is ever loaded twice
 *   WELCOME     hold, then hand over to the app
 *
 * There is deliberately no separate "curtain" beat. An earlier version played a 1.5s empty stage
 * - reticle turning, no text - before the first line appeared, which read as the app flashing a
 * blank screen at launch. The overexposed wash is now the only thing before the first beat, and
 * the first line starts typing as the paper settles.
 *
 * Transitions are event driven. A stage advances when the previous one finishes its own work,
 * never from a formula over elapsed time: mixing a clock with live load progress is what produced
 * the race that used to replay the last two beats.
 */
private enum class Act { Permission, Reading, Verified, Welcome }

private object Paper {
    val bg = Color(0xFFE5E3DF)
    val bg2 = Color(0xFFECEAE6)
    val ink = Color(0xFF0A0A0A)
    val inkSoft = Color(0xFF3A3936)
    val muted = Color(0xFF7A7873)
    val hair = Color(0x290A0A0A)
    val hairSoft = Color(0x120A0A0A)
    val accent = Color(0xFFFF7500)
}

private fun Act.zh(): String = when (this) {
    Act.Permission -> "需要访问许可"
    Act.Reading -> "开始读取权限"
    Act.Verified -> "读取完毕 · 核验通过"
    Act.Welcome -> "欢迎访问"
}

private fun Act.en(): String = when (this) {
    Act.Permission -> "ACCESS PERMISSION REQUIRED"
    Act.Reading -> "PERMISSION READ STARTED"
    Act.Verified -> "READ COMPLETE · VERIFIED"
    Act.Welcome -> "WELCOME TO EV"
}

@Composable
fun PaperStartup(
    runtime: HostRuntime,
    /**
     * 这次启动要加载多少插件。
     *
     * 主界面用 [LaunchScope.Full]；从桌面快捷方式进来时只加载那个插件的闭包——
     * 用户点的是"浏览器"图标，他没有要聊天、模型和记忆。
     */
    scope: LaunchScope = LaunchScope.Full,
    onGrant: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val progress by runtime.loadProgress.collectAsState()

    var elapsed by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(Act.Permission) }
    var stageAt by remember { mutableFloatStateOf(0f) }
    var loadStarted by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAllFilesAccess() }

    // One clock, used for the typewriter, the caret and the wash. It never decides the stage.
    LaunchedEffect(Unit) {
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

    LaunchedEffect(stage) {
        stageAt = elapsed
        when (stage) {
            Act.Permission -> {
                // Wait for the real permission rather than pretending to check on a timer.
                var waited = 0f
                while (!hasAllFilesAccess() && waited < 60f) {
                    delay(120)
                    waited += 0.12f
                }
                granted = hasAllFilesAccess()
                if (granted) {
                    // Long enough for the first line to finish typing.
                    delay(1900)
                    stage = Act.Reading
                }
            }
            Act.Reading -> {
                // The only place the host is started. Nothing else in this screen calls start().
                if (!loadStarted) {
                    loadStarted = true
                    runCatching { runtime.start(scope) }.onFailure { loadFailed = true }
                }
                // Reached only once start() has actually returned.
                delay(250)
                stage = Act.Verified
                context.thump(18)
            }
            Act.Verified -> {
                delay(1400)
                stage = Act.Welcome
                context.thump(26)
            }
            Act.Welcome -> {
                delay(2300)
                onDone()
            }
        }
    }

    val sinceStage = (elapsed - stageAt).coerceAtLeast(0f)
    // The first line waits for the white wash to clear, so nothing types behind a blank flash.
    val textDelay = if (stage == Act.Permission) 0.55f else 0f
    val textLocal = sinceStage - textDelay

    val total = progress.names.size
    val done = progress.done

    // Overexposed wash: black -> white -> paper, over the first 0.75s only.
    val washAlpha = when {
        elapsed < 0.10f -> 0f
        elapsed < 0.42f -> ((elapsed - 0.10f) / 0.32f).coerceIn(0f, 1f)
        elapsed < 0.75f -> 1f - ((elapsed - 0.42f) / 0.33f).coerceIn(0f, 1f)
        else -> 0f
    }
    val paperIn = (elapsed / 0.75f).coerceIn(0f, 1f)
    val exitBlack = if (stage == Act.Welcome) ((sinceStage - 1.9f) / 0.4f).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(Modifier.fillMaxSize().background(lerpBlackToPaper(paperIn))) {
        val ringD = minOf(maxWidth * 0.52f, maxHeight * 0.34f)
        val titleSize = (ringD.value * 0.60f / stage.zh().length.coerceAtLeast(2)).coerceIn(13f, 30f)

        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Paper.bg2.copy(alpha = 0.55f * paperIn), Color.Transparent),
                ),
            )
        }

        Reticle(elapsed = elapsed, alpha = paperIn)

        Column(
            Modifier.fillMaxSize().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TypedLine(
                text = stage.zh(),
                local = textLocal,
                style = TextStyle(
                    color = Paper.ink,
                    fontSize = titleSize.sp,
                    letterSpacing = (titleSize * 0.2f).sp,
                ),
            )
            Spacer(Modifier.height(12.dp))
            // The caret blinks; it is the only saturated colour on the page.
            val caretOn = ((elapsed * 2f).toInt() % 2) == 0
            Box(
                Modifier
                    .width(13.dp)
                    .height(3.dp)
                    .background(if (caretOn && textLocal > 0f) Paper.accent else Color.Transparent),
            )

            // The gate is a real control, not a decorative line of text.
            if (stage == Act.Permission && !granted && textLocal > 0.4f) {
                Spacer(Modifier.height(26.dp))
                Box(
                    Modifier
                        .width(180.dp)
                        .height(44.dp)
                        .background(Paper.ink)
                        .clickable(onClick = onGrant),
                ) {
                    BasicText(
                        "前往授权",
                        modifier = Modifier.align(Alignment.Center),
                        style = TextStyle(color = Paper.bg, fontSize = 13.sp, letterSpacing = 3.sp),
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopStart).evSystemBarsPadding().padding(start = 24.dp, top = 12.dp),
        ) {
            BasicText(
                "EV",
                style = TextStyle(color = Paper.ink.copy(alpha = paperIn), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            )
            BasicText(
                "STARTUP SEQUENCE",
                style = TextStyle(color = Paper.muted.copy(alpha = paperIn), fontSize = 8.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .evSystemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TypedLine(
                text = stage.en(),
                local = textLocal - 0.16f,
                style = TextStyle(
                    color = Paper.muted.copy(alpha = paperIn),
                    fontSize = 9.5.sp,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(14.dp))
            BasicText(
                when {
                    stage == Act.Reading && total == 0 -> "READING …"
                    total == 0 -> "0 MODULES"
                    else -> "$done / $total MODULES"
                },
                style = TextStyle(color = Paper.muted.copy(alpha = paperIn), fontSize = 8.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.width(220.dp).height(10.dp)) {
                // Tick ruler, as in the reference.
                var x = 0f
                while (x < size.width) {
                    drawLine(Paper.hair, Offset(x, 0f), Offset(x, 4f), 1f)
                    x += 30f
                }
                drawRect(Paper.hairSoft, Offset(0f, 6f), Size(size.width, 3f))
                val value = when (stage) {
                    Act.Permission -> 0f
                    // Reading: real modules when known, a sweep while the count is still unknown,
                    // so the meter visibly moves the moment reading starts.
                    Act.Reading -> if (total > 0) done.toFloat() / total else (sinceStage * 0.35f) % 1f
                    Act.Verified, Act.Welcome -> 1f
                }
                drawRect(Paper.ink, Offset(0f, 6f), Size(size.width * value.coerceIn(0f, 1f), 3f))
            }
            if (loadFailed) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "加载出错，进入后可查看详情",
                    style = TextStyle(color = Paper.muted.copy(alpha = paperIn), fontSize = 9.sp),
                )
            }
        }

        if (washAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = washAlpha)))
        if (exitBlack > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = exitBlack)))
    }
}

/* ── reticle ───────────────────────────────────────────────────────────────── */

@Composable
private fun Reticle(elapsed: Float, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (alpha <= 0f) return@Canvas
        val d = minOf(size.width * 0.52f, size.height * 0.34f)
        val c = Offset(size.width / 2f, size.height * 0.47f)
        val sw = d * 0.010f
        drawArc(
            color = Paper.ink.copy(alpha = 0.85f * alpha),
            startAngle = elapsed * 40f + 120f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(c.x - d / 2f, c.y - d / 2f),
            size = Size(d, d),
            style = Stroke(width = sw, cap = StrokeCap.Butt),
        )
        val d2 = d * 0.92f
        drawArc(
            color = Paper.inkSoft.copy(alpha = 0.75f * alpha),
            startAngle = -elapsed * 66f + 130f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(c.x - d2 / 2f, c.y - d2 / 2f),
            size = Size(d2, d2),
            style = Stroke(width = sw * 0.36f, cap = StrokeCap.Butt),
        )
        val b = d * 0.56f
        val len = d * 0.07f
        listOf(
            Triple(Offset(c.x - b, c.y - b), 1f, 1f),
            Triple(Offset(c.x + b, c.y - b), -1f, 1f),
            Triple(Offset(c.x - b, c.y + b), 1f, -1f),
            Triple(Offset(c.x + b, c.y + b), -1f, -1f),
        ).forEach { (p, dx, dy) ->
            drawLine(Paper.ink.copy(alpha = 0.6f * alpha), p, Offset(p.x + dx * len, p.y), sw * 0.3f)
            drawLine(Paper.ink.copy(alpha = 0.6f * alpha), p, Offset(p.x, p.y + dy * len), sw * 0.3f)
        }
    }
}

/* ── helpers ───────────────────────────────────────────────────────────────── */

@Composable
private fun TypedLine(text: String, local: Float, style: TextStyle) {
    Row {
        text.forEachIndexed { i, ch ->
            val a = ((local - i * 0.055f) / 0.32f).coerceIn(0f, 1f)
            BasicText(
                ch.toString(),
                modifier = Modifier.graphicsLayer {
                    alpha = a
                    translationY = (1f - a) * 10f
                },
                style = style,
            )
        }
    }
}

private fun lerpBlackToPaper(t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = Paper.bg.red * k,
        green = Paper.bg.green * k,
        blue = Paper.bg.blue * k,
        alpha = 1f,
    )
}

private fun Context.thump(ms: Long) {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(ms, 120))
    }
}
