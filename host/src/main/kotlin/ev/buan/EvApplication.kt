package ev.buan

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import java.lang.ref.WeakReference

/**
 * Process-scoped owner of [HostRuntime].
 *
 * The runtime used to live in a mutable singleton that MainActivity assigned. That made the
 * runtime's existence depend on an Activity, so a plugin page restored by the system after the
 * process was killed had nothing to bind to and silently rendered "not ready". Owning it here ties
 * its lifetime to the process, which is what it actually is.
 */
class EvApplication : Application() {

    private var reference: HostRuntime? = null
    private val crashStore by lazy { CrashStore(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // 离屏浏览器（PageReader）要把 WebView 挂到一个真实的窗口树上才能可靠地跑 JS。
        // 这里只记录最近一个 resumed 的 Activity，用完即弃，不在 Application 里持强引用。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
        })
    }

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    /** 当前可见的 Activity；没有则为 null（离屏浏览器会退化成不挂载）。 */
    fun currentActivity(): Activity? = currentActivity?.get()

    /** Creates the runtime on first use; safe to call from any component. */
    val runtime: HostRuntime
        get() = synchronized(this) {
            reference ?: HostRuntime(this).also { reference = it }
        }

    /**
     * The runtime only if it has actually completed a start in this process and is not in safe mode.
     *
     * A restarted process has an Application and no loaded plugins, and a safe-mode launch skips
     * every plugin; callers that need plugins must treat null as "go back to the host screen and
     * start over". Safe-mode exit sets it once the plugins are loaded again.
     */
    val startedRuntime: HostRuntime?
        get() = synchronized(this) { reference?.takeIf { it.hasStarted } }

    /**
     * Process-wide crash handler: record the crash for the next launch, then hand it back to the
     * platform's handler, which reports it and kills the process. Swallowing the throwable here
     * would leave a process running in an unknown state.
     *
     * The marker only buys a next-launch decision, never in-process recovery: Compose has no error
     * boundary, so an exception thrown by a plugin's composable ends the process before any host
     * code could catch it. The next start reads the marker in [HostRuntime.start] and enters safe
     * mode with every plugin skipped.
     */
    private fun installCrashHandler() {
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failed write must never mask the crash that is already in flight.
            runCatching { crashStore.record(thread.name, error, lastShownPluginId()) }
            if (platformHandler != null) {
                platformHandler.uncaughtException(thread, error)
            } else {
                // Not reachable on Android - the platform installs its handler before Application
                // creation - but keeping this a process kill avoids continuing in a corrupt state.
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** Attribution hint from the runtime, read without creating it on the crashing thread. */
    private fun lastShownPluginId(): String? = synchronized(this) { reference?.lastShownPluginId }
}
