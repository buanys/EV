package ev.buan

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import org.cordis.api.NotificationEntry

/**
 * 通知读取：把系统通知栏变成 agent 能看的东西。
 *
 * 这是 Android 上少数几个"用户必须单独授权"的能力——「通知使用权」不在运行时权限里，
 * 藏在 设置 → 通知 → 通知使用权，只能由用户手动打开。所以状态只有两种：连上了（能读），
 * 或者没连上（一条都读不到），而且必须如实区分"没授权"和"最近没通知"。
 *
 * 为什么状态放在 companion 上：`NotificationListenerService` 的实例由系统创建和销毁，
 * 宿主拿不到它的句柄，只能让服务自己登记与注销。
 *
 * 为什么**每次读取都向系统要**：服务是在应用启动之后才被绑定的，早于它连上的读取
 * 必然拿到空缓存——真机上就踩过一次（授权了，但读取发生在连接之前，于是 count=0
 * 看着像"读不到"，实际是"读得太早"）。`activeNotifications` 是权威来源，
 * 缓存只用来补上那些已被撤回、但用户刚刚还在看的条目。
 */
class EvNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        live = this
        refresh()
    }

    override fun onListenerDisconnected() {
        connected = false
        if (live === this) live = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        refresh()
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    /** 用系统此刻的快照刷新缓存，拿不到就保留旧内容，不假装清空。 */
    private fun refresh() {
        val snapshot = runCatching { activeNotifications }.getOrNull().orEmpty()
        val keys = snapshot.mapNotNull { it.key }.toSet()
        recent.keys.retainAll(keys)
        snapshot.forEach { sbn -> toEntry(sbn)?.let { recent[it.key] = it } }
    }

    private fun toEntry(sbn: StatusBarNotification): NotificationEntry? {
        val key = sbn.key ?: return null
        val extras = sbn.notification?.extras
        fun text(of: String): String =
            extras?.getCharSequence(of)?.toString().orEmpty()
        val title = text(Notification.EXTRA_TITLE)
        val body = text(Notification.EXTRA_BIG_TEXT).ifBlank { text(Notification.EXTRA_TEXT) }

        // 既没标题也没正文的（纯媒体控制、纯图标通知）对 agent 没有信息量，丢掉。
        if (title.isBlank() && body.isBlank()) return null

        val packageName = sbn.packageName ?: return null
        val label = runCatching {
            val manager = packageManager
            manager.getApplicationInfo(packageName, 0).loadLabel(manager)?.toString().orEmpty()
        }.getOrDefault("")

        return NotificationEntry(
            packageName = packageName,
            appLabel = label.ifBlank { packageName },
            title = title,
            text = body,
            postedAt = sbn.postTime,
        )
    }

    companion object {
        private const val MAX_KEPT = 50

        private val recent = ConcurrentHashMap<String, NotificationEntry>()

        @Volatile
        private var connected: Boolean = false

        /** 活着的那个服务实例。系统创建/销毁它，宿主没有句柄，只能自己记一个。 */
        @Volatile
        private var live: EvNotificationListener? = null

        fun isConnected(): Boolean = connected

        /**
         * 用户是否已经在系统里给过「通知使用权」。
         *
         * 与 [isConnected] 分开：授权了但服务还没连上时，前者为真、后者为假——
         * 这时候告诉用户"稍等"比"你没授权"准确。
         */
        fun hasAccess(context: android.content.Context): Boolean {
            val enabled = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                )
            }.getOrNull() ?: return false
            val component = android.content.ComponentName(
                context,
                EvNotificationListener::class.java,
            )
            return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
        }

        /**
         * 最近的 [limit] 条通知。
         *
         * 每次向系统要，不靠缓存——监听服务可能比读取晚一步连上，靠缓存会误报"没有通知"。
         */
        fun latest(limit: Int): List<NotificationEntry> {
            live?.refresh()
            return recent.values.sortedByDescending { it.postedAt }.take(limit.coerceIn(1, MAX_KEPT))
        }
    }
}
