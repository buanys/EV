package ev.buan.hostcore.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The id becomes a file name, so these cases are a path-traversal boundary, not cosmetics: every
 * rejected shape is something that would add a path segment or hide the file name.
 */
class PluginIdTest {

    @Test
    fun `正常插件 id 合法`() {
        for (id in listOf("ev.buan.plugins.probe", "a", "A1._-", "a.b_c-d", "0plugin")) {
            assertTrue(PluginId.isValid(id), "id=$id")
            assertEquals(id, PluginId.requireValid(id))
        }
    }

    @Test
    fun `长度边界按 loader 规则`() {
        val max = "a".repeat(PluginId.MAX_LENGTH)
        val tooLong = "a".repeat(PluginId.MAX_LENGTH + 1)

        assertTrue(PluginId.isValid(max))
        assertFalse(PluginId.isValid(tooLong))
    }

    @Test
    fun `路径分隔符与驱动器前缀被拒绝`() {
        for (id in listOf("a/b", "../escape", "a/../b", "a\\b", "C:evil", "a:b", "/abs", "a b", "a\nb", "a\u0000b")) {
            assertFalse(PluginId.isValid(id), "id=$id")
            assertFailsWith<IllegalArgumentException> { PluginId.requireValid(id) }
        }
    }

    @Test
    fun `空值与点开头被拒绝`() {
        for (id in listOf("", ".", "..", ".hidden", "-leading", "_leading")) {
            assertFalse(PluginId.isValid(id), "id=$id")
        }
    }

    @Test
    fun `非 ASCII 与超长 id 被拒绝`() {
        for (id in listOf("插件", "a".repeat(PluginId.MAX_LENGTH + 1))) {
            assertFalse(PluginId.isValid(id), "id=$id")
        }
    }

    @Test
    fun `文件名与暂存名不会包含路径分隔符`() {
        for (id in listOf("ev.buan.plugins.probe", "a", "A1._-")) {
            val name = PluginId.fileName(id)
            val staging = PluginId.stagingFileName(id)

            assertEquals("$id.apk", name)
            assertEquals(".$id.installing", staging)
            assertFalse('/' in name || '\\' in name, "name=$name")
            assertFalse('/' in staging || '\\' in staging, "staging=$staging")
        }
    }

    @Test
    fun `非法 id 不会产生文件名`() {
        for (id in listOf("../escape", "a/b", "", "..")) {
            assertFailsWith<IllegalArgumentException> { PluginId.fileName(id) }
            assertFailsWith<IllegalArgumentException> { PluginId.stagingFileName(id) }
        }
    }

    @Test
    fun `校验后的文件名钉在私有目录内`() {
        val root = File("mods")
        for (id in listOf("ev.buan.plugins.probe", "a")) {
            val file = File(root, PluginId.fileName(id))
            assertEquals(root, file.parentFile)
        }
    }
}
