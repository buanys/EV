package ev.buan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context as AndroidContext
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.cordis.api.AppEntry
import org.cordis.api.ClipRead
import org.cordis.api.DeviceContextService
import org.cordis.api.DeviceStatus
import org.cordis.api.LaunchOutcome
import org.cordis.api.NotifyOutcome

/**
 * 宿主的设备上下文实现。
 *
 * 全部是"读一眼就知道"的东西，加两个受控副作用（写剪贴板、发通知）。没有一处需要 root，
 * 也没有一处去猜用户的意图——启动应用只认系统给的 launch intent，拿不到就如实说。
 *
 * 为什么放在宿主而不是插件：`PackageManager` / `NotificationManager` / `ClipboardManager`
 * 都挂在 `Context` 上，插件没有。这是 [DeviceContextService] 存在的全部理由。
 */
internal class DeviceContextServiceImpl(private val context: AndroidContext) : DeviceContextService {

    override suspend fun apps(): List<AppEntry> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 宿主 manifest 里的 <queries> 声明了这一个意图，否则 API 30+ 会把列表过滤成空。
        val resolved = runCatching {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        }.getOrDefault(emptyList())

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val packageName = activity.packageName ?: return@mapNotNull null
                if (packageName.isBlank() || packageName == context.packageName) return@mapNotNull null
                val label = runCatching {
                    info.loadLabel(manager)?.toString()?.trim().orEmpty()
                }.getOrDefault("")
                AppEntry(
                    packageName = packageName,
                    label = label.ifEmpty { packageName },
                    system = runCatching {
                        (manager.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    }.getOrDefault(false),
                )
            }
            // 同一个包可能有多个启动入口，列一次就够。
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }

    override fun launch(packageName: String): LaunchOutcome {
        if (packageName.isBlank()) return LaunchOutcome.NoSuchApp(packageName)
        val manager = context.packageManager
        val known = runCatching { manager.getApplicationInfo(packageName, 0) }.isSuccess
        if (!known) return LaunchOutcome.NoSuchApp(packageName)

        val intent = runCatching { manager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return LaunchOutcome.NoLauncher(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            LaunchOutcome.Launched
        }.getOrElse { error ->
            LaunchOutcome.Blocked(error.message ?: error::class.java.simpleName)
        }
    }

    override fun status(): DeviceStatus {
        val battery = battery()
        val storage = storage()
        return DeviceStatus(
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            batteryPercent = battery.first,
            charging = battery.second,
            storageTotalBytes = storage.first,
            storageFreeBytes = storage.second,
            memoryTotalBytes = memory().first,
            memoryAvailableBytes = memory().second,
            network = network(),
            screenOn = runCatching {
                context.getSystemService(PowerManager::class.java)?.isInteractive
            }.getOrNull(),
            uptimeMs = SystemClock.elapsedRealtime(),
        )
    }

    override fun clipboard(): ClipRead {
        // Android 10+ 只让前台应用读剪贴板：不在前台时"读不到"和"是空的"必须分开报。
        val foreground = (context as? EvApplication)?.currentActivity() != null
        if (!foreground) return ClipRead.NotForeground
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ClipRead.NotForeground
        val clip = manager.primaryClip ?: return ClipRead.Empty
        if (clip.itemCount == 0) return ClipRead.Empty
        val text = runCatching { clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() }
            .getOrDefault("")
        return if (text.isEmpty()) ClipRead.Empty else ClipRead.Content(text)
    }

    override fun setClipboard(text: String): Boolean = runCatching {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("EV", text))
        true
    }.getOrDefault(false)

    override fun notify(title: String, body: String): NotifyOutcome {
        // POST_NOTIFICATIONS 是运行时权限（Android 13+）；没授权就明说，不要静默失败。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotifyOutcome.NoPermission
        }
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return NotifyOutcome.Failed("没有 NotificationManager")
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "agent 提醒",
                    // DEFAULT：这是 agent 主动找人的消息，该有提示音，但不该像来电一样弹满屏。
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "agent 完成任务或需要你注意时发的通知" },
            )
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title.ifBlank { "EV" })
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(nextId(), notification)
            NotifyOutcome.Posted
        }.getOrElse { NotifyOutcome.Failed(it.message ?: it::class.java.simpleName) }
    }

    // ---------------------------------------------------------------- readers

    private fun battery(): Pair<Int, Boolean> = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        percent to charging
    }.getOrDefault(-1 to false)

    private fun storage(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.totalBytes to stat.availableBytes
    }.getOrDefault(-1L to -1L)

    private fun memory(): Pair<Long, Long> = runCatching {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
            ?: return@runCatching -1L to -1L
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.totalMem to info.availMem
    }.getOrDefault(-1L to -1L)

    private fun network(): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching "unknown"
        val active = manager.activeNetwork ?: return@runCatching "none"
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching "none"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrDefault("unknown")

    private fun nextId(): Int = ((SystemClock.elapsedRealtime() / 1000) % Int.MAX_VALUE).toInt()

    private companion object {
        const val CHANNEL_ID = "agent-notify"
    }
}
