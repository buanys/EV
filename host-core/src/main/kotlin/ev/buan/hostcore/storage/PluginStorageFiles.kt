package ev.buan.hostcore.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A write would push one plugin's persistent area above its quota. */
class StorageQuotaExceededException(message: String) : IOException(message)

/**
 * File-system half of the per-plugin storage contract, with no Android dependency.
 *
 * The host binds one instance to one plugin id (via [PluginStorageLayout]) and exposes it as
 * `org.cordis.api.PluginStorage`; the plugin never chooses a root. All paths go through
 * [PluginStoragePath], so `..`, absolute paths and path traversal are rejected before any I/O.
 *
 * The quota covers the persistent area; the scratch area only holds temporary files that are moved
 * into place or deleted, so it is excluded from accounting and cleaned with [clear].
 */
class PluginStorageFiles(
    private val dataRoot: File,
    private val cacheRoot: File,
    private val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
) {

    init {
        require(quotaBytes > 0) { "quota must be positive" }
    }

    /** File content, or null when the file does not exist. */
    fun read(path: String): ByteArray? {
        val file = PluginStoragePath.resolve(dataRoot, path)
        return if (file.isFile) file.readBytes() else null
    }

    /** Writes the whole file, creating parent directories and enforcing the quota. */
    fun write(path: String, bytes: ByteArray) {
        val file = PluginStoragePath.resolve(dataRoot, path)
        require(!file.isDirectory) { "存储路径是目录：$path" }
        ensureQuota(file, bytes.size.toLong(), path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /**
     * Writes through a temporary file in the scratch area and moves it into place with an atomic
     * rename. If the file system cannot move atomically (rare, e.g. cache and data on different
     * mounts) it falls back to a replacing move so the write still succeeds.
     */
    fun atomicWrite(path: String, bytes: ByteArray) {
        val target = PluginStoragePath.resolve(dataRoot, path)
        require(!target.isDirectory) { "存储路径是目录：$path" }
        ensureQuota(target, bytes.size.toLong(), path)
        target.parentFile?.mkdirs()
        cacheRoot.mkdirs()
        val temporary = File(cacheRoot, "atomic-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            moveIntoPlace(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Deletes a file, or a directory recursively. True when something existed. */
    fun delete(path: String): Boolean {
        val file = PluginStoragePath.resolve(dataRoot, path)
        if (!file.exists()) return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    /** Entry names directly under the directory; a missing directory lists as empty. */
    fun list(path: String = ""): List<String> {
        val directory = if (path.isEmpty()) dataRoot else PluginStoragePath.resolve(dataRoot, path)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()?.map { it.name }?.sorted().orEmpty()
    }

    /** Bytes currently used by the persistent area. */
    fun usageBytes(): Long {
        if (!dataRoot.isDirectory) return 0L
        return dataRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Removes both the persistent and the scratch area (uninstall, management clear). */
    fun clear() {
        dataRoot.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun ensureQuota(target: File, incomingBytes: Long, path: String) {
        val replaced = if (target.isFile) target.length() else 0L
        val projected = usageBytes() - replaced + incomingBytes
        if (projected > quotaBytes) {
            throw StorageQuotaExceededException(
                "插件存储超过配额（上限 ${quotaBytes / (1024 * 1024)} MB）：写入 $path 后需要 $projected 字节",
            )
        }
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (atomicFailure: IOException) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(atomicFailure)
                throw fallbackFailure
            }
        }
    }

    companion object {
        /** 20 MB, the per-plugin quota from PLAN T07. */
        const val DEFAULT_QUOTA_BYTES: Long = 20L * 1024 * 1024
    }
}
