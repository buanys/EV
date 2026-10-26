package ev.buan.hostcore.storage

import java.io.File

/**
 * On-disk layout of the per-plugin storage areas: `files/plugins/<dir>` and `cache/plugins/<dir>`.
 *
 * The directory name is the plugin id itself when the id is a safe file name; otherwise it is
 * escaped. The id comes from plugin metadata, so it must not be able to address a path outside the
 * storage tree. Escaped names always start with `~`, which can never start a raw id name, so an
 * escaped name and a raw id can never collide.
 */
object PluginStorageLayout {

    /** Directory under filesDir/cacheDir that holds one subdirectory per plugin. */
    const val ROOT_DIRECTORY: String = "plugins"

    /** Persistent area: `files/plugins/<dir>`. */
    fun dataRoot(filesDir: File, pluginId: String): File =
        File(File(filesDir, ROOT_DIRECTORY), directoryName(pluginId))

    /** Scratch area: `cache/plugins/<dir>`. */
    fun cacheRoot(cacheDir: File, pluginId: String): File =
        File(File(cacheDir, ROOT_DIRECTORY), directoryName(pluginId))

    /** Maps an arbitrary plugin id to one safe directory name. */
    fun directoryName(pluginId: String): String {
        require(pluginId.isNotEmpty()) { "插件 id 不能为空" }
        if (isSafeName(pluginId)) return pluginId
        val escaped = buildString {
            append('~')
            pluginId.forEach { character ->
                when {
                    character.isLetterOrDigit() || character == '.' || character == '_' ||
                        character == '-' -> append(character)

                    else -> {
                        append('%')
                        append(character.code.toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }
        require(escaped.length <= 200) { "插件 id 过长，无法映射到存储目录：$pluginId" }
        return escaped
    }

    /**
     * Carries a renamed plugin's storage area forward: for each `old id -> new id` pair, an existing
     * old area is renamed to the new id's directory when the new one does not exist yet.
     *
     * Must run before [pruneUnknown]: without it a rename reads as an uninstall and the user's data
     * is deleted instead of carried over. A new area that already exists wins - the old directory is
     * left alone (prune will collect it later), so a downgrade-then-upgrade never clobbers fresh
     * data with stale. Returns the applied renames as `"old -> new"` directory names.
     */
    fun migrateRenames(root: File, renames: Map<String, String>): List<String> {
        val applied = mutableListOf<String>()
        renames.forEach { (oldId, newId) ->
            val from = File(root, directoryName(oldId))
            val to = File(root, directoryName(newId))
            if (from.isDirectory && !to.exists() && from.renameTo(to)) {
                applied += "${from.name} -> ${to.name}"
            }
        }
        return applied.sorted()
    }

    /**
     * Deletes per-plugin directories under [root] whose plugin id is no longer installed.
     *
     * Uninstall is only detected from a completed sync (the caller decides when the accepted set is
     * trustworthy); this function just applies the difference. Returns the removed directory names.
     */
    fun pruneUnknown(root: File, installedIds: Set<String>): List<String> {
        val keep = installedIds.mapTo(HashSet()) { directoryName(it) }
        val removed = mutableListOf<String>()
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in keep && child.deleteRecursively()) {
                removed += child.name
            }
        }
        return removed.sorted()
    }

    private fun isSafeName(pluginId: String): Boolean =
        pluginId.length <= 200 &&
            pluginId != "." && pluginId != ".." &&
            pluginId.first().isLetterOrDigit() &&
            pluginId.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
}
