package ev.buan

import androidx.activity.enableEdgeToEdge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.ui.EvPage
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvTheme
import ev.buan.ui.EvThemeTokens
import ev.buan.ui.StatusError
import ev.buan.ui.StatusWarning
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PluginsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as EvApplication).runtime
        setContent {
            EvTheme(
                appearance = runtime.appearance.value,
                spec = runtime.themeSpec(runtime.appearance.value.themeId),
            ) { HostRoot(this, runtime) }
        }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, PluginsActivity::class.java)
    }
}

private sealed interface HostUiState {
    data object Loading : HostUiState
    data class Ready(val state: HostState) : HostUiState
    data class Failed(val message: String) : HostUiState
}

@Composable
private fun HostRoot(activity: ComponentActivity, runtime: HostComposition) {
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(activity)) }
    var state by remember { mutableStateOf<HostUiState>(HostUiState.Loading) }
    val scope = rememberCoroutineScope()
    // These live in the composable scope, not inside `EvPage { }`: that content lambda is a
    // LazyListScope, where remember and the activity-result launcher cannot be called.
    var query by remember { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = importContainer(activity, uri)
                state = HostUiState.Ready(runtime.start())
                Toast.makeText(
                    activity,
                    if (ok) "已导入，重启 EV 后生效" else "导入失败：无法读取该文件",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasAllFilesAccess()
        notificationsGranted = hasNotificationPermission(activity)
    }

    if (!granted) {
        PermissionPage(onGrant = { activity.openAllFilesSettings() })
        return
    }

    LaunchedEffect(Unit) {
        state = runCatching { runtime.start() }.fold(
            onSuccess = { HostUiState.Ready(it) },
            onFailure = { HostUiState.Failed(it.stackTraceToString()) },
        )
    }

    EvPage(title = "EV") {
        when (val current = state) {
            HostUiState.Loading -> item {
                Text(
                    "正在扫描插件…",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(24.dp),
                )
            }

            is HostUiState.Failed -> item {
                Text(
                    current.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusError,
                    modifier = Modifier.padding(24.dp),
                )
            }

            is HostUiState.Ready -> {
                val ready = current.state
                val safeMode = ready.safeMode
                if (safeMode != null) {
                    item(key = "safe-mode") {
                        SafeModeSection(
                            notice = safeMode,
                            onExit = {
                                scope.launch {
                                    state = HostUiState.Ready(runtime.exitSafeMode())
                                }
                            },
                        )
                    }
                }
                // The foreground service is notification-visible; without the Android 13+ runtime
                // grant the OS hides that notification. Ask here, where host-level grants live.
                if (!notificationsGranted) {
                    item(key = "notification-permission") {
                        EvPreferenceGroup {
                            EvPreferenceRow(
                                title = "通知权限未授予",
                                summary = "插件后台运行依赖常驻通知；未授权时系统会隐藏通知。点击请求授权。",
                                onClick = { activity.requestNotificationPermission() },
                            )
                        }
                    }
                }
                item(key = "search") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        CardAction(label = "导入") {
                            importer.launch(arrayOf("*/*"))
                        }
                    }
                }
                if (ready.cards.isEmpty() && ready.errors.isEmpty()) {
                    item { EmptyHint() }
                }
                ready.cards.filter { it.matches(query) }.forEach { card ->
                    item(key = "header:${card.id}") {
                        Spacer(Modifier.height(8.dp))
                    }
                    item(key = card.id) {
                        PluginSection(
                            card = card,
                            confirming = pendingUninstall == card.id,
                            onToggle = { id, enabled ->
                                scope.launch { state = HostUiState.Ready(runtime.setEnabled(id, enabled)) }
                            },
                            onPermission = { id, permission, granted ->
                                scope.launch {
                                    state = HostUiState.Ready(runtime.setPermission(id, permission, granted))
                                }
                            },
                            onRollback = { id ->
                                scope.launch { state = HostUiState.Ready(runtime.rollback(id)) }
                            },
                            onResetFailures = { id ->
                                scope.launch { state = HostUiState.Ready(runtime.resetFailureScore(id)) }
                            },
                            onClearStorage = { id ->
                                scope.launch { state = HostUiState.Ready(runtime.clearStorage(id)) }
                            },
                            onOpenPage = { id ->
                                activity.startActivity(PluginPageActivity.intent(activity, id))
                            },
                            onUninstall = { id -> pendingUninstall = id },
                            onConfirmUninstall = {
                                val id = pendingUninstall
                                pendingUninstall = null
                                if (id != null) {
                                    scope.launch {
                                        state = HostUiState.Ready(runtime.uninstall(id))
                                    }
                                }
                            },
                            onCancelUninstall = { pendingUninstall = null },
                            onTrustUpdates = { id, trust ->
                                scope.launch {
                                    state = HostUiState.Ready(runtime.setTrustUpdates(id, trust))
                                }
                            },
                            onPinShortcut = { id, shortcut -> ShortcutPin.pin(activity, id, shortcut) },
                        )
                    }
                }
                if (ready.pending.isNotEmpty()) {
                    item {
                        Text(
                            "待确认的未签名插件",
                            style = MiuixTheme.textStyles.subtitle,
                            color = StatusWarning,
                            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    item {
                        EvPreferenceGroup {
                            ready.pending.forEachIndexed { index, pendingItem ->
                                if (index > 0) EvPreferenceDivider()
                                EvPreferenceRow(
                                    title = pendingItem.id,
                                    summary = "v${pendingItem.version} · 未签名 · 哈希 ${pendingItem.sha256.take(8)}…" +
                                    (pendingItem.reason?.let { "\n$it" } ?: ""),
                                    onClick = {
                                        state = HostUiState.Ready(
                                            runtime.approveUnsigned(pendingItem.id, pendingItem.sha256),
                                        )
                                    },
                                    endActions = {
                                        Text(
                                            "信任",
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            "信任只对当前这份字节有效；文件改动后会再次要求确认。重启 EV 才会加载。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                }
                if (ready.errors.isNotEmpty()) {
                    item {
                        Text(
                            "同步问题",
                            style = MiuixTheme.textStyles.subtitle,
                            color = StatusWarning,
                            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    item {
                        EvPreferenceGroup {
                            ready.errors.forEachIndexed { index, error ->
                                if (index > 0) EvPreferenceDivider(hasLeading = false)
                                EvPreferenceRow(title = error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val REQUEST_POST_NOTIFICATIONS = 0xE5

private fun ComponentActivity.requestNotificationPermission() {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Case-insensitive match over the two fields the card actually shows. */
private fun PluginCard.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return id.contains(needle, ignoreCase = true) || title.contains(needle, ignoreCase = true)
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(EvThemeTokens.colors.surface1)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = "搜索插件",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = colors.onBackground),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Copies a picked container into `EV/mods`.
 *
 * The display name is kept as-is: the sidecar json is looked up by the jar's own file name, so
 * renaming here would detach a plugin from its metadata. A restart is required afterwards because
 * sync runs at launch.
 */
private suspend fun importContainer(activity: ComponentActivity, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = displayNameOf(activity, uri)
                ?: "imported-${System.currentTimeMillis()}.jar"
            val dir = File(Environment.getExternalStorageDirectory(), "EV/mods")
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
            val input = activity.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { source ->
                File(dir, name).outputStream().use { target -> source.copyTo(target) }
            }
            true
        }.getOrDefault(false)
    }

private fun displayNameOf(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

internal fun externalModsPath(): String = "${Environment.getExternalStorageDirectory().path}/EV/mods"
