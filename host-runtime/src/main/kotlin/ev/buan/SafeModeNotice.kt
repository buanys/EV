package ev.buan

import java.time.Instant

/** What the management page needs to explain safe mode. */
data class SafeModeNotice(
    /** Last plugin whose UI the host handed to a screen; a hint only, never a verdict. */
    val suspectedPluginId: String?,
    /** When the previous process wrote the marker; null when that field was unreadable. */
    val recordedAt: Instant?,
    /**
     * Full report from `files/crash/last.txt`: timestamp, thread, suspected plugin and the whole
     * stack trace. Empty when the report was lost; the screen must then say so instead of showing
     * a blank panel, because "safe mode with no evidence" is exactly the state this field exists
     * to avoid.
     */
    val report: String,
)
