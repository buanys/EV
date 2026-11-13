package ev.buan.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

/**
 * Keeps content out of the status bar and navigation bar.
 *
 * With targetSdk 35+ the system forces edge-to-edge: the window is drawn behind the bars whether
 * the app asks for it or not. The host owns the window, so it also owns this padding - a plugin
 * must never have to guess how tall the status bar is on this device.
 *
 * Deliberately excludes the IME inset: a chat composer still wants [imePadding] to lift itself,
 * and consuming the keyboard inset here would zero it out for every plugin.
 */
@Composable
fun Modifier.evSystemBarsPadding(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars)
        .windowInsetsPadding(WindowInsets.displayCutout)
