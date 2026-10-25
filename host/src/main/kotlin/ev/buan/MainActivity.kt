package ev.buan

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.evSystemBarsPadding
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvTheme
import ev.buan.ui.StatusError
import org.cordis.api.AgentPage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The app root. Like Eta, EV opens straight into the home page a plugin provides - today the chat
 * page - instead of a management list. The host screen moves one level down behind the home's top
 * bar, and stays reachable even when no home plugin is loaded.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the window keeps fitting system windows: WindowInsets.systemBars and
        // WindowInsets.ime both read 0, so evSystemBarsPadding() and every plugin's imePadding()
        // become no-ops - content draws under the status bar and the keyboard covers the composer.
        enableEdgeToEdge()
        val runtime = (application as EvApplication).runtime
        setContent {
            EvTheme(appearance = runtime.appearance.value) { HomeShell(this, runtime) }
        }
    }
}

@Composable
private fun HomeShell(activity: ComponentActivity, runtime: HostRuntime) {
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The runtime publishes every state change here, including an exit from safe mode performed on
    // the management page, so this screen always renders the live snapshot instead of a copy taken
    // at first composition. The home plugin therefore appears on return - or as soon as the activity
    // is back on screen - without re-running sync or restarting the process.
    val state by runtime.state

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasAllFilesAccess()
    }
    if (!granted) {
        HostGate(activity)
        return
    }

    // Kick the first (and only) start pass of this screen; [HostRuntime.state] carries the result.
    LaunchedEffect(Unit) {
        runCatching { runtime.start() }.onFailure { failure = it.toString() }
    }

    val openHost: () -> Unit = { activity.startActivity(PluginsActivity.intent(activity)) }
    val home: AgentPage? = state?.let { runtime.homePage() }

    when {
        home != null -> Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).evSystemBarsPadding(),
        ) {
            home.Home(Modifier.fillMaxSize(), onOpenHost = openHost)
        }
        failure != null -> FallbackPage("宿主启动失败", failure!!, openHost)
        state == null -> FallbackPage("正在加载…", "", openHost)
        // Safe mode loads no plugin by decision, so "没有主页插件" would send the user looking for a
        // missing file. Say what actually happened and where the way out is.
        state?.safeMode != null -> SafeModePage(
            notice = state!!.safeMode!!,
            activity = activity,
            onExit = runtime::exitSafeMode,
            onOpenHost = openHost,
        )
        else -> FallbackPage("没有主页插件", "把 ev-ui.jar 放进 EV/mods，或在管理页启用一个界面插件。", openHost)
    }
}

/**
 * Safe mode with the crash on screen.
 *
 * Safe mode already had the report on disk (`files/crash/last.txt`) but never showed it, so a crash
 * was invisible to both the user and whoever has to fix it. This page closes that loop: the stack
 * trace is rendered read-only for on-device reading, and one tap writes a copy into
 * `Download/EV/` so it can be shared without root or adb.
 */
@Composable
private fun SafeModePage(
    notice: SafeModeNotice,
    activity: ComponentActivity,
    onExit: suspend () -> ev.buan.HostState,
    onOpenHost: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var exported by remember { mutableStateOf<String?>(null) }
    val stamp = notice.recordedAt?.toString()?.replace('T', ' ')?.take(19) ?: "时间未知"
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

    Column(
        Modifier
            .fillMaxSize()
            .evSystemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text(
            text = "安全模式",
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Text(
            text = "上次运行在 $stamp 异常退出，本次启动没有加载任何插件。\n" +
                "疑似插件：${notice.suspectedPluginId ?: "无法判定（崩溃发生在宿主或非插件代码）"}",
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        Text(
            text = "崩溃报告",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
        SelectionContainer {
            Text(
                text = notice.report.ifBlank {
                    "（报告缺失：files/crash/last.txt 没有写成，或被系统清理）"
                },
                style = mono,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(12.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            ActionChip(
                label = "导出日志",
                onClick = {
                    exported = exportCrashReport(activity, notice.report)
                },
                modifier = Modifier.weight(1f),
            )
            ActionChip(
                label = "退出安全模式",
                onClick = { scope.launch { runCatching { onExit() } } },
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }

        exported?.let { path ->
            Text(
                text = if (path == null) "导出失败：没有写入权限（需要「所有文件访问」）" else "已导出到 $path",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Text(
            text = "报告同时保留在应用私有目录 files/crash/last.txt，可用 adb pull 取走；" +
                "再次崩溃时会被新报告覆盖。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
        )
        Text(
            text = "打开插件管理",
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenHost() },
        )
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .background(MiuixTheme.colorScheme.surface)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/** Writes the report next to the user's downloads so it can be shared from any file manager. */
private fun exportCrashReport(context: Context, report: String): String? = runCatching {
    if (report.isBlank()) return@runCatching null
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "EV",
    )
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    val file = File(dir, "crash-$stamp.txt")
    file.writeText(report)
    file.absolutePath
}.getOrNull()

/** Shown while loading or when no plugin can be the home page. */
@Composable
private fun FallbackPage(title: String, detail: String, onOpenHost: () -> Unit) {
    Box(Modifier.fillMaxSize().evSystemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 96.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (title.contains("失败")) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                )
            }
            EvPreferenceGroup {
                EvPreferenceRow(title = "插件管理", onClick = onOpenHost)
                EvPreferenceDivider(hasLeading = false)
                EvPreferenceRow(title = "刷新", onClick = onOpenHost)
            }
        }
    }
}

@Composable
private fun HostGate(activity: ComponentActivity) {
    Box(Modifier.fillMaxSize().evSystemBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 96.dp)) {
            Text(
                text = "EV",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            Text(
                text = "需要「所有文件访问权限」才能扫描 EV/mods 里的插件。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            )
            EvPreferenceGroup {
                EvPreferenceRow(title = "去授权", onClick = { activity.openAllFilesSettings() })
            }
        }
    }
}

private fun ComponentActivity.openAllFilesSettings() {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:$packageName"),
    )
    runCatching { startActivity(appIntent) }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

private fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()
