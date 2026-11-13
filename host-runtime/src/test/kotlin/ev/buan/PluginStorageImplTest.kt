package ev.buan

import ev.buan.hostcore.storage.PluginStorageFiles
import ev.buan.hostcore.storage.StorageQuotaExceededException
import java.io.File
import java.nio.file.Files
import org.cordis.api.PluginStorageQuotaExceededException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adapter half of the storage contract: binds one plugin id to its roots and maps host-core
 * exceptions to the published contract exception. Filesystem rules themselves are covered by
 * `:host-core` tests; this keeps the Android-side wiring honest.
 */
class PluginStorageImplTest {

    private val created = mutableListOf<File>()

    private fun impl(
        id: String,
        quotaBytes: Long = PluginStorageFiles.DEFAULT_QUOTA_BYTES,
    ): PluginStorageImpl {
        val base = Files.createTempDirectory("ev-storage-").toFile()
        created += base
        return PluginStorageImpl(
            id,
            PluginStorageFiles(
                dataRoot = File(base, "files/plugins/$id"),
                cacheRoot = File(base, "cache/plugins/$id"),
                quotaBytes = quotaBytes,
            ),
        )
    }

    private fun cleanup() {
        created.forEach { it.deleteRecursively() }
        created.clear()
    }

    @Test
    fun `两个插件同相对路径互不影响`() {
        try {
            val first = impl("plugin.a")
            val second = impl("plugin.b")

            first.write("state.json", "a".encodeToByteArray())
            second.write("state.json", "b".encodeToByteArray())

            assertContentEquals("a".encodeToByteArray(), first.read("state.json"))
            assertContentEquals("b".encodeToByteArray(), second.read("state.json"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun `越界路径被拒绝`() {
        try {
            val storage = impl("plugin.a")
            assertFailsWith<IllegalArgumentException> {
                storage.write("../escape.txt", byteArrayOf(1))
            }
            assertFailsWith<IllegalArgumentException> { storage.read("/etc/passwd") }
        } finally {
            cleanup()
        }
    }

    @Test
    fun `配额异常转换为契约异常`() {
        try {
            val storage = impl("plugin.a", quotaBytes = 4)
            val error = assertFailsWith<PluginStorageQuotaExceededException> {
                storage.write("too-big.bin", ByteArray(8))
            }
            assertTrue(error.cause is StorageQuotaExceededException, "cause=${error.cause}")
        } finally {
            cleanup()
        }
    }

    @Test
    fun `clear 清空占用`() {
        try {
            val storage = impl("plugin.a")
            storage.atomicWrite("dir/state.txt", byteArrayOf(1, 2, 3))
            assertEquals(3L, storage.usageBytes())

            storage.clear()

            assertEquals(0L, storage.usageBytes())
            assertTrue(storage.list().isEmpty())
        } finally {
            cleanup()
        }
    }
}
