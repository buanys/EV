package ev.buan.plugins.ev.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import ev.buan.ui.evGlassLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.systemBars
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import ev.buan.plugins.ev.ui.ui.EvComposer
import ev.buan.plugins.ev.ui.ui.EvComposerAction
import ev.buan.plugins.ev.ui.ui.EvComposerPanel
import ev.buan.plugins.ev.ui.ui.EvModelSheet
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvMotionTokens
import ev.buan.ui.EvThemeTokens

/**
 * The composer and the `+` panel above it.
 *
 * The panel grows upward out of the composer's own slot, so the two share one vertical boundary
 * while it springs open (spec §7.3). Every way out is a callback: this file never decides where a
 * tap goes, it only reports it, which is what let the surface lose its layout code.
 */
@Composable
private fun EvComposerPanels(
    plusActive: Boolean,
    onOpenBrowser: () -> Unit,
    onOpenTools: () -> Unit,
    modelSheetOpen: Boolean,
    models: List<String>,
    modelName: String?,
    onPickModel: (String) -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = plusActive,
        enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeIn(EvMotionTokens.fadeFast()),
        exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeOut(EvMotionTokens.fadeFast()),
    ) {
        EvComposerPanel(
            actions = listOf(
                EvComposerAction(EvGlyphKind.Globe, "浏览器", onOpenBrowser),
                EvComposerAction(EvGlyphKind.Tool, "工具", onOpenTools),
            ),
        )
    }

    // Same boundary as the `+` panel: the sheet belongs to the composer, not to a dialog.
    androidx.compose.animation.AnimatedVisibility(
        visible = modelSheetOpen,
        enter = expandVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeIn(EvMotionTokens.fadeFast()),
        exit = shrinkVertically(EvMotionTokens.reducedAware<IntSize>(EvThemeTokens.reducedMotion) { EvMotionTokens.gentle() }) +
            fadeOut(EvMotionTokens.fadeFast()),
    ) {
        EvModelSheet(models = models, current = modelName, onPick = onPickModel)
    }

}

@Composable
internal fun EvComposerArea(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    busy: Boolean,
    onStop: () -> Unit,
    plusActive: Boolean,
    onPlus: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTools: () -> Unit,
    modelName: String?,
    models: List<String>,
    modelSheetOpen: Boolean,
    onModelClick: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    // One sheet, not three cards: the glass wraps the panels and the field together, so an open
    // panel reads as the composer growing rather than as a second surface landing on top of it.
    val sheetShape = RoundedCornerShape(
        topStart = EvThemeTokens.radii.large,
        topEnd = EvThemeTokens.radii.large,
        bottomStart = EvThemeTokens.radii.large,
        bottomEnd = EvThemeTokens.radii.large,
    )

    // 排障：三个 inset 一起打。ime=0 只说明键盘没弹（或者没传进来），
    // 但 nav/systemBars 也是 0 的话，就是整条 inset 分发链断了——那时改 padding 组合全都没用。
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val sysBottom = WindowInsets.systemBars.getBottom(density)
    androidx.compose.runtime.LaunchedEffect(imeBottom, navBottom, sysBottom) {
        android.util.Log.i("EV", "insets: ime=$imeBottom nav=$navBottom sys=$sysBottom px")
    }
    // 玻璃和间距都在**这一层**：`evGlassLayer` 画的是本 Box 的矩形，如果把 padding 加在内层
    // EvComposer 上，内容会缩进去而玻璃仍然贴着屏幕底——看起来就像"只有按钮行有间距"。
    Box(
        Modifier
            .fillMaxWidth()
            // 键盘和底部手势条取并集，不是串联：串联会把两个高度加起来。
            // navigationBars 在这里已被父级的 evSystemBarsPadding 消费成 0，
            // 所以键盘收起时真正起作用的是下面的 6dp——视觉呼吸，不是安全区。
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
    ) {
        // The field floats over the transcript, so it needs more body than the drawer's glass:
        // 30% of the theme blur (a hint of what is underneath, not a readable copy of it) and a
        // denser tint, so the placeholder and the cursor never compete with a message scrolling by.
        Box(
            Modifier.matchParentSize()
                // 之前是 tintAlpha=0.72 / blurScale=0.3：模糊只有主题的三成，底色又太透，
                // 结果后面滚过去的消息能直接读出来，输入的文字反而看不清。
                // 输入框是"正在写字的地方"，可读性优先于通透——模糊拉满，底色压到接近实心。
                .evGlassLayer(sheetShape, tintAlpha = 0.94f, blurScale = 1f),
        )
        // 边缘跑马灯：沿玻璃卡片的真实轮廓走，不是四条边各画一条线——
        // 圆角处会断、四段接缝会亮成四个点，一眼就是拼出来的。
        ComposerEdgeLight(
            cornerRadius = EvThemeTokens.radii.large,
            modifier = Modifier.matchParentSize(),
        )
        Column {
            EvComposerPanels(
                plusActive = plusActive,
                onOpenBrowser = onOpenBrowser,
                onOpenTools = onOpenTools,
                modelSheetOpen = modelSheetOpen,
                models = models,
                modelName = modelName,
                onPickModel = onPickModel,
            )
            EvComposer(
        value = draft,
        onValueChange = onDraftChange,
        onSend = onSend,
        placeholder = placeholder,
        busy = busy,
        onStop = onStop,
        plusActive = plusActive,
        onPlus = onPlus,
        onFocusChange = onFocusChange,
        modelName = modelName,
        opaque = false,
        onModelClick = onModelClick,
            // 间距已经在外层（玻璃那一层）处理，这里不再重复加，否则内容会比玻璃多缩一段。
            modifier = Modifier,
        )
        }
    }
}

