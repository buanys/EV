package ev.buan.plugins.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.ui.EvAccentColor
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvGroupTitle
import ev.buan.ui.EvPaletteStyle
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvThemeMode
import ev.buan.ui.MAX_INTERFACE_SCALE
import ev.buan.ui.MIN_INTERFACE_SCALE
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.AgentUi
import org.cordis.api.AppearanceService
import org.cordis.dependencies
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.round

/**
 * Appearance settings, shipped as a jar plugin.
 *
 * It renders only rows: the palette, the typography and the card surfaces all come from the host.
 * Changing something goes through [AppearanceService], so the host recomposes the entire tree -
 * including this page.
 */
class SettingsPlugin : Plugin<Unit>, AgentSurface, AgentUi {
    override val name = "settings-plugin"
    override val title = "外观设置"
    override val body = "主题、取色、调色板、强调色、纯黑与界面缩放。"

    override val inject: Dependencies = dependencies(AppearanceService.Key)

    private var service: AppearanceService? = null

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        service = ctx.require(AppearanceService.Key)
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val appearance = service?.current ?: EvAppearance()
        EvGroupTitle("外观")
        EvPreferenceGroup(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "主题模式",
                value = appearance.themeMode.label(),
                onClick = { update { it.copy(themeMode = it.themeMode.next()) } },
            )
            EvPreferenceDivider()
            SettingRow(
                title = "Monet 取色",
                summary = "从强调色生成整套配色",
                value = if (appearance.monetEnabled) "开" else "关",
                onClick = { update { it.copy(monetEnabled = !it.monetEnabled) } },
            )
            EvPreferenceDivider()
            SettingRow(
                title = "调色板",
                value = appearance.paletteStyle.label(),
                onClick = { update { it.copy(paletteStyle = it.paletteStyle.next()) } },
            )
            EvPreferenceDivider()
            SettingRow(
                title = "强调色",
                value = appearance.accentColor.label(),
                onClick = { update { it.copy(accentColor = it.accentColor.next()) } },
            )
            EvPreferenceDivider()
            SettingRow(
                title = "纯黑背景",
                summary = "深色模式下背景与卡片全黑",
                value = if (appearance.pureBlackEnabled) "开" else "关",
                onClick = { update { it.copy(pureBlackEnabled = !it.pureBlackEnabled) } },
            )
            EvPreferenceDivider()
            SettingRow(
                title = "界面缩放",
                value = "${(appearance.interfaceScale * 100).toInt()}%",
                onClick = {
                    val next = round((appearance.interfaceScale + 0.1f) * 10) / 10f
                    update {
                        it.copy(
                            interfaceScale = if (next > MAX_INTERFACE_SCALE) MIN_INTERFACE_SCALE else next,
                        )
                    }
                },
            )
        }
    }

    private fun update(transform: (EvAppearance) -> EvAppearance) {
        service?.update(transform)
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    EvPreferenceRow(
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
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

private fun EvPaletteStyle.label(): String = when (this) {
    EvPaletteStyle.TONAL_SPOT -> "Tonal Spot"
    EvPaletteStyle.NEUTRAL -> "Neutral"
    EvPaletteStyle.VIBRANT -> "Vibrant"
    EvPaletteStyle.EXPRESSIVE -> "Expressive"
    EvPaletteStyle.RAINBOW -> "Rainbow"
    EvPaletteStyle.FRUIT_SALAD -> "Fruit Salad"
    EvPaletteStyle.MONOCHROME -> "Monochrome"
    EvPaletteStyle.FIDELITY -> "Fidelity"
    EvPaletteStyle.CONTENT -> "Content"
}

private fun EvPaletteStyle.next(): EvPaletteStyle {
    val all = EvPaletteStyle.entries
    return all[(all.indexOf(this) + 1) % all.size]
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
