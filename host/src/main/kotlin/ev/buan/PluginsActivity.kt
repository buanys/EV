package ev.buan

import androidx.activity.enableEdgeToEdge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.hostcore.storage.PluginStorageFiles
import ev.buan.ui.EvPage
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvTheme
import ev.buan.ui.evSystemBarsPadding
import ev.buan.ui.StatusError
import ev.buan.ui.StatusWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import org.cordis.api.AgentUi
import org.cordis.api.HostPermission
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PluginsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as EvApplication).runtime
        setContent {
            EvTheme(appearance = runtime.appearance.value) { HostRoot(this, runtime) }
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
private fun HostRoot(activity: ComponentActivity, runtime: HostRuntime) {
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(activity)) }
    var state by remember { mutableStateOf<HostUiState>(HostUiState.Loading) }
    val scope = rememberCoroutineScope()

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
                if (ready.cards.isEmpty() && ready.errors.isEmpty()) {
                    item { EmptyHint() }
                }
                ready.cards.forEach { card ->
                    item(key = "header:${card.id}") {
                        Spacer(Modifier.height(8.dp))
                    }
                    item(key = card.id) {
                        PluginSection(
                            card = card,
                            onToggle = { id, enabled ->
                                scope.launch { state = HostUiState.Ready(runtime.setEnabled(id, enabled)) }
                            },
                            onPermission = { id, permission, granted ->
                                scope.launch {
                                    state = HostUiState.Ready(runtime.setPermission(id, permission, granted))
                                }
                            },
                            onClearStorage = { id ->
                                scope.launch { state = HostUiState.Ready(runtime.clearStorage(id)) }
                            },
                            onOpenPage = { id ->
                                activity.startActivity(PluginPageActivity.intent(activity, id))
                            },
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
                                    summary = "v${pendingItem.version} · 未签名 · 哈希 ${pendingItem.sha256.take(8)}…",
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

@Composable
private fun PluginSection(
    card: PluginCard,
    onToggle: (String, Boolean) -> Unit,
    onPermission: (String, HostPermission, Boolean) -> Unit,
    onClearStorage: (String) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    EvPreferenceGroup {
        EvPreferenceRow(
            title = card.id,
            summary = buildString {
                append('v').append(card.version).append("  ·  api ").append(card.apiVersion)
                if (!card.signed) append("  ·  未签名")
            },
            endActions = {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggle(card.id, !card.enabled) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (card.enabled) "已启用" else "已禁用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (card.enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            },
        )
        // Declared capabilities are always listed, even while the plugin is disabled or failed:
        // this is the only place the user can grant them, and an ungranted declaration is exactly
        // why a plugin may be sitting in the failure state.
        val requested = HostPermission.entries.filter { it in card.requestedPermissions }
        if (requested.isNotEmpty()) {
            EvPreferenceDivider()
            EvPreferenceRow(
                title = "权限",
                summary = "${requested.size} 项 · 插件声明的宿主能力",
                enabled = false,
            )
            requested.forEach { permission ->
                val granted = permission in card.grantedPermissions
                EvPreferenceDivider()
                EvPreferenceRow(
                    title = permissionLabel(permission),
                    summary = if (granted) "已授予，点击撤销" else "未授予，点击授予",
                    onClick = { onPermission(card.id, permission, !granted) },
                    endActions = {
                        Text(
                            if (granted) "已授予" else "未授予",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (granted) MiuixTheme.colorScheme.primary else StatusWarning,
                        )
                    },
                )
            }
        }
        // Storage stays visible for disabled and failed plugins too: usage is the host's accounting
        // of this plugin's area, and clearing it is always available.
        EvPreferenceDivider()
        EvPreferenceRow(
            title = "插件存储",
            summary = "${formatBytes(card.storageBytes)} / ${formatBytes(PluginStorageFiles.DEFAULT_QUOTA_BYTES)}",
            endActions = {
                Text(
                    "清理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (card.storageBytes > 0) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            },
            onClick = { onClearStorage(card.id) },
        )
        if (card.error != null || !card.enabled) {
            EvPreferenceDivider()
            EvPreferenceRow(
                title = card.error ?: "已禁用，不会加载",
                enabled = false,
            )
        } else if (card.ui != null) {
            EvPreferenceDivider()
            EvPreferenceRow(
                title = "打开页面",
                summary = "以独立 Activity 全屏运行",
                onClick = { onOpenPage(card.id) },
            )
            EvPreferenceDivider()
            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                card.ui.Content(Modifier.fillMaxWidth())
            }
        } else if (card.body.isNotEmpty()) {
            EvPreferenceDivider()
            EvPreferenceRow(title = card.title, summary = card.body)
        }
    }
}

/**
 * Top-of-page notice while the process runs in safe mode.
 *
 * Safe mode is a next-launch recovery path, not in-process supervision: Compose has no error
 * boundary, so a plugin composable that throws kills the process before the host could catch it.
 * The copy therefore promises "nothing loaded this launch", never "recovered".
 */
@Composable
private fun SafeModeSection(notice: SafeModeNotice, onExit: () -> Unit) {
    Column {
        Text(
            "安全模式",
            style = MiuixTheme.textStyles.subtitle,
            color = StatusWarning,
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
        )
        EvPreferenceGroup {
            EvPreferenceRow(
                title = "上次运行异常退出，本次启动没有加载任何插件",
                summary = "崩溃时间：${notice.recordedAt?.let(::formatCrashTime) ?: "未知"}" +
                    "\n疑似插件：${notice.suspectedPluginId ?: "未知"}（仅提示，宿主未隔离插件）",
                enabled = false,
            )
            EvPreferenceDivider()
            EvPreferenceRow(
                title = "退出安全模式",
                summary = "重新加载已启用的插件；若再次崩溃，下次启动仍会进入安全模式。",
                onClick = onExit,
            )
        }
        Text(
            "Compose 没有错误边界：插件 UI 抛异常会直接结束进程，宿主做不到进程内恢复；" +
                "安全模式只保证下一次启动能绕开插件。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
    }
}

/** Crash time in the device's current zone; the marker stores an instant, so the zone can change. */
private fun formatCrashTime(instant: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(instant)

/** Management-page wording for each contract constant; the wire name alone means nothing to a user. */
private fun permissionLabel(permission: HostPermission): String = when (permission) {
    HostPermission.NETWORK -> "网络访问"
    HostPermission.PLUGIN_STORAGE -> "插件存储"
    HostPermission.BACKGROUND -> "后台运行"
    HostPermission.NOTIFICATIONS -> "通知"
    HostPermission.CLIPBOARD -> "剪贴板"
    HostPermission.INSTALL_PACKAGES -> "安装应用"
    HostPermission.ROOT_SHELL -> "Root Shell"
}

/** Compact, locale-stable size for the storage row. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun EmptyHint() {
    EvPreferenceGroup {
        EvPreferenceRow(
            title = "没有插件",
            summary = "把 .apk / .jar / .dex 放进 ${externalModsPath()} 再重开 EV。",
        )
    }
}

@Composable
private fun PermissionPage(onGrant: () -> Unit) {
    EvPage(title = "EV") {
        item {
            EvPreferenceGroup {
                EvPreferenceRow(
                    title = "需要「所有文件访问权限」",
                    summary = "用来扫描 ${externalModsPath()} 下的插件。授权后每次启动会自动同步到私有目录。",
                )
                EvPreferenceDivider(hasLeading = false)
                EvPreferenceRow(title = "去授权", onClick = onGrant)
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

private const val REQUEST_POST_NOTIFICATIONS = 0xE5

private fun ComponentActivity.requestNotificationPermission() {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun externalModsPath(): String = "${Environment.getExternalStorageDirectory().path}/EV/mods"
