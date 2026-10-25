package ev.buan

import android.content.Context
import ev.buan.hostcore.crash.CrashMarker
import ev.buan.hostcore.crash.CrashReport
import ev.buan.hostcore.crash.PendingCrash
import java.io.File
import java.time.Instant

/** What the management page needs to explain safe mode. */
data class SafeModeNotice(
    /** Last plugin whose UI the host handed to a screen; a hint only, never a verdict. */
    val suspectedPluginId: String?,
    /** When the previous process wrote the marker; null when that field was unreadable. */
    val recordedAt: Instant?,
)

/**
 * Host-side wiring of the crash marker under `files/crash/`.
 *
 * The marker is written from the uncaught-exception handler (see [EvApplication]) and read by
 * [HostRuntime.start]; this class only supplies the Android `filesDir` and the report text. The
 * read/write decision itself lives in `host-core` (`CrashMarker`) so it is unit-tested on the JVM.
 *
 * Recovery is next-launch only. Compose has no error boundary, so an exception thrown by a plugin's
 * composable kills the process before any host code could catch it; safe mode exists to avoid
 * repeating the same crash on the next start, not to keep the process alive.
 */
class CrashStore(private val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    /** Marker left by a previous run, or null when that run ended cleanly. */
    fun pending(): PendingCrash? = CrashMarker.pending(filesDir)

    /** Ends safe mode; keeps `last.txt` for post-mortem inspection. */
    fun clearPending(): Boolean = CrashMarker.clearPending(filesDir)

    /**
     * Best effort: called from the uncaught-exception handler, so it must not throw and must not
     * mask the crash. False means the marker could not be written; that launch will then behave as
     * if the crash never happened, which is the only degradation available at this point.
     */
    fun record(threadName: String, error: Throwable, suspectedPluginId: String?): Boolean =
        runCatching {
            val recordedAt = Instant.now()
            val report = CrashReport.format(recordedAt, threadName, suspectedPluginId, error)
            CrashMarker.record(filesDir, report, suspectedPluginId, recordedAt)
        }.getOrDefault(false)
}
