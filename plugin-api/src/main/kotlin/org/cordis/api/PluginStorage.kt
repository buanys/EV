package org.cordis.api

import org.cordis.ServiceKey
import java.io.IOException

/**
 * One plugin's own file area, mediated by the host.
 *
 * Paths are relative, POSIX-style and resolved inside that plugin's area only. The host rejects
 * absolute paths and any `.` / `..` segment with [IllegalArgumentException], so this API cannot be
 * used to walk into another plugin's files or the host's private tree.
 *
 * [write] replaces a whole file. [atomicWrite] stages a temporary file and renames it into place,
 * so a reader sees either the old bytes or the new bytes, never a half-written file. The persistent
 * area has a quota (20 MB for this host); exceeding it throws [PluginStorageQuotaExceededException].
 *
 * Data survives disabling the plugin and is removed when the plugin is uninstalled or the user
 * clears it. Per ADR-0003 this is a host-mediated convention plus quota, not a sandbox: a plugin in
 * the same process can still touch the file system directly. The API exists so well-behaved plugins
 * stay inside their allocated area and the host can account for usage.
 */
interface PluginStorage {
    /** File content, or null when [path] does not exist. */
    fun read(path: String): ByteArray?

    /** Writes [path], creating parent directories; replaces existing content. */
    fun write(path: String, bytes: ByteArray)

    /** Writes [path] through a temporary file and a rename, so readers never see partial bytes. */
    fun atomicWrite(path: String, bytes: ByteArray)

    /** Deletes [path]; directories are removed recursively. True when something existed. */
    fun delete(path: String): Boolean

    /**
     * Entry names directly under [path] (the storage root for the default empty path), sorted.
     * A missing directory is an empty list, not an error.
     */
    fun list(path: String = ""): List<String>

    companion object {
        val Key = ServiceKey<PluginStorage>("host/storage")
    }
}

/** A [PluginStorage.write] or [PluginStorage.atomicWrite] would exceed the per-plugin quota. */
class PluginStorageQuotaExceededException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
