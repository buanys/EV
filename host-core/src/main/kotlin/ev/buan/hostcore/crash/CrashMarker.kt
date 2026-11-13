package ev.buan.hostcore.crash

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * What the next process learns about the crash that ended the previous one.
 */
data class PendingCrash(
    /** Full report from `last.txt`; empty when the report was lost or could not be read. */
    val report: String,
    /**
     * Plugin whose UI the host had last handed to a screen when it crashed, when known.
     *
     * This is an attribution hint, not a verdict: every plugin shares the host process and the host
     * does no in-process isolation, so a crash cannot be proven to belong to this plugin.
     */
    val suspectedPluginId: String?,
    /** Wall-clock time the previous process wrote the marker; null when unreadable. */
    val recordedAt: Instant?,
)

/**
 * The on-disk pair that turns "the previous run died" into a next-launch decision.
 *
 * `files/crash/last.txt` is a readable report for the user or `adb pull`, and
 * `files/crash/crash.pending` is the marker the next launch checks. The two files are separate on
 * purpose: the marker is authoritative (it must survive a partially written report), while the
 * report is diagnostics that may be missing or truncated without changing the decision.
 *
 * Writing order is report first, then marker: a marker must never point at a missing report, while a
 * report without a marker is harmless and simply gets overwritten by the next crash.
 *
 * This is pure JVM logic on purpose - the Android side only supplies `filesDir` - so the recovery
 * decision is unit-tested without a device.
 */
object CrashMarker {

    /** Directory under `filesDir` that holds the crash files. */
    const val DIRECTORY_NAME: String = "crash"

    /** Human-readable report of the last crash. */
    const val REPORT_FILE_NAME: String = "last.txt"

    /** Marker that makes the next launch enter safe mode. */
    const val PENDING_FILE_NAME: String = "crash.pending"

    private const val KEY_TIME = "time"
    private const val KEY_PLUGIN = "plugin"

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

    fun reportFile(filesDir: File): File = File(directory(filesDir), REPORT_FILE_NAME)

    fun pendingFile(filesDir: File): File = File(directory(filesDir), PENDING_FILE_NAME)

    /**
     * Writes [report] and then the marker in one attempt. Returns false when either write failed
     * (the caller is already crashing and can only log); a failed marker write cannot make the next
     * launch think the process crashed, which is the safe direction.
     */
    fun record(
        filesDir: File,
        report: String,
        suspectedPluginId: String? = null,
        recordedAt: Instant = Instant.now(),
    ): Boolean {
        val directory = directory(filesDir)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return runCatching {
            reportFile(filesDir).writeText(report)
            pendingFile(filesDir).writeText(markerText(recordedAt, suspectedPluginId))
            true
        }.getOrDefault(false)
    }

    /**
     * The pending crash, or null when the previous run ended cleanly.
     *
     * A present marker always counts, even when its content is garbled or the report is gone: the
     * marker existing is the fact that the previous process did not finish, and safe mode is the
     * conservative response. Fields that cannot be parsed degrade to null.
     */
    fun pending(filesDir: File): PendingCrash? {
        val marker = pendingFile(filesDir)
        if (!marker.isFile) return null
        val fields = runCatching {
            marker.readLines().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.take(separator) to line.substring(separator + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
        return PendingCrash(
            report = runCatching { reportFile(filesDir).takeIf { it.isFile }?.readText() }
                .getOrNull()
                .orEmpty(),
            suspectedPluginId = fields[KEY_PLUGIN]
                ?.let(::decodePluginId)
                ?.takeIf { it.isNotEmpty() },
            recordedAt = fields[KEY_TIME]?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    /**
     * Ends safe mode by removing the marker. `last.txt` is deliberately kept: it is the only
     * post-mortem available when the app cannot reproduce the crash, and it is overwritten by the
     * next crash rather than accumulating.
     */
    fun clearPending(filesDir: File): Boolean {
        val marker = pendingFile(filesDir)
        return !marker.exists() || marker.delete()
    }

    private fun markerText(recordedAt: Instant, suspectedPluginId: String?): String = buildString {
        append(KEY_TIME).append('=').append(recordedAt).append('\n')
        if (suspectedPluginId != null) {
            append(KEY_PLUGIN).append('=').append(encodePluginId(suspectedPluginId)).append('\n')
        }
    }

    // Plugin ids come from container metadata, so a newline or '=' in one must not be able to forge
    // additional marker fields. Form encoding keeps the marker one key/value pair per line.
    private fun encodePluginId(id: String): String = URLEncoder.encode(id, StandardCharsets.UTF_8)

    private fun decodePluginId(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()
}
