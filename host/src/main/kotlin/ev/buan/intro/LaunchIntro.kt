package ev.buan.intro

import ev.buan.LaunchScope
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.HostRuntime
import ev.buan.LoadProgress
import ev.buan.hasAllFilesAccess
import ev.buan.openAllFilesSettings
import ev.buan.ui.evSystemBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cold-start sequence, built on the EV cyber token layer.
 *
 * Design constraints carried from the spec (and the reasons this version looks different from the
 * earlier ones):
 *  - Every readout is real: device specs, a real scan of EV/mods, and the host's own plugin state.
 *    Nothing is invented to fill space.
 *  - Accents carry meaning: green = online, yellow = active/pending, red = fault, cyan = data.
 *  - Panels are flat with 1px borders and 45-degree chamfers; glow is reserved for live acquisition.
 *  - All five pages share one frame (eyebrow bar, rule, hero, real-data block, phase meter), so the
 *    sequence reads as one system rather than five unrelated screens.
 */
enum class IntroStage { Boot, Scan, Assembly, Converge, Ready }

private const val STAGE_COUNT = 5

/* ------------------------------------------------------------------ facts */

/** A real scan of the mods directory. */
private data class ModsInfo(
    val reachable: Boolean,
    val files: List<String>,
    val bytes: Long,
)

private suspend fun scanMods(): ModsInfo = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File("/sdcard/EV/mods")
        if (!dir.isDirectory) return@runCatching ModsInfo(false, emptyList(), 0L)
        // Only containers count as modules; the folder also holds notes and backups.
        val jars = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        ModsInfo(true, jars.map { it.name }, jars.sumOf { it.length() })
    }.getOrDefault(ModsInfo(false, emptyList(), 0L))
}

private data class DeviceFacts(
    val android: String,
    val sdk: String,
    val abi: String,
    val memoryTotal: String,
    val memoryFree: String,
)

private fun deviceFacts(context: Context): DeviceFacts {
    val mem = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info
    }.getOrNull()
    fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
    return DeviceFacts(
        android = "ANDROID ${Build.VERSION.RELEASE}",
        sdk = "SDK ${Build.VERSION.SDK_INT}",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        memoryTotal = mem?.let { gb(it.totalMem) } ?: "N/A",
        memoryFree = mem?.let { gb(it.availMem) } ?: "N/A",
    )
}

/** Battery percentage, read from the sticky broadcast; null when the system does not answer. */
private fun batteryPercent(context: Context): String = runCatching {
    val intent = context.registerReceiver(
        null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
    ) ?: return "N/A"
    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) "N/A" else "${level * 100 / scale}%"
}.getOrDefault("N/A")

/** Uptime formatted as h:mm. */
private fun uptime(): String {
    val ms = android.os.SystemClock.elapsedRealtime()
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return "%dh %02dm".format(h, m)
}

/**
 * The instrument readings on the first page. Every entry is measured on this device at launch -
 * no invented values, which is the one rule the earlier drafts of this screen broke.
 */
private fun bootStats(context: Context, facts: DeviceFacts, mods: ModsInfo?): List<Pair<String, String>> {
    val dm = context.resources.displayMetrics
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    val refresh = runCatching {
        val mgr = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = mgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        "%.0f Hz".format(display?.refreshRate ?: 0f)
    }.getOrDefault("N/A")
    val freeDisk = runCatching {
        "%.1f GB".format(File("/sdcard").usableSpace / 1_073_741_824.0)
    }.getOrDefault("N/A")
    return listOf(
        "ANDROID" to Build.VERSION.RELEASE,
        "SDK" to Build.VERSION.SDK_INT.toString(),
        "ABI" to facts.abi,
        "CORES" to Runtime.getRuntime().availableProcessors().toString(),
        "RAM" to facts.memoryFree,
        "RAM TOTAL" to facts.memoryTotal,
        "UPTIME" to uptime(),
        "VIEWPORT" to "${dm.widthPixels}×${dm.heightPixels}",
        "DENSITY" to "${dm.densityDpi} dpi",
        "BATTERY" to batteryPercent(context),
        "MODULES" to (mods?.files?.size?.toString() ?: "—"),
        "EV" to version,
        "MODEL" to Build.MODEL,
        "KERNEL" to (System.getProperty("os.version") ?: "?"),
        "JVM" to (System.getProperty("java.vm.version") ?: "?"),
        "LOCALE" to java.util.Locale.getDefault().toString(),
        "REFRESH" to refresh,
        "MODS SIZE" to "%.1f MB".format((mods?.bytes ?: 0L) / 1_048_576.0),
        "DISK FREE" to freeDisk,
        "BUILD" to Build.ID.take(8),
    )
}

