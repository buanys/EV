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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.ui.EvGlyphKind
import ev.buan.ui.EvPage
import ev.buan.ui.EvSettingsGroup
import ev.buan.ui.EvSettingsRow
import ev.buan.ui.EvSettingsSection
import ev.buan.ui.EvThemeTokens
import kotlinx.coroutines.launch
import org.cordis.api.PluginCatalogService
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text

/**
 * The Linux environment, from the outside: is it installed, where is it, how do I install it.
 *
 * This page is a view over two existing surfaces - the plugin's own settings (through the host
 * catalog) and its tools (through the tool bus). It owns no state of its own; that is why it can be
 * deleted without losing anything.
 */
@Composable
fun EvLinuxScreen(
    registry: () -> ToolRegistry?,
    catalog: PluginCatalogService?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = EvThemeTokens.colors
    val scope = rememberCoroutineScope()
    var url by remember {
        mutableStateOf(catalog?.readSetting(LINUX_ID, "url").orEmpty())
    }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    fun refresh() {
        val tool = registry()?.lookup("linux_status")
        if (tool == null) {
            log += "linux 插件没在运行"
            return
        }
        scope.launch {
            val raw = runCatching { tool.invoke("{}") }.getOrNull()
            status = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun install() {
        if (busy) return
        val tool = registry()?.lookup("linux_setup")
        if (tool == null) {
            log += "linux 插件没在运行"
            return
        }
        if (url.isBlank()) {
            log += "先填写根文件系统地址"
            return
        }
        catalog?.writeSetting(LINUX_ID, "url", url)
        busy = true
        log += "开始下载：$url"
        scope.launch {
            val raw = runCatching {
                tool.invoke(JSONObject().put("url", url).toString())
            }.getOrElse { "调用失败：${it.message}" }
            log += raw
            busy = false
            refresh()
        }
    }

    EvPage(title = "Linux 环境", onBack = onBack, modifier = modifier) {
        item {
            EvSettingsSection("状态")
            EvSettingsGroup {
                EvSettingsRow(
                    title = if (status?.optBoolean("installed") == true) "已安装" else "未安装",
                    glyph = EvGlyphKind.Screen,
                    summary = status?.optString("distribution")?.takeIf { it.isNotBlank() }
                        ?: status?.optString("hint")?.takeIf { it.isNotBlank() }
                        ?: "读取中…",
                    onClick = { refresh() },
                )
                status?.optString("path")?.takeIf { it.isNotBlank() }?.let { path ->
                    EvSettingsRow(
                        title = "位置",
                        glyph = EvGlyphKind.Folder,
                        summary = path,
                        onClick = null,
                    )
                }
            }
        }

        item {
            EvSettingsSection("根文件系统地址")
            EvSettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "Debian 13 arm64 的 tar.xz / tar.gz 下载地址。不会替你选镜像。",
                        style = EvThemeTokens.type.caption.style(tokens.textTertiary),
                    )
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        textStyle = EvThemeTokens.type.body.style(tokens.textPrimary),
                        cursorBrush = SolidColor(tokens.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(EvThemeTokens.radii.medium))
                            .background(tokens.surface1)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "安装") { install() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = if (busy) "安装中…" else "下载并安装",
                        style = EvThemeTokens.type.body.style(if (busy) tokens.textTertiary else tokens.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (log.isNotEmpty()) {
            item {
                EvSettingsSection("输出")
                EvSettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        log.takeLast(6).forEach { line ->
                            Text(
                                text = line,
                                style = androidx.compose.ui.text.TextStyle(
                                    color = tokens.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val LINUX_ID = "ev.buan.plugins.linux"
