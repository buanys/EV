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
import androidx.compose.ui.unit.Dp
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
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
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

        val head = phase * length
        val step = 1.5.dp.toPx()
        val dots = (EDGE_TRAIL_DP.dp.toPx() / step).toInt().coerceAtLeast(1)

        for (i in 0 until dots) {
            val fade = 1f - i / dots.toFloat()
            val alpha = fade * fade
            val distance = (head - i * step + length) % length
            // Compose 的 PathMeasure 只有 getPosition/getTangent，不暴露 getPosTan 的数组重载。
            val position = measure.getPosition(distance)
            drawCircle(
                color = ComposerEdgeBlue.copy(alpha = alpha),
                radius = 1.1.dp.toPx() * (0.35f + 0.65f * fade),
                center = position,
            )
        }
    }
}

/** 拖尾长度（dp）。整圈周长在这块屏上约 1100px，取 40dp 大约占 12%。 */
private const val EDGE_TRAIL_DP = 40f

/** 冷蓝，和 HUD 胶囊的边缘光同源，两个浮层看起来才像一套东西。 */
private val ComposerEdgeBlue = Color(0xFF5AAEFF)