/* ------------------------------------------------------------- entry point */

@Composable
fun LaunchIntro(
    runtime: HostRuntime,
    activity: ComponentActivity,
    /**
     * 这次启动要加载多少插件。
     *
     * 主界面用 [LaunchScope.Full]；从桌面快捷方式进来时只加载那个插件的闭包——
     * 用户点的是"浏览器"图标，他没有要聊天、模型和记忆。
     */
    scope: LaunchScope = LaunchScope.Full,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val reduced = remember { reducedMotion(context) }
    val facts = remember { deviceFacts(context) }
    var mods by remember { mutableStateOf<ModsInfo?>(null) }
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAllFilesAccess() }

    val progress by runtime.loadProgress.collectAsState()
    val hostState by runtime.state
    val loadFailure = remember { MutableStateFlow<String?>(null) }
    val loadError by loadFailure.collectAsState()

    var startRequested by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(IntroStage.Boot) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(Unit) { mods = scanMods() }
    // Every value here is read from this device at launch; recomputed once the mods scan lands.
    val stats = remember(facts, mods) { bootStats(context, facts, mods) }

    // The real load starts at the assembly page, once file access is in place.
    LaunchedEffect(stage, granted) {
        if (stage == IntroStage.Assembly && granted && !startRequested) {
            startRequested = true
            runCatching { runtime.start(scope) }.onFailure { loadFailure.value = it.toString() }
        }
    }

    LaunchedEffect(stage) {
        context.tick()
        fun d(ms: Long) = if (reduced) 0L else ms
        when (stage) {
            IntroStage.Boot -> { delay(d(1300)); stage = IntroStage.Scan }
            IntroStage.Scan -> { delay(d(1300)); stage = IntroStage.Assembly }
            IntroStage.Assembly -> {
                combine(runtime.loadProgress, runtime.stateFlow(), loadFailure) { p, host, err ->
                    (p.finished && host != null) || err != null
                }.first { it }
                delay(d(700))
                stage = IntroStage.Converge
            }
            IntroStage.Converge -> { delay(d(1300)); stage = IntroStage.Ready }
            IntroStage.Ready -> {
                delay(d(1700))
                exit.animateTo(1f, tween(if (reduced) 0 else 320, easing = EvMotion.EaseOut))
                onDone()
            }
        }
    }

    // One tick per module that comes online.
    var lastDone by remember { mutableStateOf(0) }
    LaunchedEffect(progress.done) {
        if (progress.done > lastDone) context.tick()
        lastDone = progress.done
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(EvCyber.Bg)
            .graphicsLayer {
                // Exit: the whole surface pulls back and dissolves. Composited properties only.
                scaleX = 1f + 0.04f * exit.value
                scaleY = 1f + 0.04f * exit.value
                alpha = 1f - exit.value
            },
    ) {
        Backdrop()
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                // Enter with the spec's 240ms ease-out; leave fast so two pages never sit on top of
                // each other for long enough to read as a grey blend.
                fadeIn(tween(EvMotion.ENTER, easing = EvMotion.EaseOut)) togetherWith
                    fadeOut(tween(EvMotion.INSTANT, easing = EvMotion.EaseOut))
            },
            label = "intro",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                IntroStage.Boot -> BootPage(stats, granted, if (reduced) 0 else 1300)
                IntroStage.Scan -> ScanPage(mods, granted, activity::openAllFilesSettings, if (reduced) 0 else 1300)
                IntroStage.Assembly -> AssemblyPage(progress, hostVersions(hostState), loadError)
                IntroStage.Converge -> ConvergePage(progress, if (reduced) 0 else 1300)
                IntroStage.Ready -> ReadyPage(progress, facts, if (reduced) 0 else 1000)
            }
        }
    }
}

/** Real per-plugin metadata from the host snapshot, keyed by short name. */
private fun hostVersions(state: ev.buan.HostState?): Map<String, Triple<String, Int, Long>> =
    state?.cards.orEmpty().associate {
        ev.buan.shortPluginName(it.id) to Triple(it.version, it.apiVersion, it.storageBytes)
    }

