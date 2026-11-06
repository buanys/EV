package ev.buan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.cordis.api.BackgroundService
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService

/**
 * The single foreground service the host runs on behalf of plugins.
 *
 * Plugins never start services themselves: they call [BackgroundService.acquire] on their own child
 * Context, and this service is kept alive (with a notification the user can see) while at least one
 * plugin holds a reference. Calling it a `specialUse` service is the Android 14+ truthful answer -
 * its purpose is app-defined plugin background work, not one of the platform's categories.
 *
 * A foreground service only buys liveness. It does not isolate plugin work, schedule it, or keep it
 * alive once the platform or the user stops the app; starting it from the background can still be
 * refused by Android, which surfaces as [IllegalStateException] to the plugin.
 */
class EvBackgroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this, intent?.getStringExtra(EXTRA_REASON))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: the type must be explicit. `specialUse` is declared in the manifest with
            // its required subtype property, and FOREGROUND_SERVICE_SPECIAL_USE is a normal
            // permission declared there too.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // API 33 knows no `specialUse` type; the two-argument form is what it expects.
            startForeground(NOTIFICATION_ID, notification)
        }
        // References live in this process only; a restart without them would be a phantom service.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // The system can stop a foreground service on its own (user swipe, OEM policy). References
        // cannot outlive the service instance, so let the next acquire start a fresh one.
        BackgroundSessions.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        /** Stable id: one notification for the whole process, not one per plugin. */
        const val NOTIFICATION_ID = 0xE5B001

        private const val CHANNEL_ID = "ev.background"
        private const val EXTRA_REASON = "ev.background.reason"

        /** Channel creation is idempotent; called by the service and before the first start. */
        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台运行",
                // LOW: the service is a status, not an event - it must never buzz on every acquire.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "插件在后台运行时保持的常驻通知"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun buildNotification(context: Context, reason: String?): Notification {
            val text = reason?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "插件后台任务：${it.take(MAX_REASON_LENGTH)}" }
                ?: "插件后台任务运行中"
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                PluginsActivity.intent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("EV 后台运行")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build()
        }

        private const val MAX_REASON_LENGTH = 120

        internal fun startIntent(context: Context, reason: String): Intent =
            Intent(context, EvBackgroundService::class.java).putExtra(EXTRA_REASON, reason)
    }
}

/**
 * Process-wide reference accounting for [BackgroundService].
 *
 * Counts are kept per plugin so a plugin can acquire repeatedly and release symmetrically, while
 * the service itself is shared: it starts on the first reference in the process and stops when the
 * last plugin drops its last reference. Host-side activation is also a source of truth: when a
 * plugin is deactivated, [releaseAll] drops its references, because a plugin that is no longer
 * running cannot legitimately keep the host's notification alive.
 *
 * Counts are memory-only on purpose. If the process dies the service dies with it, so persisting
 * references would only resurrect a phantom notification after a restart.
 */
internal object BackgroundSessions {

    private val counts = linkedMapOf<String, Int>()

    /** Whether the service has been asked to run and has not reported [onServiceDestroyed]. */
    private var serviceStarted = false

    /**
     * Takes one reference for [pluginId]; starts the service when it is the first one.
     *
     * The count is incremented before the platform call so the caller's reference is never lost if
     * the call fails; a refused start rolls the increment back and rethrows, leaving no phantom.
     */
    @Synchronized
    fun acquire(context: Context, pluginId: String, reason: String) {
        val wasIdle = counts.isEmpty()
        counts[pluginId] = (counts[pluginId] ?: 0) + 1
        if (serviceStarted && !wasIdle) return
        try {
            EvBackgroundService.createChannel(context)
            context.startForegroundService(EvBackgroundService.startIntent(context, reason))
            serviceStarted = true
        } catch (error: RuntimeException) {
            // ForegroundServiceStartNotAllowedException and friends: the platform is the authority.
            // Roll back so the plugin does not hold a reference with nothing behind it.
            drop(pluginId)
            throw error
        }
    }

    /** Drops one reference; releasing more times than acquiring is a no-op. */
    @Synchronized
    fun release(context: Context, pluginId: String) {
        if (drop(pluginId)) stopIfIdle(context)
    }

    /** Drops every reference of one plugin (deactivation, disable, uninstall, safe mode). */
    @Synchronized
    fun releaseAll(context: Context, pluginId: String) {
        counts.remove(pluginId)
        stopIfIdle(context)
    }

    /** The service instance is gone; a later acquire must start a new one. */
    @Synchronized
    fun onServiceDestroyed() {
        serviceStarted = false
    }

    /** Removes one reference, returning whether the plugin had one. */
    private fun drop(pluginId: String): Boolean {
        val current = counts[pluginId] ?: return false
        if (current <= 1) counts.remove(pluginId) else counts[pluginId] = current - 1
        return true
    }

    private fun stopIfIdle(context: Context) {
        if (counts.isNotEmpty()) return
        serviceStarted = false
        // stopService is harmless when nothing is running; keeping it unconditional means a stale
        // serviceStarted flag can never leave a notification behind.
        context.stopService(Intent(context, EvBackgroundService::class.java))
    }
}

/**
 * One plugin's [BackgroundService] binding.
 *
 * The host provides one instance per plugin on that plugin's child Context, so the plugin id is
 * fixed here: a plugin cannot acquire for another plugin. The permission gate is the same
 * [PermissionService] instance the plugin sees, so a revoked `BACKGROUND` grant fails the next
 * acquire with the usual `PERMISSION_DENIED` identity instead of only failing on reactivation.
 */
internal class BackgroundServiceImpl(
    private val pluginId: String,
    private val permissions: PermissionService,
    private val context: Context,
) : BackgroundService {

    override fun acquire(reason: String) {
        permissions.require(HostPermission.BACKGROUND)
        BackgroundSessions.acquire(context, pluginId, reason)
    }

    override fun release() {
        BackgroundSessions.release(context, pluginId)
    }
}
