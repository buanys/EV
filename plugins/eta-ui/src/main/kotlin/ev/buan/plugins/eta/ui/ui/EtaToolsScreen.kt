package ev.buan.plugins.eta.ui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsDivider
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvSettingsTrailing
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The tools a plugin publishes, read straight from the host's `ServiceDirectory`.
 *
 * Read-only on purpose: the panel answers "what can the model ask for?". Enabling, disabling and
 * configuring stay in the plugin manager, and invoking a tool is the agent brain's job during a
 * conversation - nothing here calls [ev.buan.plugins.agent.api.Tool.invoke], so opening the panel
 * cannot touch a plugin's storage.
 *
 * A missing registry is a normal state, not an error: the tools plugin may not be installed, may be
 * disabled, or may be enabled but publishing nothing. The two empty states stay apart because they
 * send the user to different places.
 */
@Composable
fun EtaToolsScreen(
    registry: () -> ToolRegistry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bumped by the refresh action; specs() is an in-memory list, so a synchronous re-read keeps the
    // panel honest after the tools plugin was enabled on another screen without restarting the app.
    var refreshToken by remember { mutableStateOf(0) }
    val state = remember(refreshToken) { readTools(registry) }

    EvPage(
        title = "工具",
        modifier = modifier,
        onBack = onBack,
        actions = {
            // EvPage owns the clickable back arrow (ui-kit P3 fix); the page only adds its refresh
            // action here.
            Text(
                text = "刷新",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClickLabel = "刷新工具列表") { refreshToken++ }
                    .padding(horizontal = 12.dp),
            )
        },
    ) {
        when (state) {
            ToolsState.Missing -> item {
                ToolsHint(
                    title = "agent-tools 未启用",
                    detail = "没有插件发布工具注册表。在「插件中心」启用 agent-tools 后回来刷新。",
                )
            }

            ToolsState.Empty -> item {
                ToolsHint(
                    title = "未注册工具",
                    detail = "agent-tools 已启用，但当前没有向注册表发布任何工具。",
                )
            }

            is ToolsState.Failed -> item {
                ToolsHint(title = "读取工具列表失败", detail = state.message)
            }

            is ToolsState.Loaded -> {
                item { EvSettingsSection("已注册 ${state.specs.size} 个工具") }
                item {
                    EvSettingsGroup {
                        state.specs.forEachIndexed { index, spec ->
                            if (index > 0) EvSettingsDivider()
                            ToolEntry(spec)
                        }
                    }
                }
                item { ToolsHint(title = null, detail = READ_ONLY_NOTE) }
            }
        }
    }
}

/** What a read of [ToolRegistry.specs] produced, including the two states that both look "empty". */
internal sealed interface ToolsState {
    /** No plugin publishes a [ToolRegistry]: agent-tools is missing or disabled. */
    data object Missing : ToolsState

    /** A registry is published but offers nothing. */
    data object Empty : ToolsState

    data class Loaded(val specs: List<ToolSpec>) : ToolsState

    /** The registry exists but reading it threw; the message is shown instead of crashing the page. */
    data class Failed(val message: String) : ToolsState
}

internal fun readTools(registry: () -> ToolRegistry?): ToolsState {
    val found = try {
        registry()
    } catch (error: Exception) {
        // A failing lookup is reported, not folded into "not installed": those are different fixes.
        return ToolsState.Failed(error.message ?: error::class.simpleName ?: "未知错误")
    } ?: return ToolsState.Missing
    val specs = try {
        found.specs()
    } catch (error: Exception) {
        return ToolsState.Failed(error.message ?: error::class.simpleName ?: "未知错误")
    }
    return if (specs.isEmpty()) ToolsState.Empty else ToolsState.Loaded(specs)
}

/** One tool: the row, plus its schema folded away until the user asks for it. */
@Composable
private fun ToolEntry(spec: ToolSpec) {
    val preview = parameterPreview(spec.parametersJson)
    var expanded by remember(spec) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        EvSettingsRow(
            title = spec.name,
            // A dangerous tool wears the lock and says so in its summary: the management page must
            // show the same risk label the brain enforces at call time.
            glyph = if (spec.dangerous) EvGlyphKind.Lock else EvGlyphKind.Gear,
            summary = riskSummary(spec),
            trailing = if (preview == null) EvSettingsTrailing.None else EvSettingsTrailing.Chevron,
            onClick = if (preview == null) null else ({ expanded = !expanded }),
        )
        if (preview != null) {
            if (expanded) {
                ParameterBlock(spec.parametersJson)
            } else {
                Text(
                    text = preview,
                    style = monoStyle(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    // Two lines is enough to recognize the shape and never pushes the next tool off
                    // the screen; the full schema is one tap away.
                    maxLines = 2,
                    modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ParameterBlock(json: String) {
    Text(
        text = parameterDetails(json),
        style = monoStyle(),
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ToolsHint(title: String?, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun monoStyle(): TextStyle = MiuixTheme.textStyles.footnote1.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
)

internal const val READ_ONLY_NOTE: String =
    "只读面板：这里只列出工具声明，不执行工具；调用发生在对话中，由 agent 运行时完成。"

/** A description is the row's summary; a very long one is clipped rather than laid out. */
internal fun descriptionSummary(description: String, maxChars: Int = 120): String? {
    val trimmed = description.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars - 1).trimEnd() + "…"
}

/** The row's summary with the risk label first for tools the brain routes through the human. */
private fun riskSummary(spec: ToolSpec): String? {
    val base = descriptionSummary(spec.description)
    if (!spec.dangerous) return base
    return listOfNotNull("高危 · 执行前需人工审批", base).joinToString(" · ")
}

/**
 * The parameters on one or two lines: minified JSON, so the collapsed row stays a row.
 *
 * Null when there is nothing to show (blank, or the empty-object schema) - which is also how the row
 * decides whether it is expandable at all.
 */
internal fun parameterPreview(parametersJson: String): String? {
    val trimmed = parametersJson.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return null
    val minified = runCatching { JSONObject(trimmed).toString() }
        .getOrElse { trimmed.replace(WHITESPACE, " ") }
    return minified.takeIf { it.isNotBlank() && it != "{}" }
}

/**
 * The full schema, pretty-printed for the expanded state.
 *
 * Anything unparseable is shown verbatim: a tool that publishes a broken schema is exactly the kind
 * of thing this panel exists to reveal. [maxLines] keeps a pathological schema from becoming the
 * whole page.
 */
internal fun parameterDetails(parametersJson: String, maxLines: Int = 60): String {
    val pretty = runCatching { JSONObject(parametersJson.trim()).toString(2) }
        .getOrNull()
        ?: parametersJson.trim()
    val lines = pretty.lines()
    if (lines.size <= maxLines) return pretty
    return lines.take(maxLines).joinToString("\n") + "\n…"
}

private val WHITESPACE = Regex("\\s+")
