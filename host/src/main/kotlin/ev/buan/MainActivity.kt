package ev.buan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        state?.safeMode != null -> FallbackPage(
            "安全模式",
            "上次运行异常退出，本次启动没有加载任何插件。在插件管理页退出安全模式后，主页插件会直接出现。",
            openHost,
        )
        else -> FallbackPage("没有主页插件", "把 eta-ui.jar 放进 EV/mods，或在管理页启用一个界面插件。", openHost)
    }
}

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
