package ev.buan

import androidx.activity.enableEdgeToEdge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvAccentColor
import ev.buan.ui.EvBackButton
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPaletteStyle
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.EvTheme
import ev.buan.ui.EvThemeMode
import ev.buan.ui.MAX_INTERFACE_SCALE
import ev.buan.ui.MIN_INTERFACE_SCALE
import ev.buan.ui.evSystemBarsPadding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Host settings, laid out the way Eta lays out its own: a gray section label, one rounded card, a
 * coloured glyph per row, switch or chevron on the right.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as EvApplication).runtime
        setContent {
            EvTheme(appearance = runtime.appearance.value) {
                Column(
                    Modifier.fillMaxSize()
                        .background(MiuixTheme.colorScheme.background)
                        .evSystemBarsPadding(),
                ) {
                    SettingsContent(runtime, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

@Composable
private fun SettingsContent(runtime: HostRuntime, onBack: () -> Unit) {
    val appearance = runtime.appearance.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvBackButton(onClick = onBack)
            Text("设置", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackground)
        }

        EvSettingsSection("外观")
        EvSettingsGroup {
            EvSettingsRow(
                title = "主题模式",
                glyph = EvGlyphKind.Sparkle,
                summary = appearance.themeMode.label(),
                onClick = { runtime.updateAppearance { copy(themeMode = themeMode.next()) } },
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = "纯黑背景",
                glyph = EvGlyphKind.Grid,
                summary = "深色模式下背景与卡片全黑",
                trailing = EvSettingsTrailing.Switch,
                checked = appearance.pureBlackEnabled,
                onCheckedChange = { value -> runtime.updateAppearance { copy(pureBlackEnabled = value) } },
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = "界面缩放",
                glyph = EvGlyphKind.Doc,
                summary = "${(appearance.interfaceScale * 100).toInt()}%",
                onClick = {
                    runtime.updateAppearance {
                        val next = ((interfaceScale + 0.1f) * 10).toInt() / 10f
                        copy(interfaceScale = if (next > MAX_INTERFACE_SCALE) MIN_INTERFACE_SCALE else next)
                    }
                },
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = "强调色",
                glyph = EvGlyphKind.Bell,
                summary = appearance.accentColor.label(),
                onClick = { runtime.updateAppearance { copy(accentColor = accentColor.next()) } },
            )
        }

        EvSettingsSection("插件")
        EvSettingsGroup {
            EvSettingsRow(
                title = "插件管理",
                glyph = EvGlyphKind.Grid,
                summary = "启用、禁用、未签名批准",
                onClick = { runtime.openActivity(PluginsActivity::class.java) },
            )
            EvSettingsDivider()
            EvSettingsRow(
                title = "调色板",
                glyph = EvGlyphKind.Gear,
                summary = appearance.paletteStyle.label(),
                onClick = { runtime.updateAppearance { copy(paletteStyle = paletteStyle.next()) } },
            )
        }

        EvSettingsSection("关于")
        EvSettingsGroup {
            EvSettingsRow(
                title = "EV",
                glyph = EvGlyphKind.Lock,
                summary = "self-hosted plugin host · API 1",
                trailing = EvSettingsTrailing.None,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun EvThemeMode.label(): String = when (this) {
    EvThemeMode.SYSTEM -> "跟随系统"
    EvThemeMode.LIGHT -> "浅色"
    EvThemeMode.DARK -> "深色"
}

private fun EvThemeMode.next(): EvThemeMode = when (this) {
    EvThemeMode.SYSTEM -> EvThemeMode.LIGHT
    EvThemeMode.LIGHT -> EvThemeMode.DARK
    EvThemeMode.DARK -> EvThemeMode.SYSTEM
}

private fun EvAccentColor.label(): String = when (this) {
    EvAccentColor.SYSTEM -> "系统"
    EvAccentColor.BLUE -> "蓝"
    EvAccentColor.PURPLE -> "紫"
    EvAccentColor.PINK -> "粉"
    EvAccentColor.RED -> "红"
    EvAccentColor.ORANGE -> "橙"
    EvAccentColor.YELLOW -> "黄"
    EvAccentColor.GREEN -> "绿"
    EvAccentColor.TEAL -> "青"
}

private fun EvAccentColor.next(): EvAccentColor {
    val all = EvAccentColor.entries
    return all[(all.indexOf(this) + 1) % all.size]
}

private fun EvPaletteStyle.label(): String = name.replace('_', ' ').lowercase()

private fun EvPaletteStyle.next(): EvPaletteStyle {
    val all = EvPaletteStyle.entries
    return all[(all.indexOf(this) + 1) % all.size]
}
