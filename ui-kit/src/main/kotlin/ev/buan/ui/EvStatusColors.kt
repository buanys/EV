package ev.buan.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Semantic status colours, read from the active theme.
 *
 * They used to be two hardcoded values plus two Miuix lookups, which meant a theme could repaint
 * the page but not a success tick or an error line - the states that carry the most meaning were the
 * ones it could not reach. They now come from [EvColorTokens], so a theme defines them like any
 * other colour.
 */
val StatusSuccess: Color
    @Composable @ReadOnlyComposable get() = EvThemeTokens.colors.success

val StatusWarning: Color
    @Composable @ReadOnlyComposable get() = EvThemeTokens.colors.warning

val StatusError: Color
    @Composable @ReadOnlyComposable get() = EvThemeTokens.colors.danger

val StatusRunning: Color
    @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.primary

val StatusIdle: Color
    @Composable @ReadOnlyComposable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
