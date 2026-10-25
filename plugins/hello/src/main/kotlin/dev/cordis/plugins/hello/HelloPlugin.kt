package dev.cordis.plugins.hello

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvCard
import ev.buan.ui.EvCardDefaults
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Minimal plugin used to prove the host pipeline: asset copy -> SHA-256 pin -> DexClassLoader ->
 * Cordis Fiber activation -> UI drawn from the plugin's own dex against the host's design system.
 *
 * Note what this class does NOT do: it paints no background, picks no palette and ships no theme.
 * Colors, typography and the card shape all come from the host composition.
 */
class HelloPlugin : Plugin<Unit>, AgentSurface, AgentUi {
    override val name = "hello-plugin"

    override val title = "Hello from an APK"

    override val body =
        "This text, this class and the Plugin identity the host activated all came from " +
            "hello.apk through a child-first DexClassLoader. No system install happened."

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) = Unit

    @Composable
    override fun Content(modifier: Modifier) {
        EvCard(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            insideMargin = PaddingValues(18.dp),
        ) {
            Column {
                Text(
                    text = "Compose from inside hello.apk",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = "These calls live in the plugin dex. The theme, the card shape and every " +
                        "color come from the host, resolved through the shared package allowlist.",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "plugin class loader: separate · ui kit: host · corner ${EvCardDefaults.CornerRadius.value.toInt()}dp",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
