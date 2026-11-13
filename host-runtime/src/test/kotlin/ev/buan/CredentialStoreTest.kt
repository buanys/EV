package ev.buan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 凭据存放地：一个插件一份 JSON，文件在宿主目录里。
 *
 * 这些用例钉的是"凭据的边界"而不是文件格式：谁能看见谁、空值怎么处理、坏了会不会连累别人。
 * 文件格式随时可以换，边界不能。
 */
class CredentialStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(root: File = temp.newFolder("secrets")) = CredentialStore(root)

    @Test
    fun `write then read returns the value`() {
        val store = store()
        store.write("plugin.a", "provider:openai", "sk-123")
        assertEquals("sk-123", store.read("plugin.a", "provider:openai"))
    }

    @Test
    fun `names lists keys without exposing values`() {
        val store = store()
        store.write("plugin.a", "zeta", "1")
        store.write("plugin.a", "alpha", "2")
        assertEquals(listOf("alpha", "zeta"), store.names("plugin.a"))
    }

    @Test
    fun `plugins are isolated from each other`() {
        val store = store()
        store.write("plugin.a", "same-name", "a-secret")
        store.write("plugin.b", "same-name", "b-secret")

        assertEquals("a-secret", store.read("plugin.a", "same-name"))
        assertEquals("b-secret", store.read("plugin.b", "same-name"))
        assertEquals(listOf("same-name"), store.names("plugin.a"))
    }

    @Test
    fun `blank values are rejected so erase stays the only way to remove`() {
        val store = store()
        store.write("plugin.a", "k", "v")
        runCatching { store.write("plugin.a", "k", "") }.also {
            assertTrue("空值应被拒绝", it.isFailure)
        }
        assertEquals("v", store.read("plugin.a", "k"))
    }

    @Test
    fun `erase removes one key and leaves the rest`() {
        val store = store()
        store.write("plugin.a", "keep", "1")
        store.write("plugin.a", "drop", "2")

        store.erase("plugin.a", "drop")

        assertEquals("1", store.read("plugin.a", "keep"))
        assertNull(store.read("plugin.a", "drop"))
    }

    @Test
    fun `erasing the last key removes the file entirely`() {
        val root = temp.newFolder("secrets")
        val store = CredentialStore(root)
        store.write("plugin.a", "only", "1")

        store.erase("plugin.a", "only")

        assertEquals(0, root.listFiles().orEmpty().count { it.name.endsWith(".json") })
    }

    @Test
    fun `eraseAll drops the plugin file`() {
        val store = store()
        store.write("plugin.a", "k1", "1")
        store.write("plugin.a", "k2", "2")

        store.eraseAll("plugin.a")

        assertEquals(emptyList<String>(), store.names("plugin.a"))
    }

    @Test
    fun `a corrupted file reads as no credentials instead of throwing`() {
        val root = temp.newFolder("secrets")
        val store = CredentialStore(root)
        store.write("plugin.a", "k", "v")
        // 把文件写坏：一个坏文件不该让插件激活失败，最坏情况是用户重填一次。
        root.listFiles()!!.single { it.name.endsWith(".json") }.writeText("{not json")

        assertNull(store.read("plugin.a", "k"))
        assertEquals(emptyList<String>(), store.names("plugin.a"))
    }

    @Test
    fun `copying a provider keeps the old value until overwritten`() {
        val store = store()
        store.write("plugin.a", "provider:x", "old")
        store.write("plugin.a", "provider:x", "new")
        assertEquals("new", store.read("plugin.a", "provider:x"))
    }
}
