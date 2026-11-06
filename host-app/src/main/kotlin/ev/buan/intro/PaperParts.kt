package ev.buan.intro

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/**
 * 启动屏的四个视觉零件：准星、打字机一行、纸色插值、一次触觉反馈。
 *
 * 它们从 `PaperStartup` 拆出来，不是为了行数，而是因为它们**与启动流程无关**：
 * 没有一个知道现在是哪一拍、加载到了哪一步。换一套开场动画时，这一文件里的东西
 * 大部分还能留用。
 *
 * 一律 `internal` 而不是 `private`：它们跨文件了，但对宿主之外仍不可见。
 */

/** 一个字开始浮现到相邻一个字开始浮现之间的相位差。 */
private const val PHASE_PER_CHAR = 0.055f

/** 一个字从透明到不透明的时长。 */
private const val FADE = 0.32f

/**
 * 取景准星：一圈正转的外环、一圈反转的内环、四角括号。
 *
 * [alpha] 是这一拍的透明度，由调用方按进度给；[elapsed] 只用来决定转角——
 * 两个环的时间系数（40 与 66）不同，就是它看起来"在扫描"的全部原因。
 */
@Composable
internal fun Reticle(elapsed: Float, alpha: Float) {
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

/**
 * 一个字一个字浮现的一行。
 *
 * 逐字而不是整行淡入：整行淡入读起来像"字幕已经在那儿了"，逐字才像"正在被打出来"，
 * 而这段启动屏要表达的就是机器在读。
 */
@Composable
internal fun TypedLine(text: String, local: Float, style: TextStyle) {
    Row {
        text.forEachIndexed { i, ch ->
            val a = ((local - i * PHASE_PER_CHAR) / FADE).coerceIn(0f, 1f)
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

/** 黑到纸色的插值。启动的第一帧必须是纯黑，否则会先闪一下灰。 */
internal fun lerpBlackToPaper(t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = Paper.bg.red * k,
        green = Paper.bg.green * k,
        blue = Paper.bg.blue * k,
        alpha = 1f,
    )
}

/** 一次短触觉。失败就静默：没有马达、系统禁止、服务拿不到，都不该让启动屏崩掉。 */
internal fun Context.thump(ms: Long) {
    runCatching {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(ms, 120))
    }
}
