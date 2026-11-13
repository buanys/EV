package ev.buan.permissions

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 一条权限在界面上的样子：**状态是自己读出来的，不是猜的**。
 *
 * 分两类，处置方式完全不同：
 * - 运行时权限（相机、通讯录…）在系统弹窗里授过就完事，这里只报告结果，跳应用详情让用户自己关；
 * - 特殊权限（无障碍、所有文件、通知使用权、悬浮窗…）没有弹窗，只能跳各自的设置页，
 *   而且它们的状态在 `Settings.Secure` / `AppOps` 里，**普通 checkSelfPermission 读不到**。
 */
data class PermissionEntry(
    val key: String,
    val title: String,
    val summary: String,
    val granted: Boolean,
    /** 点这一行要去的地方；null 表示只能看不能跳。 */
    val target: PermissionTarget?,
)

/** 去哪儿授权。用受控的枚举而不是裸 Intent，页面层就不用认识系统 action。 */
sealed interface PermissionTarget {
    data object Accessibility : PermissionTarget
    data object AllFiles : PermissionTarget
    data object NotificationAccess : PermissionTarget
    data object Overlay : PermissionTarget
    data object UsageAccess : PermissionTarget
    data object ExactAlarm : PermissionTarget
    /** 运行时权限没有直达页，只能去应用详情自己关。 */
    data object AppDetails : PermissionTarget
}

/**
 * 读当前状态。**每次进页面都重新读**：用户多半是刚从系统设置回来的，
 * 缓存一次就可能显示"未授予"直到重启。
 */
object PermissionProbe {

    fun read(context: Context): List<PermissionEntry> {
        val pkg = context.packageName
        val cr = context.contentResolver
        return listOf(
            entry(
                key = "accessibility",
                title = "无障碍服务",
                summary = "读屏幕、点按、滑动都靠它。关掉之后 EV 什么都做不了。",
                granted = enabledServices(cr).contains(pkg),
                target = PermissionTarget.Accessibility,
            ),
            entry(
                key = "all_files",
                title = "所有文件访问",
                summary = "插件投放目录 /sdcard/EV/mods 和文件类工具需要它。",
                granted = android.os.Environment.isExternalStorageManager(),
                target = PermissionTarget.AllFiles,
            ),
            entry(
                key = "notification_access",
                title = "通知使用权",
                summary = "读通知栏内容，用来判断当前 app 在做什么。",
                granted = enabledListeners(cr).contains(pkg),
                target = PermissionTarget.NotificationAccess,
            ),
            entry(
                key = "overlay",
                title = "悬浮窗",
                summary = "让 EV 在别的 app 上面显示状态和进度。",
                granted = Settings.canDrawOverlays(context),
                target = PermissionTarget.Overlay,
            ),
            entry(
                key = "usage_stats",
                title = "使用情况访问",
                summary = "知道最近打开过哪些 app，用于上下文推断。",
                granted = hasUsageStats(context),
                target = PermissionTarget.UsageAccess,
            ),
            entry(
                key = "exact_alarm",
                title = "精确闹钟",
                summary = "定时任务到了点能准点触发，而不是被系统挪后。",
                granted = exactAlarmAllowed(context),
                target = PermissionTarget.ExactAlarm,
            ),
            entry(
                key = "camera",
                title = "相机",
                summary = "需要时拍一张画面作为上下文。",
                granted = runtimeGranted(context, android.Manifest.permission.CAMERA),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "microphone",
                title = "麦克风",
                summary = "语音输入和录音类工具。",
                granted = runtimeGranted(context, android.Manifest.permission.RECORD_AUDIO),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "location",
                title = "定位",
                summary = "问答和工具里用到「我在哪」时才会取。",
                granted = runtimeGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "contacts",
                title = "通讯录",
                summary = "查联系人、按名字找号码。",
                granted = runtimeGranted(context, android.Manifest.permission.READ_CONTACTS),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "sms",
                title = "短信",
                summary = "读验证码和通知类短信。",
                granted = runtimeGranted(context, android.Manifest.permission.READ_SMS),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "calendar",
                title = "日历",
                summary = "读写日程，安排提醒。",
                granted = runtimeGranted(context, android.Manifest.permission.READ_CALENDAR),
                target = PermissionTarget.AppDetails,
            ),
            entry(
                key = "notifications",
                title = "发通知",
                summary = "任务跑完、需要你确认时，能提醒到你。",
                granted = runtimeGranted(context, android.Manifest.permission.POST_NOTIFICATIONS),
                target = PermissionTarget.AppDetails,
            ),
        ).sortedBy { it.granted }
    }

    /** 已授予的排后面：进页面先看到的是"还差什么"。 */
    private fun entry(
        key: String,
        title: String,
        summary: String,
        granted: Boolean,
        target: PermissionTarget?,
    ) = PermissionEntry(key, title, summary, granted, target)

    private fun runtimeGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun enabledServices(cr: android.content.ContentResolver): String =
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

    private fun enabledListeners(cr: android.content.ContentResolver): String =
        Settings.Secure.getString(cr, "enabled_notification_listeners").orEmpty()

    private fun hasUsageStats(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun exactAlarmAllowed(context: Context): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    /** 目标 → 实际 Intent。页面只认 [PermissionTarget]。 */
    fun intent(context: Context, target: PermissionTarget): Intent {
        val pkgUri = android.net.Uri.fromParts("package", context.packageName, null)
        return when (target) {
            PermissionTarget.Accessibility ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionTarget.AllFiles ->
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
            PermissionTarget.NotificationAccess ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            PermissionTarget.Overlay ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            PermissionTarget.UsageAccess ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            PermissionTarget.ExactAlarm ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            PermissionTarget.AppDetails ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
