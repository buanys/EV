package ev.buan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ev.buan.ui.EvPage
import ev.buan.ui.EvPreferenceDivider
import ev.buan.ui.EvPreferenceGroup
import ev.buan.ui.EvPreferenceRow
import ev.buan.ui.StatusWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import org.cordis.api.HostPermission
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 管理页的三种"没东西可显示"：安全模式提示、空列表、缺权限页面，外加几个格式化函数。
 *
 * 它们与插件数据无关——给什么显示什么，所以放在一处；主页面因此只剩"这一页怎么组织"。
 */

/**
 * Top-of-page notice while the process runs in safe mode.
 *
 * Safe mode is a next-launch recovery path, not in-process supervision: Compose has no error
 * boundary, so a plugin composable that throws kills the process before the host could catch it.
 * The copy therefore promises "nothing loaded this launch", never "recovered".
 */
@Composable
internal fun SafeModeSection(notice: SafeModeNotice, onExit: () -> Unit) {
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
internal fun formatCrashTime(instant: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(instant)

/** Management-page wording for each contract constant; the wire name alone means nothing to a user. */
internal fun permissionLabel(permission: HostPermission): String = when (permission) {
    HostPermission.NETWORK -> "网络访问"
    HostPermission.PLUGIN_STORAGE -> "插件存储"
    HostPermission.BACKGROUND -> "后台运行"
    HostPermission.NOTIFICATIONS -> "通知"
    HostPermission.CLIPBOARD -> "剪贴板"
    HostPermission.INSTALL_PACKAGES -> "安装应用"
    HostPermission.ROOT_SHELL -> "Root Shell"
    HostPermission.SECRETS -> "凭据保管"
    HostPermission.CALENDAR -> "日历读写"
    HostPermission.DEVICE_CONTROL -> "设备控制"
}

/** Compact, locale-stable size for the storage row. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
internal fun EmptyHint() {
    EvPreferenceGroup {
        EvPreferenceRow(
            title = "没有插件",
            summary = "把 .apk / .jar / .dex 放进 ${externalModsPath()} 再重开 EV。",
        )
    }
}

@Composable
internal fun PermissionPage(onGrant: () -> Unit) {
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
