package ev.buan.hostcore.storage

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Directory layout: one safe directory per plugin id, plus uninstall pruning. */
class PluginStorageLayoutTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `普通插件 id 直接作为目录名`() {
        assertEquals("ev.buan.plugins.probe", PluginStorageLayout.directoryName("ev.buan.plugins.probe"))
    }

    @Test
    fun `危险插件 id 被转义且不会越界`() {
        for (id in listOf("../escape", "..", ".", "a/b", "a\\b", "C:", "with space")) {
            val name = PluginStorageLayout.directoryName(id)
            assertFalse('/' in name, "id=$id -> $name")
            assertFalse('\\' in name, "id=$id -> $name")
            assertFalse(name == "." || name == "..", "id=$id -> $name")

            val root = temp.resolve("files").toFile()
            val resolved = File(File(root, PluginStorageLayout.ROOT_DIRECTORY), name)
            assertTrue(
                resolved.canonicalPath.startsWith(root.canonicalPath + File.separator),
                "id=$id -> $name",
            )
        }
    }

    @Test
    fun `数据与缓存根落在 files plugins 与 cache plugins 下`() {
        val filesDir = temp.resolve("files").toFile()
        val cacheDir = temp.resolve("cache").toFile()

        val data = PluginStorageLayout.dataRoot(filesDir, "plugin.a")
        val cache = PluginStorageLayout.cacheRoot(cacheDir, "plugin.a")

        assertEquals(File(File(filesDir, "plugins"), "plugin.a"), data)
        assertEquals(File(File(cacheDir, "plugins"), "plugin.a"), cache)
    }

    @Test
    fun `pruneUnknown 只删除不再安装的插件目录`() {
        val root = temp.resolve("plugins").toFile()
        File(root, "kept.plugin").mkdirs()
        File(root, "removed.plugin").apply { mkdirs(); resolve("file.txt").writeText("x") }
        File(root, "not-a-directory.txt").writeText("ignore")

        val removed = PluginStorageLayout.pruneUnknown(root, setOf("kept.plugin"))

        assertEquals(listOf("removed.plugin"), removed)
        assertTrue(File(root, "kept.plugin").isDirectory)
        assertFalse(File(root, "removed.plugin").exists())
        assertTrue(File(root, "not-a-directory.txt").isFile)
    }

    @Test
    fun `pruneUnknown 对转义后的安装 id 同样保留`() {
        val root = temp.resolve("plugins").toFile()
        val encoded = PluginStorageLayout.directoryName("../weird")
        File(root, encoded).mkdirs()

        val removed = PluginStorageLayout.pruneUnknown(root, setOf("../weird"))

        assertTrue(removed.isEmpty())
        assertTrue(File(root, encoded).isDirectory)
    }
}
