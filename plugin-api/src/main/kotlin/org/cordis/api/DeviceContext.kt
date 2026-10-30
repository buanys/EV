package org.cordis.api

import org.cordis.ServiceKey

/**
 * 设备上下文：agent 的"眼睛和手"里，宿主独占的那一半。
 *
 * 为什么必须由宿主提供：`PackageManager`、`NotificationManager`、`ClipboardManager`、屏幕与电池
 * 全都挂在 Android `Context` 上，而插件拿不到 `Context`（它们只从 [ServiceDirectory] 取服务）。
 * 插件能自己做的只有文件（`Environment` 是静态的）和网络（OkHttp 是纯 JVM）。
 *
 * 这一层刻意只读 + 两个受控副作用（写剪贴板、发通知）。**不给**"改系统设置""装应用"
 * 这类需要另一套授权模型的动作——那些应当有自己的契约，而不是混进来一个万能接口。
 */
interface DeviceContextService {

    /**
     * 有启动入口的应用，按名称排序。
     *
     * 只列**可启动**的（有 `MAIN/LAUNCHER` 意图），不是"装了哪些包"：
     * agent 想用的是"打开微信"，列一堆服务和库给它只会浪费上下文。
     * 系统应用一并列出但打了 [AppEntry.system] 标记，让调用方能自己决定要不要过滤。
     */
    suspend fun apps(): List<AppEntry>

    /**
     * 启动一个应用的前台界面。
     *
     * 不去猜"用户想打开哪个页面"：只认 `getLaunchIntentForPackage`，拿不到就如实说这个包
     * 没有启动入口（服务、插件、被禁用的应用都属于这类）。
     */
    fun launch(packageName: String): LaunchOutcome

    /** 电量、存储、内存、网络、屏幕、机型与系统版本——一次读全，省一轮工具调用。 */
    fun status(): DeviceStatus

    /**
     * 读剪贴板。
     *
     * Android 10+ 只允许**前台应用**读剪贴板，宿主不在前台时返回 null 并说明原因，
     * 而不是假装剪贴板是空的——这两件事对模型的含义完全不同。
     */
    fun clipboard(): ClipRead

    /** 写剪贴板。返回是否成功（个别 ROM 在无输入焦点时会拒绝）。 */
    fun setClipboard(text: String): Boolean

    /**
     * 发一条系统通知。
     *
     * 这是**副作用**：调用方应当把它当危险动作对待（模型可以在用户不在的时候给人发通知）。
     * 需要 `POST_NOTIFICATIONS` 运行时授权；没授权时返回 false 并把原因带回去。
     */
    fun notify(title: String, body: String): NotifyOutcome

    companion object {
        val Key = ServiceKey<DeviceContextService>("host/device-context")
    }
}

/** 一个有启动入口的应用。 */
data class AppEntry(
    val packageName: String,
    /** 用户看到的名字，取不到时回落为包名。 */
    val label: String,
    /** 系统应用（预装）。 */
    val system: Boolean,
)

/** 启动结果。失败要能区分"没这个包"和"有这个包但打不开"。 */
sealed interface LaunchOutcome {
    data object Launched : LaunchOutcome

    /** 包不存在，或对宿主不可见。 */
    data class NoSuchApp(val packageName: String) : LaunchOutcome

    /** 包存在但没有前台入口（服务、被禁用、纯库）。 */
    data class NoLauncher(val packageName: String) : LaunchOutcome

    /** 系统拒绝了这次启动，[reason] 是原始原因。 */
    data class Blocked(val reason: String) : LaunchOutcome
}

/** 一次设备状态快照。全部是可空/带默认值的读值，读不到不抛异常。 */
data class DeviceStatus(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** 电量百分比，0–100；读不到时 -1。 */
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val storageTotalBytes: Long = -1,
    val storageFreeBytes: Long = -1,
    val memoryTotalBytes: Long = -1,
    val memoryAvailableBytes: Long = -1,
    /** `wifi` / `mobile` / `ethernet` / `none` / `unknown`。 */
    val network: String = "unknown",
    /** 屏幕是否亮着；读不到时为 null。 */
    val screenOn: Boolean? = null,
    /** 从开机到现在的毫秒数。 */
    val uptimeMs: Long = 0,
)

/** 读剪贴板的结果：拿不到和"空"必须分开。 */
sealed interface ClipRead {
    data class Content(val text: String) : ClipRead

    /** 剪贴板是空的。 */
    data object Empty : ClipRead

    /** 宿主不在前台（Android 10+ 限制），本次读不到。 */
    data object NotForeground : ClipRead
}

/** 发通知的结果。 */
sealed interface NotifyOutcome {
    data object Posted : NotifyOutcome

    /** 没有通知权限（用户没授权 POST_NOTIFICATIONS）。 */
    data object NoPermission : NotifyOutcome

    data class Failed(val reason: String) : NotifyOutcome
}
