package ev.buan.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * How one full-screen surface replaces another.
 *
 * Pages used to swap instantly, which is why navigation felt like a repaint rather than a move. The
 * language here is deliberately small: entering a page drifts in from the side it belongs to and
 * settles, leaving drifts back. The distance is a fraction of the width, so it reads as motion, not
 * as a slide deck - a full-width slide at this scale is slow and makes a two-tap trip feel long.
 *
 * Reduced motion degrades to a plain cross-fade: a page still resolves, but nothing travels.
 */
object EvPageTransitions {

    /** Fraction of the width a page travels. Small on purpose; see the comment above. */
    private const val TRAVEL = 8

    fun forward(reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(tween(120)) togetherWith fadeOut(tween(120))
        val enter = slideInHorizontally(animationSpec = EvMotionTokens.gentle()) { width -> width / TRAVEL } +
            fadeIn(EvMotionTokens.fadeFast()) +
            scaleIn(initialScale = 0.985f, animationSpec = EvMotionTokens.snappy())
        val exit = slideOutHorizontally(animationSpec = EvMotionTokens.gentle()) { width -> -width / (TRAVEL * 2) } +
            fadeOut(EvMotionTokens.fadeFast()) +
            scaleOut(targetScale = 0.985f, animationSpec = EvMotionTokens.snappy())
        return enter togetherWith exit
    }

    fun backward(reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(tween(120)) togetherWith fadeOut(tween(120))
        val enter = slideInHorizontally(animationSpec = EvMotionTokens.gentle()) { width -> -width / (TRAVEL * 2) } +
            fadeIn(EvMotionTokens.fadeFast()) +
            scaleIn(initialScale = 0.985f, animationSpec = EvMotionTokens.snappy())
        val exit = slideOutHorizontally(animationSpec = EvMotionTokens.gentle()) { width -> width / TRAVEL } +
            fadeOut(EvMotionTokens.fadeFast()) +
            scaleOut(targetScale = 0.985f, animationSpec = EvMotionTokens.snappy())
        return enter togetherWith exit
    }
}
