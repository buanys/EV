package ev.buan

import androidx.activity.enableEdgeToEdge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ev.buan.hostcore.storage.PluginStorageFiles
import ev.buan.ui.EvPage
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.EvTheme
import ev.buan.ui.EvThemeTokens
import ev.buan.ui.evSystemBarsPadding
import ev.buan.ui.StatusError
import ev.buan.ui.StatusWarning
import java.time.Instant
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.AgentUi
import org.cordis.api.HostPermission
import top.yukonga.miuix.kmp.basic.Switch
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

/**
 * One plugin as a KernelSU-style module card: name and version on top, the switch on the right,
 * one action row underneath ("打开" / "卸载"), and the permission + storage detail folded away
 * until the card is tapped.
 *
 * Uninstall is a two-step confirm rendered inside the card rather than a dialog: the card is
 * already the thing being acted on, so asking next to it keeps the target and the question in one
 * place and avoids pulling a dialog API into a page that has no other modal.
 */
@Composable
private fun PluginSection(
    card: PluginCard,
    confirming: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onPermission: (String, HostPermission, Boolean) -> Unit,
    onClearStorage: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onConfirmUninstall: () -> Unit,
    onCancelUninstall: () -> Unit,
) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            // surface0 is the page background; the card needs the next layer up. Miuix surface is
            // forced to pure black in this theme, which made every card invisible.
            .background(EvThemeTokens.colors.surface1)
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.title.ifBlank { card.id },
                    style = MiuixTheme.textStyles.subtitle,
                    color = colors.onBackground,
                )
                Text(
                    text = buildString {
                        append("版本 ").append(card.version)
                        append("  ·  api ").append(card.apiVersion)
                        if (!card.signed) append("  ·  未签名")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (card.body.isNotBlank()) {
                    Text(
                        text = card.body,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Box(Modifier.padding(start = 12.dp)) {
                Switch(checked = card.enabled, onCheckedChange = { onToggle(card.id, it) })
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A plugin without a UI page still offers "打开" is pointless, so the action only
            // appears when there is somewhere to go.
            if (card.ui != null) {
                CardAction(label = "打开") { onOpenPage(card.id) }
                Spacer(Modifier.width(10.dp))
            }
            CardAction(label = "清理") { onClearStorage(card.id) }
            Spacer(Modifier.weight(1f))
            CardAction(label = "卸载", danger = true) { onUninstall(card.id) }
        }

        if (confirming) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "卸载只删除 EV/mods 里的容器文件，插件存储的数据会保留。重启 EV 后生效。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardAction(label = "取消") { onCancelUninstall() }
                Spacer(Modifier.weight(1f))
                CardAction(label = "确认卸载", danger = true) { onConfirmUninstall() }
            }
        }

        if (expanded) {
            val requested = HostPermission.entries.filter { it in card.requestedPermissions }
            if (requested.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "权限",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                requested.forEach { permission ->
                    val granted = permission in card.grantedPermissions
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onPermission(card.id, permission, !granted)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = permissionLabel(permission),
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (granted) "已授予" else "未授予",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (granted) colors.primary else StatusWarning,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "存储 ${formatBytes(card.storageBytes)} / ${formatBytes(PluginStorageFiles.DEFAULT_QUOTA_BYTES)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "清理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.primary,
                )
            }
            if (card.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = card.error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusError,
                )
            } else if (!card.enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已禁用，不会加载",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun CardAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, colors.onSurfaceVariantSummary.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (danger) StatusError else colors.primary,
        )
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

private fun externalModsPath(): String = "${Environment.getExternalStorageDirectory().path}/EV/mods"
