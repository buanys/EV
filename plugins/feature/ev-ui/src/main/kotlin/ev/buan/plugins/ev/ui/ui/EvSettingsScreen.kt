package ev.buan.plugins.ev.ui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvAccentColor
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvPaletteStyle
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import ev.buan.ui.EvThemeMode
import ev.buan.ui.MAX_INTERFACE_SCALE
import ev.buan.ui.MIN_INTERFACE_SCALE
import ev.buan.ui.normalizeInterfaceScale
import org.cordis.api.AppearanceService
import org.cordis.api.HudPulse
import org.cordis.api.HudService
import org.cordis.api.HudState
import org.cordis.api.PLUGIN_API_VERSION
import kotlin.math.roundToInt

/**
 * Ev-style settings, owned by this plugin rather than by a second settings plugin.
 *
 * Appearance is the one place that proves the plugin does not own theme state: every row reads
 * [AppearanceService.current] during composition and writes through [AppearanceService.update]. The
 * host applies the result above every composition, so changing the theme here restyles the chat
 * underneath immediately - and the values survive a restart because the host persists them.
 *
 * Capability rows open this plugin's own second-level pages ([onOpenModels], [onOpenTools],
 * [onOpenMemory]); only "插件管理" and "宿主设置" leave the
 * plugin, because those two screens belong to the host.
 */
@Composable
fun EvSettingsScreen(
    appearance: AppearanceService,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenPluginCatalog: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenHostSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenAppearance: () -> Unit,
    /** Name of the active theme, resolved by the caller from the host catalog. */
    themeName: String,
    /** 宿主提供的屏幕提示层；null 表示宿主版本旧或无障碍没开——那这行只是不可用，不是坏掉。 */
    hud: HudService? = null,
    /**
     * 预览是否已开。状态由上层（[EvUiPlugin]）持有，不放在这里：
     * 离开设置页就把灯关掉，那就不叫"常驻"了。
     */
    hudPreview: Boolean = false,
    onToggleHudPreview: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val current = appearance.current
    // 调试开关用它把涟漪打在屏幕正中：没有屏幕尺寸就只能猜一个坐标，那在多分辨率上必错。
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    EvPage(
        title = "设置",
        modifier = modifier,
        onBack = onBack,
    ) {
        // Appearance belongs to the host: the theme is applied above every plugin, so the host owns
        // the store and the only editor. This is a pointer, not a second set of controls - having
        // both surfaces write the same state is what made the two pages look duplicated.
        item { EvSettingsSection("外观") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "主题",
                    glyph = EvGlyphKind.Theme,
                    summary = themeName,
                    onClick = onOpenThemes,
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "主题与显示",
                    glyph = EvGlyphKind.Theme,
                    summary = "${current.themeMode.label()} · ${(current.interfaceScale * 100).roundToInt()}%",
                    onClick = onOpenAppearance,
                )
            }
        }

        item { EvSettingsSection("模型与能力") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "模型服务",
                    glyph = EvGlyphKind.Atom,
                    summary = "Provider、Key 与模型",
                    onClick = onOpenModels,
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "工具",
                    glyph = EvGlyphKind.Tool,
                    summary = "agent-tools 发布的工具（只读）",
                    onClick = onOpenTools,
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "记忆",
                    glyph = EvGlyphKind.Memory,
                    summary = "当前会话的 agent 记忆",
                    onClick = onOpenMemory,
                )
            }
        }

        item { EvSettingsSection("环境") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "Linux 环境",
                    glyph = EvGlyphKind.Screen,
                    summary = "Debian 根文件系统：apt、编译器、完整用户态",
                    onClick = onOpenLinux,
                )
            }
        }

        item { EvSettingsSection("插件") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "插件能力",
                    glyph = EvGlyphKind.Info,
                    summary = "每个插件声明自己能做什么",
                    onClick = onOpenPluginCatalog,
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "已安装的插件",
                    glyph = EvGlyphKind.Grid,
                    summary = "启用、禁用与检查插件",
                    onClick = onOpenPlugins,
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "宿主设置",
                    glyph = EvGlyphKind.Gear,
                    summary = "打开 EV 宿主设置页",
                    onClick = onOpenHostSettings,
                )
            }
        }

        item { EvSettingsSection("调试") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "显示屏幕操作提示",
                    glyph = EvGlyphKind.Screen,
                    summary = when {
                        hud == null -> "宿主未提供提示层（需要无障碍已开启）"
                        hudPreview -> "已点亮：胶囊与跑马灯常驻显示"
                        else -> "常驻显示进度胶囊与边缘跑马灯，用来检查亮度"
                    },
                    trailing = EvSettingsTrailing.Switch,
                    checked = hudPreview,
                    onCheckedChange = { on ->
                        // 这一行日志是排障用的：null 说明宿主没发布 HudService（版本旧），
                        // 非 null 还不显示就是窗口那一层的问题，两者查法完全不同。
                        android.util.Log.i("EV", "HUD 调试开关 → $on，hud=${hud != null}")
                        onToggleHudPreview(on)
                        if (on) {
                            // 顺手在屏幕中间打一个涟漪，不然这个开关只验证了胶囊，
                            // 用户还得专门让 agent 点一下才能看到另一半。
                            hud?.pulse(
                                HudPulse(
                                    x = (screenWidthPx / 2).toInt(),
                                    y = (screenHeightPx / 2).toInt(),
                                    startedAt = android.os.SystemClock.elapsedRealtime(),
                                ),
                            )
                        }
                    },
                )
            }
        }

        item { EvSettingsSection("关于") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "EV 界面插件",
                    glyph = EvGlyphKind.Info,
                    summary = "版本 0.1.0 · 契约 API $PLUGIN_API_VERSION",
                    trailing = EvSettingsTrailing.None,
                )
            }
        }
    }
}

