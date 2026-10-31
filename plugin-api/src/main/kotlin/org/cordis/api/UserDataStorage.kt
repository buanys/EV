package org.cordis.api

import org.cordis.ServiceKey

/**
 * The user's own data for one plugin, kept by the host rather than inside the plugin's area.
 *
 * Same file API as [PluginStorage] (same path rules, quota and permission gate), but the bytes live
 * in a host-owned tree that the plugin lifecycle never deletes: disabling, reinstalling, rebuilding
 * or unapproving a plugin keeps this data. Only the user can remove it, from the management page.
 *
 * Use it for things that belong to the user rather than to the plugin's code: conversations, model
 * keys and provider settings, memories. Put throwaway caches in [PluginStorage] instead.
 */
interface UserDataStorage : PluginStorage {
    companion object {
        val Key = ServiceKey<UserDataStorage>("host/userdata")
    }
}

/** Written into the user-data area once the old plugin-area copy has been carried over. */
private const val MIGRATION_MARKER = ".migrated-from-plugin-area"

/**
 * One-time move of a plugin's data from its private area into [UserDataStorage].
 *
 * Call it at startup with the plugin's [PluginStorage] (null when the plugin never declared it). It
 * runs only while the marker is absent, and the marker is written only after a complete copy: a
 * failed copy is retried next start, and once the marker exists the old copy is never read again,
 * so anything the user later deletes stays deleted instead of coming back from the old files.
 *
 * Never throws. Both storage areas are permission-gated, and a revoked grant would otherwise fail
 * plugin activation over a one-time data move that is not worth failing for: the migration is simply
 * retried next start, once the user grants the capability again.
 */
fun migrateToUserData(legacy: PluginStorage?, userData: UserDataStorage) {
    if (legacy == null) return
    runCatching {
        if (userData.read(MIGRATION_MARKER) == null) {
            legacy.copyMissingTo(userData)
            userData.atomicWrite(MIGRATION_MARKER, "1".toByteArray())
        }
    }
}

/**
 * Copies every file under [path] that [target] does not have yet, keeping the directory layout.
 *
 * Existing files in [target] always win, so running a copy twice never overwrites newer data.
 * Returns the number of files copied.
 */
fun PluginStorage.copyMissingTo(target: PluginStorage, path: String = ""): Int {
    var copied = 0
    for (name in list(path)) {
        val child = if (path.isEmpty()) name else "$path/$name"
        val bytes = read(child)
        if (bytes != null) {
            if (target.read(child) == null) {
                target.atomicWrite(child, bytes)
                copied += 1
            }
        } else {
            copied += copyMissingTo(target, child)
        }
    }
    return copied
}