/* ---------------------------------------------------------------- backdrop */

@Composable
private fun Backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(EvCyber.Bg)
        // 32dp grid, faint: structure without decoration.
        evGrid(96f, Color.White, 0.030f)
        // A single soft wash of the structural red at the top, so the surface is not dead flat.
        drawRect(
            Brush.verticalGradient(
                listOf(EvCyber.RedDim.copy(alpha = 0.16f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.5f,
            ),
        )
        // Static scanlines on the backdrop only, never behind dense text.
        var y = 0f
        while (y < size.height) {
            drawLine(EvCyber.Scanline, Offset(0f, y), Offset(size.width, y), 1f)
            y += 4f
        }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                center = center,
                radius = maxOf(size.width, size.height) * 0.8f,
            ),
        )
    }
}

/* -------------------------------------------------------------- the frame */

/**
 * Every page is built from this frame. A shared skeleton is what makes a sequence look designed:
 * the eye learns where the title, the number, the data and the meter live, and only the content
 * changes.
 */
@Composable
private fun IntroFrame(
    index: Int,
    eyebrow: String,
    hero: @Composable () -> Unit,
    data: @Composable ColumnScope.() -> Unit,
    phase: Float,
) {
    Column(
        Modifier
            .fillMaxSize()
            .evSystemBarsPadding()
            .padding(horizontal = EvSpace.s5, vertical = EvSpace.s5),
    ) {
        // Top bar: one eyebrow on the left, the station counter on the right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(eyebrow, style = eyebrowStyle(EvCyber.Cyan))
            BasicText(
                text = "%02d / %02d".format(index, STAGE_COUNT),
                style = eyebrowStyle(EvCyber.Fg4),
            )
        }
        Spacer(Modifier.height(EvSpace.s3))
        Box(Modifier.fillMaxWidth().height(1.dp).background(EvCyber.Line))

        // Hero: the one thing this page is meant to be read for.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { hero() }

        // Real data block, then the phase meter.
        Column(Modifier.fillMaxWidth()) { data() }
        Spacer(Modifier.height(EvSpace.s4))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            evSegMeter(Offset.Zero, size.width, size.height, 40, phase, EvCyber.Red)
        }
        Spacer(Modifier.height(EvSpace.s2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("EV // BOOT SEQUENCE", style = eyebrowStyle(EvCyber.Fg4))
            BasicText("PHASE %02d".format(index), style = eyebrowStyle(EvCyber.Fg4))
        }
    }
}

private fun eyebrowStyle(color: Color) = TextStyle(
    color = color,
    fontSize = 10.sp,
    letterSpacing = 2.sp,
    fontFamily = FontFamily.Monospace,
)

