package ev.buan

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-container scan results, so a launch only pays for containers whose bytes changed.
 *
 * The install pass used to re-hash and re-scan every container on every start: with two ~27 MB
 * containers in `EV/mods` that was ~1.8 s spent proving the same bytes were still the same bytes,
 * and it grows with every plugin added.
 *
 * A published container is immutable - the host locks its private copy read-only - so
 * `(size, lastModified)` identifies a version well enough for this cache. Defeating it means
 * replacing the file without touching either field, and anyone who can do that can already drop a
 * fresh jar into `EV/mods` and pass the same trust gate.
 */
internal class PluginScanCache(private val file: File) {

    data class Entry(
        val size: Long,
        val modified: Long,
        val sha256: String,
        /** Class names that shadow a host or platform package; empty when the container is clean. */
        val violations: List<String>,
    )

    private val entries = HashMap<String, Entry>()

    fun load() {
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val root = JSONObject(text)
            root.keys().forEach { path ->
                val item = root.optJSONObject(path) ?: return@forEach
                val violations = item.optJSONArray("violations")?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                }.orEmpty()
                entries[path] = Entry(
                    size = item.optLong("size", -1L),
                    modified = item.optLong("modified", -1L),
                    sha256 = item.optString("sha256"),
                    violations = violations,
                )
            }
        }
    }

    /** The cached result for exactly this version of the file, or null when it must be recomputed. */
    fun get(path: String, size: Long, modified: Long): Entry? =
        entries[path]?.takeIf { it.size == size && it.modified == modified && it.sha256.isNotEmpty() }

    fun put(path: String, entry: Entry) {
        entries[path] = entry
    }

    /** Forgets containers this pass did not scan, so the file cannot grow without bound. */
    fun retain(paths: Set<String>) {
        entries.keys.retainAll(paths)
    }

    fun save() {
        try {
            val root = JSONObject()
            entries.forEach { (path, entry) ->
                root.put(
                    path,
                    JSONObject().apply {
                        put("size", entry.size)
                        put("modified", entry.modified)
                        put("sha256", entry.sha256)
                        put("violations", JSONArray(entry.violations))
                    },
                )
            }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) {
                    android.util.Log.w("EV", "scan cache: rename failed for ${file.absolutePath}")
                }
            }
        } catch (error: Throwable) {
            android.util.Log.w("EV", "scan cache: save failed (${entries.size} entries)", error)
        }
    }
}
