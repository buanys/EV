package ev.buan.plugins.ev.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import ev.buan.ui.EvGlyph
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvThemeTokens
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.PluginSetting

/**
 * The declared capabilities of every plugin, read from the host catalog.
 *
 * This is the page that answers "what does this thing actually do to my phone". It renders the
 * declaration the plugin made about itself - so a plugin that ships a shell says so in the list,
 * instead of the user having to find that out by having the model run a command.
 */
@Composable
fun EvPluginCatalogScreen(
    manifests: List<PluginManifest>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<PluginManifest?>(null) }
    val detail = selected
    if (detail != null) {
        PluginDetail(detail, onBack = { selected = null }, modifier = modifier)
        return
    }

    EvPage(
        title = "插件能力",
        subtitle = if (manifests.isEmpty()) "还没有插件声明自己的能力" else "${manifests.size} 个插件",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            if (manifests.isEmpty()) {
                EvSettingsSection("说明")
                EvSettingsGroup {
                    EvSettingsRow(
                        title = "没有声明",
                        glyph = EvGlyphKind.Info,
                        summary = "插件可以在入口类实现 ManifestProvider 来声明能力，宿主会汇总到这里。",
                        onClick = null,
                    )
                }
                return@item
            }
            EvSettingsSection("已声明")
            EvSettingsGroup {
                manifests.forEach { manifest ->
                    EvSettingsRow(
                        title = manifest.name,
                        glyph = EvGlyphKind.Grid,
                        summary = "${manifest.version} · ${manifest.description}",
                        onClick = { selected = manifest },
                    )
                }
            }
        }
    }
}

/** One plugin: what it publishes, what it may touch, and what it can be configured with. */
@Composable
private fun PluginDetail(
    manifest: PluginManifest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    EvPage(
        title = manifest.name,
        subtitle = "${manifest.version} · ${manifest.id}",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            EvSettingsSection("它做什么")
            EvSettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = manifest.description,
                        style = EvThemeTokens.type.body.style(tokens.textSecondary),
                    )
                }
            }
        }

        if (manifest.capabilities.isNotEmpty()) {
            item {
                EvSettingsSection("能力")
                EvSettingsGroup {
                    manifest.capabilities.forEach { capability ->
                        CapabilityRow(capability)
                    }
                }
            }
        }

        if (manifest.permissions.isNotEmpty()) {
            item {
                EvSettingsSection("系统权限")
                EvSettingsGroup {
                    manifest.permissions.forEach { permission ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = permission.substringAfterLast('.'),
                                style = EvThemeTokens.type.body.style(tokens.textPrimary),
                            )
                        }
                    }
                }
            }
        }

        if (manifest.settings.isNotEmpty()) {
            item {
                EvSettingsSection("配置项")
                EvSettingsGroup {
                    manifest.settings.forEach { setting -> SettingRow(setting) }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(capability: PluginCapability) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = capability.title,
                style = EvThemeTokens.type.body.style(tokens.textPrimary),
            )
            if (capability.description.isNotBlank()) {
                Text(
                    text = capability.description,
                    style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                )
            }
        }
        Text(
            text = capability.kind.name.lowercase(),
            style = EvThemeTokens.type.micro.style(tokens.textTertiary),
        )
    }
}

@Composable
private fun SettingRow(setting: PluginSetting) {
    val tokens = EvThemeTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = setting.title,
                style = EvThemeTokens.type.body.style(tokens.textPrimary),
            )
            if (setting.description.isNotBlank()) {
                Text(
                    text = setting.description,
                    style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                )
            }
        }
        if (setting.value.isNotBlank()) {
            Text(
                text = setting.value,
                style = EvThemeTokens.type.label.style(tokens.textSecondary),
            )
        }
    }
}
