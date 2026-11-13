package ev.buan.hostcore.storage

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * File-system half of the storage contract: per-plugin isolation, relative-path enforcement, quota
 * and atomic replacement. Uses plain temp directories, so it runs on the JVM without Android.
 */
class PluginStorageFilesTest {

    @TempDir
    lateinit var temp: Path

    private fun storage(
        id: String,
        quotaBytes: Long = PluginStorageFiles.DEFAULT_QUOTA_BYTES,
    ): PluginStorageFiles = PluginStorageFiles(
        dataRoot = temp.resolve("files/plugins/$id").toFile(),
        cacheRoot = temp.resolve("cache/plugins/$id").toFile(),
        quotaBytes = quotaBytes,
    )

    @Test
    fun `两个插件同相对路径落到不同目录`() {
        val first = storage("plugin.a")
        val second = storage("plugin.b")

        first.write("notes/config.json", "a".encodeToByteArray())
        second.write("notes/config.json", "b".encodeToByteArray())

        assertContentEquals("a".encodeToByteArray(), first.read("notes/config.json"))
        assertContentEquals("b".encodeToByteArray(), second.read("notes/config.json"))
        assertTrue(temp.resolve("files/plugins/plugin.a/notes/config.json").toFile().isFile)
        assertTrue(temp.resolve("files/plugins/plugin.b/notes/config.json").toFile().isFile)
    }

    @Test
    fun `写入读取列举与删除`() {
        val storage = storage("plugin.a")
        storage.write("dir/b.txt", byteArrayOf(1, 2))
        storage.write("dir/a.txt", byteArrayOf(3))

        assertEquals(listOf("a.txt", "b.txt"), storage.list("dir"))
        assertEquals(listOf("dir"), storage.list())
        assertEquals(3L, storage.usageBytes())
        assertContentEquals(byteArrayOf(3), storage.read("dir/a.txt"))
        assertNull(storage.read("dir/missing.txt"))

        assertTrue(storage.delete("dir"))
        assertFalse(storage.delete("dir"))
        assertTrue(storage.list("dir").isEmpty())
        assertEquals(0L, storage.usageBytes())
    }

    @Test
    fun `点段路径在所有操作上都被拒绝且不会落盘`() {
        val storage = storage("plugin.a")
        val outside = temp.resolve("escape.txt").toFile()

        assertFailsWith<IllegalArgumentException> { storage.write("../escape.txt", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { storage.read("../../escape.txt") }
        assertFailsWith<IllegalArgumentException> { storage.delete("../escape.txt") }
        assertFailsWith<IllegalArgumentException> { storage.list("../") }
        assertFailsWith<IllegalArgumentException> { storage.write("/absolute.txt", byteArrayOf(1)) }

        assertFalse(outside.exists())
    }

    @Test
    fun `配额超限报错且已有内容可覆盖`() {
        val storage = storage("plugin.a", quotaBytes = 32)
        storage.write("a.bin", ByteArray(20))

        val error = assertFailsWith<StorageQuotaExceededException> {
            storage.write("b.bin", ByteArray(20))
        }
        assertTrue(error.message.orEmpty().contains("配额"), error.message)
        assertEquals(20L, storage.usageBytes())

        // Replacing an existing file with the same size is allowed: the quota counts total bytes.
        storage.write("a.bin", ByteArray(20))
        assertEquals(20L, storage.usageBytes())
    }

    @Test
    fun `原子写替换内容且不留下临时文件`() {
        val storage = storage("plugin.a")
        storage.write("state.json", "old".encodeToByteArray())

        storage.atomicWrite("state.json", "new".encodeToByteArray())

        assertContentEquals("new".encodeToByteArray(), storage.read("state.json"))
        val cacheRoot = temp.resolve("cache/plugins/plugin.a").toFile()
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty(), "cache=${cacheRoot.listFiles()?.toList()}")
    }

    @Test
    fun `原子写也会创建父目录并受配额约束`() {
        val storage = storage("plugin.a", quotaBytes = 8)
        storage.atomicWrite("nested/dir/file.bin", ByteArray(8))
        assertContentEquals(ByteArray(8), storage.read("nested/dir/file.bin"))

        assertFailsWith<StorageQuotaExceededException> {
            storage.atomicWrite("nested/dir/second.bin", ByteArray(8))
        }
        assertFalse(temp.resolve("files/plugins/plugin.a/nested/dir/second.bin").toFile().exists())
    }

    @Test
    fun `clear 删除数据与缓存目录`() {
        val storage = storage("plugin.a")
        storage.write("data.txt", byteArrayOf(1))
        storage.atomicWrite("state.txt", byteArrayOf(2))

        storage.clear()

        assertFalse(temp.resolve("files/plugins/plugin.a").toFile().exists())
        assertFalse(temp.resolve("cache/plugins/plugin.a").toFile().exists())
        assertEquals(0L, storage.usageBytes())
        assertNull(storage.read("data.txt"))
    }

    @Test
    fun `缓存目录与数据目录分离`() {
        val storage = storage("plugin.a")
        storage.atomicWrite("x.txt", byteArrayOf(1))

        val dataRoot: File = temp.resolve("files/plugins/plugin.a").toFile()
        val cacheRoot: File = temp.resolve("cache/plugins/plugin.a").toFile()
        assertTrue(dataRoot.resolve("x.txt").isFile)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty(), "cache=${cacheRoot.listFiles()?.toList()}")
    }
}