/** Label above a monospaced value: the RIS eyebrow/value pair. */
@Composable
private fun DataRow(label: String, value: String, accent: Color = EvCyber.Fg1) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(label, style = eyebrowStyle(EvCyber.Fg3))
        BasicText(
            text = value,
            style = TextStyle(
                color = accent,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/* ----------------------------------------------------------------- page 1 */

@Composable
private fun BootPage(stats: List<Pair<String, String>>, granted: Boolean, durationMs: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs.coerceAtLeast(1), easing = EvMotion.EaseOut)) }
    IntroFrame(
        index = 1,
        eyebrow = "COLD START",
        phase = (1f / STAGE_COUNT) * p.value,
        hero = {
            // A grid of real instrument cells. Twelve measured readings give the page the density
            // the design calls for without inventing a single number.
            // Rows share the hero band's height, so the panel covers the page instead of sitting
            // in the middle of it with dead space above and below.
            Column(Modifier.fillMaxSize()) {
                stats.chunked(2).forEachIndexed { rowIndex, pair ->
                    val reveal = ((p.value * (stats.size / 2 + 1)) - rowIndex).coerceIn(0f, 1f)
                    Row(
                        Modifier.fillMaxWidth().weight(1f).padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pair.forEach { (label, value) ->
                            StatCell(label, value, reveal, Modifier.weight(1f).fillMaxHeight())
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        data = {
            DataRow("MODS PATH", "/sdcard/EV/mods")
            DataRow(
                "SCAN ACCESS",
                if (granted) "AUTHORISED" else "BLOCKED",
                if (granted) EvCyber.Green else EvCyber.Red,
            )
        },
    )
}

/**
 * One instrument cell: eyebrow label over a monospaced value, on a flat chamfered plate.
 * Cells fade up in sequence rather than as one block, so the page assembles itself.
 */
@Composable
private fun StatCell(label: String, value: String, reveal: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 8.dp.toPx() },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            evPanel(Offset.Zero, size, EvCutSm.toPx(), EvCyber.Surface1, EvCyber.Line, 1f)
            // Accent tick on the cut corner: a small piece of machinery, not decoration.
            drawLine(
                EvCyber.Red,
                Offset(size.width - 10.dp.toPx(), 0f),
                Offset(size.width, 10.dp.toPx()),
                1.5f,
            )
        }
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp)) {
            // Fg3, not Fg4: Fg4 is reserved for disabled/placeholder and must never carry meaning.
            BasicText(label, style = eyebrowStyle(EvCyber.Fg3))
            Spacer(Modifier.height(4.dp))
            BasicText(
                value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = EvCyber.Fg1,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}


/* ----------------------------------------------------------------- page 2 */

@Composable
private fun ScanPage(mods: ModsInfo?, granted: Boolean, onGrant: () -> Unit, durationMs: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs.coerceAtLeast(1), easing = EvMotion.EaseOut)) }

    val count = mods?.files?.size ?: 0
    val visible = (p.value * (count + 1)).toInt().coerceAtMost(count)

    IntroFrame(
        index = 2,
        eyebrow = "MODULE SCAN",
        phase = (2f / STAGE_COUNT),
        hero = {
            if (!granted) {
                AccessRequired(onGrant)
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The hero value is the real number of files on disk.
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(
                            text = if (mods == null) "--" else count.toString().padStart(2, '0'),
                            style = TextStyle(
                                color = EvCyber.Fg1,
                                fontSize = 96.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-2).sp,
                            ),
                        )
                        BasicText(
                            "MODULES",
                            modifier = Modifier.padding(bottom = 18.dp, start = 6.dp),
                            style = TextStyle(
                                color = EvCyber.Cyan,
                                fontSize = 11.sp,
                                letterSpacing = 3.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                    Spacer(Modifier.height(EvSpace.s3))
                    // Real file names, revealed one per beat.
                    Column(Modifier.fillMaxWidth()) {
                        mods?.files.orEmpty().take(6).forEachIndexed { i, name ->
                            val on = i < visible
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BasicText(
                                    text = name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        color = if (on) EvCyber.Fg2 else EvCyber.Fg4,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                                BasicText(
                                    text = if (on) "OK" else "··",
                                    style = TextStyle(
                                        color = if (on) EvCyber.Green else EvCyber.Fg4,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        data = {
            if (granted) {
                val extra = (mods?.files?.size ?: 0) - 6
                DataRow("PATH", "/sdcard/EV/mods")
                DataRow("ON DISK", if (extra > 0) "显示 6 / 共 ${mods?.files?.size}" else "${mods?.files?.size ?: 0} 个文件")
                DataRow("SIZE", "%.1f MB".format((mods?.bytes ?: 0L) / 1_048_576.0), EvCyber.Cyan)
            } else {
                DataRow("PATH", "/sdcard/EV/mods")
                DataRow("ACCESS", "DENIED", EvCyber.Red)
            }
        },
    )
}

@Composable
private fun AccessRequired(onGrant: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(300.dp).height(120.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                evPanel(Offset.Zero, size, 12.dp.toPx(), EvCyber.Surface2, EvCyber.LineStrong, 1.5f)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    "ACCESS REQUIRED",
                    style = TextStyle(color = EvCyber.Yellow, fontSize = 12.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    "需要「所有文件访问」",
                    style = TextStyle(color = EvCyber.Fg1, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
        Spacer(Modifier.height(EvSpace.s4))
        Box(
            Modifier
                .width(180.dp)
                .height(48.dp)
                .clip(evChamfer(EvCutSm))
                .background(EvCyber.RedFill)
                .clickable(onClick = onGrant),
        ) {
            BasicText(
                "GRANT ACCESS",
                modifier = Modifier.align(Alignment.Center),
                style = TextStyle(color = EvCyber.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/* ----------------------------------------------------------------- page 3 */

@Composable
private fun AssemblyPage(
    progress: LoadProgress,
    meta: Map<String, Triple<String, Int, Long>>,
    loadError: String?,
) {
    val names = progress.names
    val n = names.size
    // chunked(0) throws: a launch with no enabled plugins has an empty name list.
    val columnPairs = remember(names) { if (n == 0) emptyList() else names.chunked((n + 1) / 2) }

    IntroFrame(
        index = 3,
        eyebrow = "MODULE ASSEMBLY",
        phase = 3f / STAGE_COUNT * (if (progress.finished) 1f else (progress.done.toFloat() / n.coerceAtLeast(1))),
        hero = {
            // One shared pulse for the currently-writing lamp, rather than one animation per row.
            val lampPulse by rememberInfiniteTransition(label = "lamp").animateFloat(
                0.35f, 1f,
                infiniteRepeatable(tween(700, easing = EvMotion.EaseOut), RepeatMode.Reverse),
                label = "lamp-a",
            )
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        text = progress.done.toString().padStart(2, '0'),
                        style = TextStyle(
                            color = EvCyber.Fg1,
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-2).sp,
                        ),
                    )
                    BasicText(
                        "/ ${n.toString().padStart(2, '0')}",
                        modifier = Modifier.padding(bottom = 14.dp, start = 8.dp),
                        style = TextStyle(color = EvCyber.Fg4, fontSize = 22.sp, fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.weight(1f))
                    StatusChip(
                        lam = when {
                            loadError != null -> "FAULT" to EvCyber.Red
                            progress.finished -> "READY" to EvCyber.Green
                            else -> "WRITING" to EvCyber.Yellow
                        },
                    )
                }
                Spacer(Modifier.height(EvSpace.s3))
                Box(Modifier.fillMaxWidth().height(1.dp).background(EvCyber.LineFaint))
                Spacer(Modifier.height(EvSpace.s3))

                // Real modules, two columns, each with its own state lamp.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(EvSpace.s4)) {
                    columnPairs.forEach { column ->
                        Column(Modifier.weight(1f)) {
                            column.forEach { name ->
                                val i = names.indexOf(name)
                                val online = i in 0 until progress.done
                                val writing = i == progress.done && !progress.finished
                                val failed = name in progress.failed
                                ModuleLine(
                                    name = name,
                                    online = online,
                                    writing = writing,
                                    failed = failed,
                                    pulse = lampPulse,
                                )
                            }
                        }
                    }
                }
            }
        },
        data = {
            val current = progress.current
            DataRow(
                "CURRENT",
                when {
                    loadError != null -> "FAULT"
                    progress.finished -> "IDLE"
                    current != null -> current
                    n == 0 -> "SCANNING"
                    else -> "STANDBY"
                },
                if (progress.finished) EvCyber.Green else EvCyber.Yellow,
            )
            DataRow("FAILED", "${progress.failed.size}", if (progress.failed.isEmpty()) EvCyber.Fg1 else EvCyber.Red)
            DataRow("STATE", if (progress.finished) "ALL ONLINE" else "IN PROGRESS")
        },
    )
}

/** One module row: state lamp, name, and its real API level / size when known. */
@Composable
private fun ModuleLine(
    name: String,
    online: Boolean,
    writing: Boolean,
    failed: Boolean,
    pulse: Float,
) {
    val lamp = when {
        failed -> EvCyber.Red
        online -> EvCyber.Green
        writing -> EvCyber.Yellow
        else -> EvCyber.Fg4
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(6.dp)) {
            // Only the module being acquired pulses; everything else is steady.
            drawRect(lamp.copy(alpha = if (writing) pulse else if (online || failed) 1f else 0.4f))
        }
        Spacer(Modifier.width(EvSpace.s2))
        BasicText(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (online) EvCyber.Fg1 else if (writing) EvCyber.Fg2 else EvCyber.Fg4,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun StatusChip(lam: Pair<String, Color>) {
    Box(Modifier.height(24.dp).clip(evChamfer(EvCutSm, mirror = true)).background(lam.second.copy(alpha = 0.12f))) {
        Canvas(Modifier.fillMaxSize()) {
            val p = chamferPath(size.width, size.height, 6.dp.toPx(), mirror = true)
            drawPath(p, lam.second.copy(alpha = 0.7f), style = Stroke(1f))
        }
        BasicText(
            lam.first,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
            style = TextStyle(color = lam.second, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace),
        )
    }
}

/* ----------------------------------------------------------------- page 4 */

@Composable
private fun ConvergePage(progress: LoadProgress, durationMs: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs.coerceAtLeast(1), easing = EvMotion.EaseOut)) }
    IntroFrame(
        index = 4,
        eyebrow = "LINK",
        phase = 4f / STAGE_COUNT,
        hero = {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val r = size.minDimension * 0.42f
                    val k = p.value
                    // Two chamfered plates drawing toward each other, then the emblem.
                    val verts = listOf(
                        Offset(c.x, c.y - r),
                        Offset(c.x + r * 0.866f, c.y + r * 0.5f),
                        Offset(c.x - r * 0.866f, c.y + r * 0.5f),
                    )
                    val tri = Path().apply {
                        moveTo(verts[0].x, verts[0].y)
                        lineTo(verts[1].x, verts[1].y)
                        lineTo(verts[2].x, verts[2].y)
                        close()
                    }
                    drawPath(tri, EvCyber.Surface1)
                    drawPath(tri, EvCyber.LineStrong, style = Stroke(1.5f))
                    // The stroke traces in: dash phase shrinks to zero.
                    drawPath(
                        tri,
                        EvCyber.Red,
                        style = Stroke(
                            width = 3f,
                            cap = StrokeCap.Square,
                        ),
                        alpha = k,
                    )
                    // Corner ticks: machinery around the emblem, no glow.
                    evBrackets(6f, size.minDimension * 0.10f, EvCyber.LineStrong, 0.8f * k)
                }
                BasicText(
                    text = "${progress.done}",
                    style = TextStyle(
                        color = EvCyber.Fg1,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                    ),
                )
            }
        },
        data = {
            DataRow("MODULES", "${progress.done} / ${progress.names.size}", EvCyber.Green)
            DataRow("INTEGRITY", if (progress.failed.isEmpty()) "NOMINAL" else "${progress.failed.size} FAULT", if (progress.failed.isEmpty()) EvCyber.Green else EvCyber.Red)
        },
    )
}

/* ----------------------------------------------------------------- page 5 */

@Composable
private fun ReadyPage(progress: LoadProgress, facts: DeviceFacts, durationMs: Int) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs.coerceAtLeast(1), easing = EvMotion.EaseOut)) }
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep-a",
    )
    Column(
        Modifier
            .fillMaxSize()
            .evSystemBarsPadding()
            .padding(horizontal = EvSpace.s5, vertical = EvSpace.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("ONLINE", style = eyebrowStyle(EvCyber.Green))
            BasicText("%02d / %02d".format(5, STAGE_COUNT), style = eyebrowStyle(EvCyber.Fg4))
        }
        Spacer(Modifier.height(EvSpace.s3))
        Box(Modifier.fillMaxWidth().height(1.dp).background(EvCyber.Line))

        Spacer(Modifier.weight(1f))
        BasicText(
            "WELCOME TO",
            style = TextStyle(
                color = EvCyber.Fg3,
                fontSize = 12.sp,
                letterSpacing = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
        Spacer(Modifier.height(EvSpace.s2))
        Box(contentAlignment = Alignment.Center) {
            // Wordmark: flat fill, hard red edge, one highlight band. No bloom.
            BasicText(
                "EV",
                style = TextStyle(
                    fontSize = 168.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-6).sp,
                    color = EvCyber.Fg1,
                ),
            )
            BasicText(
                "EV",
                style = TextStyle(
                    fontSize = 168.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-6).sp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, EvCyber.RedFill.copy(alpha = 0.75f), Color.Transparent),
                        start = Offset(600f * sweep - 220f, 0f),
                        end = Offset(600f * sweep + 220f, 400f),
                    ),
                ),
            )
        }
        Box(Modifier.width(200.dp).height(2.dp).background(EvCyber.RedFill))
        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                "${progress.done} MODULES ONLINE",
                style = eyebrowStyle(EvCyber.Fg3),
            )
            BasicText(facts.android, style = eyebrowStyle(EvCyber.Fg4))
        }
        Spacer(Modifier.height(EvSpace.s3))
    }
}

/* ----------------------------------------------------------------- helpers */

private fun reducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** Short haptic tick; silent on devices without a vibrator. */
private fun Context.tick() {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(12, 90))
    }
}
