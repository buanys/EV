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
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.EvThemeTokens
import org.cordis.api.PluginCapability
import org.cordis.api.ManagedPlugin
import org.cordis.api.PluginCatalogService
import org.cordis.api.PluginManagerService
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
    catalog: PluginCatalogService?,
    manager: PluginManagerService?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manifests = catalog?.manifests().orEmpty()
    var selected by remember { mutableStateOf<PluginManifest?>(null) }
    val detail = selected
    if (detail != null) {
        PluginDetail(
            manifest = detail,
            catalog = catalog,
            manager = manager,
            managed = manager?.plugins()?.firstOrNull { it.id == detail.id },
            onBack = { selected = null },
            modifier = modifier,
        )
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
    catalog: PluginCatalogService?,
    manager: PluginManagerService?,
    managed: ManagedPlugin?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    // Values live in the plugin, so they are read on entry and after every write instead of being
    // copied into the declaration.
    var revision by remember { mutableStateOf(0) }
    val values = remember(manifest.id, revision) {
        manifest.settings.associate { it.key to (catalog?.readSetting(manifest.id, it.key) ?: it.value) }
    }
    val write: (String, String) -> Unit = { key, value ->
        catalog?.writeSetting(manifest.id, key, value)
        revision += 1
    }
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

        if (managed != null) {
            item {
                EvSettingsSection("管理")
                EvSettingsGroup {
                    EvSettingsRow(
                        title = "启用",
                        glyph = EvGlyphKind.Gear,
                        summary = managed.error ?: if (managed.enabled) "运行中" else "已禁用",
                        trailing = EvSettingsTrailing.Switch,
                        checked = managed.enabled,
                        onCheckedChange = { on -> manager?.setEnabled(manifest.id, on) },
                    )
                    EvSettingsDivider()
                    EvSettingsRow(
                        title = "存储",
                        glyph = EvGlyphKind.Doc,
                        summary = formatBytes(managed.storageBytes) + " · 点此清除",
                        onClick = { manager?.clearStorage(manifest.id) },
                    )
                }
            }

            val requested = managed.requestedPermissions
            if (requested.isNotEmpty()) {
                item {
                    EvSettingsSection("权限")
                    EvSettingsGroup {
                        requested.forEach { permission ->
                            val granted = permission in managed.grantedPermissions
                            EvSettingsRow(
                                title = permissionLabel(permission),
                                glyph = EvGlyphKind.Lock,
                                summary = if (granted) "已授予" else "未授予",
                                trailing = EvSettingsTrailing.Switch,
                                checked = granted,
                                onCheckedChange = { on ->
                                    if (on) manager?.grantPermission(manifest.id, permission)
                                    else manager?.revokePermission(manifest.id, permission)
                                },
                            )
                        }
                    }
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
                    manifest.settings.forEach { setting ->
                        SettingRow(
                            setting = setting,
                            value = values[setting.key] ?: setting.value,
                            onWrite = { written -> write(setting.key, written) },
                        )
                    }
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
private fun SettingRow(
    setting: PluginSetting,
    value: String,
    onWrite: (String) -> Unit,
) {
    val tokens = EvThemeTokens.colors
    var editing by remember { mutableStateOf(false) }

    when (setting.kind) {
        PluginSetting.Kind.BOOLEAN -> EvSettingsRow(
            title = setting.title,
            glyph = EvGlyphKind.Info,
            summary = setting.description,
            trailing = EvSettingsTrailing.Switch,
            checked = value.equals("true", ignoreCase = true),
            onCheckedChange = { onWrite(it.toString()) },
        )

        PluginSetting.Kind.ACTION -> EvSettingsRow(
            title = setting.title,
            glyph = EvGlyphKind.Sparkle,
            summary = setting.description,
            onClick = { onWrite("") },
        )

        // TEXT and SELECT share one editor: a value you can see, change and save.
        else -> {
            EvSettingsRow(
                title = setting.title,
                glyph = EvGlyphKind.Create,
                summary = if (value.isBlank()) setting.description else "$value${if (setting.description.isBlank()) "" else " · ${setting.description}"}",
                onClick = { editing = true },
            )
            if (editing) {
                ValueDialog(
                    title = setting.title,
                    options = if (setting.kind == PluginSetting.Kind.SELECT) setting.options else emptyList(),
                    initial = value,
                    onDismiss = { editing = false },
                    onConfirm = { written ->
                        editing = false
                        onWrite(written)
                    },
                )
            }
        }
    }
}

/** One editor for both text and a fixed option list; both end in "save this value". */
@Composable
private fun ValueDialog(
    title: String,
    options: List<String>,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val tokens = EvThemeTokens.colors
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(EvThemeTokens.radii.large))
                .background(tokens.surface2)
                .padding(20.dp),
        ) {
            Text(text = title, style = EvThemeTokens.type.title.style(tokens.textPrimary))
            Spacer(Modifier.height(12.dp))
            if (options.isEmpty()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(tokens.surface1)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                            .clickable { text = option }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = EvThemeTokens.type.body.style(
                                if (option == text) tokens.textPrimary else tokens.textSecondary,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    style = EvThemeTokens.type.label.style(tokens.textSecondary),
                    modifier = Modifier
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "保存",
                    style = EvThemeTokens.type.label.style(tokens.onAccent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                        .background(tokens.accent)
                        .clickable { onConfirm(text) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Byte count in the units a person reads, not the ones a formatter defaults to. */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> "${bytes / (1024L * 1024L)} MB"
}

/** Host permission names are uppercase constants; show them as words. */
private fun permissionLabel(permission: String): String = when (permission.uppercase()) {
    "NETWORK" -> "网络访问"
    "STORAGE" -> "存储读写"
    "DEVICE" -> "设备控制"
    "UI" -> "界面绘制"
    else -> permission.lowercase().replaceFirstChar { it.uppercase() }
}