/**
 * 外观细节：主题模式、Monet 取色、调色板、强调色、纯黑、界面缩放。
 *
 * 这一页以前是一个独立插件（`plugins/feature/settings`）。搬进 ev-ui 的理由很实际：
 * 它没有自己的权限、没有自己的生命周期，只是"界面的一部分"——多一个插件就多一次扫描、
 * 一个 ClassLoader、一颗 Fiber，而它换来的独立性一点也没用上。
 *
 * 值写在 `summary` 里而不是右侧（[EvSettingsTrailing] 只有 Chevron/Switch/None）：
 * 与"主题与显示"那一行的写法保持一致，点一下就是下一个值。
 */
@Composable
fun EvAppearanceScreen(
    appearance: AppearanceService,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = appearance.current
    fun update(transform: (EvAppearance) -> EvAppearance) = appearance.update(transform)
    EvPage(title = "外观", modifier = modifier, onBack = onBack) {
        item { EvSettingsSection("主题与取色") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "主题模式",
                    glyph = EvGlyphKind.Theme,
                    summary = current.themeMode.label(),
                    onClick = { update { it.copy(themeMode = it.themeMode.next()) } },
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "Monet 取色",
                    glyph = EvGlyphKind.Theme,
                    summary = "从强调色生成整套配色",
                    trailing = EvSettingsTrailing.Switch,
                    checked = current.monetEnabled,
                    onCheckedChange = { enabled -> update { it.copy(monetEnabled = enabled) } },
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "调色板",
                    glyph = EvGlyphKind.Theme,
                    summary = current.paletteStyle.label(),
                    onClick = { update { it.copy(paletteStyle = it.paletteStyle.next()) } },
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "强调色",
                    glyph = EvGlyphKind.Theme,
                    summary = current.accentColor.label(),
                    onClick = { update { it.copy(accentColor = it.accentColor.next()) } },
                )
            }
        }
        item { EvSettingsSection("显示") }
        item {
            EvSettingsGroup {
                EvSettingsRow(
                    title = "纯黑背景",
                    glyph = EvGlyphKind.Theme,
                    summary = "深色模式下背景与卡片全黑",
                    trailing = EvSettingsTrailing.Switch,
                    checked = current.pureBlackEnabled,
                    onCheckedChange = { enabled -> update { it.copy(pureBlackEnabled = enabled) } },
                )
                EvSettingsDivider()
                EvSettingsRow(
                    title = "界面缩放",
                    glyph = EvGlyphKind.Theme,
                    summary = "${(current.interfaceScale * 100).roundToInt()}%",
                    onClick = { update { it.copy(interfaceScale = it.interfaceScale.nextInterfaceScale()) } },
                )
            }
        }
    }
}

private fun EvThemeMode.label(): String = when (this) {
    EvThemeMode.SYSTEM -> "跟随系统"
    EvThemeMode.LIGHT -> "浅色"
    EvThemeMode.DARK -> "深色"
}

private fun EvThemeMode.next(): EvThemeMode {
    val all = EvThemeMode.entries
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

/** "Tonal Spot" rather than the enum spelling: a settings row shows the name a user would say. */
private fun EvPaletteStyle.label(): String = name.split('_').joinToString(" ") { word ->
    word.lowercase().replaceFirstChar { it.uppercase() }
}

private fun EvPaletteStyle.next(): EvPaletteStyle {
    val all = EvPaletteStyle.entries
    return all[(all.indexOf(this) + 1) % all.size]
}

/** 80% -> 90% -> 100% -> 110% -> 80%; the bounds are the design system's own constants. */
private fun Float.nextInterfaceScale(): Float {
    val percent = (normalizeInterfaceScale(this) * 100).roundToInt()
    val next = if (percent >= (MAX_INTERFACE_SCALE * 100).roundToInt()) {
        (MIN_INTERFACE_SCALE * 100).roundToInt()
    } else {
        percent + 10
    }
    return normalizeInterfaceScale(next / 100f)
}