/**
 * 输入框边缘的一圈蓝色流光。
 *
 * 用 [PathMeasure] 沿卡片的真实轮廓取点，而不是四条 `drawLine`：
 * 圆角处会断、四个角接缝会各亮一个点，一眼就看出是拼出来的。
 *
 * "锋芒毕露但极其细腻"是靠三件事做到的：
 *  - 点小（1.1dp）、间隔密（1.5dp），连起来是一条连续的光而不是一串珠子；
 *  - 拖尾用平方衰减，后段迅速收掉——头部锐利，尾部化开；
 *  - 点的半径也随衰减收缩，两端才收得成尖。
 */
@Composable
private fun ComposerEdgeLight(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "composerEdge")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // 一个完整周期 = 走完全程 + 停顿。停顿那段什么都不画，靠时间线切出来，
        // 不是靠"跑完了再从头开始"——那样右下角还没收干净，左上角就又冒出来了。
        animationSpec = infiniteRepeatable(
            animation = tween(EDGE_MOVE_MS + EDGE_GAP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "composerEdgePhase",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val radius = cornerRadius.toPx()
        // 描边压在边线上而不是贴在外沿：外沿会被父级的 padding 裁掉半条。
        val inset = 0.6.dp.toPx()
        val outline = Path().apply {
            addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = w - inset,
                    bottom = h - inset,
                    cornerRadius = CornerRadius(radius),
                ),
            )
        }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val length = measure.length
        if (length <= 0f) return@Canvas

        // 时间线：先走完，再停顿，然后才重来。
        // phase 是一整个周期（走 + 停）的进度，所以这里换算出"现在处于哪一段"。
        val elapsed = phase * (EDGE_MOVE_MS + EDGE_GAP_MS)
        if (elapsed >= EDGE_MOVE_MS) return@Canvas
        val p = elapsed / EDGE_MOVE_MS

        // 两条光带都从**左上角**出发，各走一条边，到**右下角**汇合。
        //
        // 圆角矩形的半周长恰好是 w + h：从左上顺时针走一半就到右下，逆时针走一半也是。
        // 所以不需要两条独立的 Path——同一个 PathMeasure 上取两段相反方向的间距就行。
        val half = length / 2f
        val trail = EDGE_TRAIL_DP.dp.toPx()

        val headForward = p * half
        val headBackward = length - p * half

        // 拖尾长度同时受三个约束：
        //  1. 不超过全长；
        //  2. 不超过"已经走过的距离"——否则尾巴会越过起点，从另一条边上冒出来；
        //  3. 不超过"到终点的剩余距离"——光带在右下角是被**吸走**的，不是硬生生截断。
        // 第 2、3 条让它在起点慢慢长出来、在终点慢慢收掉，两端都是渐变的。
        val limitForward = min(trail, min(headForward, half - headForward))
        val limitBackward = min(trail, min(length - headBackward, headBackward - half))

        drawBolt(measure, length, head = headForward, direction = -1f, trailLimit = limitForward)
        drawBolt(measure, length, head = headBackward, direction = 1f, trailLimit = limitBackward)
    }
}

/**
 * 一道梭形光：从 [head] 起，沿 [direction] 方向铺开一条 [EDGE_TRAIL_DP] 长的渐尖光带。
 *
 * [direction] 为 -1 表示拖尾朝负数方向（顺时针那一路），+1 表示反过来（逆时针那一路）。
 * 两路各自收尖，所以在右下角汇合时是"两把刀尖对在一起"，不是一条粗线接上。
 */
private fun DrawScope.drawBolt(
    measure: PathMeasure,
    length: Float,
    head: Float,
    direction: Float,
    trailLimit: Float,
) {
    if (trailLimit <= 1f) return
    val segments = EDGE_SEGMENTS

    for (i in 0 until segments) {
        val tStart = i / segments.toFloat()
        val tEnd = (i + 1) / segments.toFloat()
        val envelope = sin(Math.PI * ((tStart + tEnd) / 2f)).toFloat()
        if (envelope <= 0.01f) continue

        val from = head + direction * tStart * trailLimit
        val to = head + direction * tEnd * trailLimit
        // 出界就丢，不回绕：回绕会让尾巴跨过起点出现在另一条边上。
        if (from < 0f || from > length || to < 0f || to > length) continue

        val a = measure.getPosition(from)
        val b = measure.getPosition(to)

        drawLine(
            color = ComposerEdgeBlue.copy(alpha = envelope * 0.95f),
            start = a,
            end = b,
            strokeWidth = (0.06f + 0.5f * envelope) * density,
            cap = StrokeCap.Round,
        )
    }
}

/** 取模落回 [0, length)。负数也要正确回绕，否则光带会在起点突然断掉。 */
private fun wrap(value: Float, length: Float): Float {
    val mod = value % length
    return if (mod < 0f) mod + length else mod
}

/** 拖尾长度（dp）。整圈周长在这块屏上约 1100px，取 96dp 大约占 27%：短了像一颗子弹在屏幕上跑。 */
/** 走完半圈用多久。550dp 的行程，7.5s 约 73dp/s，慢到能看清它在走。 */
private const val EDGE_MOVE_MS = 7500

/** 走完之后停多久再重来。没有这一段，右下角还没收干净左上角就又冒出来了。 */
private const val EDGE_GAP_MS = 1000

private const val EDGE_TRAIL_DP = 132f

/** 拖尾切成多少段。132dp / 60 ≈ 2.2dp 一段，包络平滑且只有 60 条线。 */
private const val EDGE_SEGMENTS = 60

/** 冷蓝，和 HUD 胶囊的边缘光同源，两个浮层看起来才像一套东西。 */
private val ComposerEdgeBlue = Color(0xFF5AAEFF)
