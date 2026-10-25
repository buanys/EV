package ev.buan.hostcore.storage

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Pure relative-path rules: canonical form only, no traversal, no absolute paths. */
class PluginStoragePathTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `合法相对路径原样返回`() {
        assertEquals("a.json", PluginStoragePath.normalize("a.json"))
        assertEquals("dir/sub/file.bin", PluginStoragePath.normalize("dir/sub/file.bin"))
        assertEquals("a_b-c.1", PluginStoragePath.normalize("a_b-c.1"))
    }

    @Test
    fun `绝对路径被拒绝`() {
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("/etc/passwd") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("C:/temp/x") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("\\windows\\x") }
    }

    @Test
    fun `点段与点点段被拒绝`() {
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("..") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize(".") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("../escape") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a/../../x") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a/./b") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a/..") }
    }

    @Test
    fun `非规范形式被拒绝`() {
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a//b") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a/") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a\\\\b") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a\nb") }
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.normalize("a\u0000b") }
    }

    @Test
    fun `resolve 把文件钉在 root 内`() {
        val root = temp.resolve("root").toFile()
        val resolved = PluginStoragePath.resolve(root, "dir/file.txt")

        assertTrue(resolved.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertFailsWith<IllegalArgumentException> { PluginStoragePath.resolve(root, "../outside.txt") }
    }
}
