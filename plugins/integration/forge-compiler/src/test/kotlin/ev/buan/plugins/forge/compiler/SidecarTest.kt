package ev.buan.plugins.forge.compiler

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这里测的是最容易静默出错的契约细节：sidecar 的键名。
 *
 * 宿主读 jar sidecar 用的是 `permissions` / `dependencies`
 * （`PermissionMetadata.JAR_SIDECAR_KEY` / `DependencyMetadata.JAR_SIDECAR_KEY`），
 * 而不是 APK 的 `cordis.*` 变体。写错不会报错——插件照装，声明照丢。
 */
class SidecarTest {

    private val request = ForgeRequest(
        pluginId = "taotie.forged",
        entryClass = "taotie.forged.ForgedPlugin",
        fileName = "Forged.kt",
        source = "class ForgedPlugin",
        version = "1.2.3",
        permissions = listOf("PLUGIN_STORAGE"),
        dependencies = listOf("ev.buan.plugins.agent.tools"),
    )

    @Test
    fun `sidecar 用宿主认的键名`() {
        val json = JSONObject(sidecarJson(request))
        assertEquals("taotie.forged", json.getString("id"))
        assertEquals("1.2.3", json.getString("version"))
        assertEquals("taotie.forged.ForgedPlugin", json.getString("entryClass"))
        assertEquals(FORGED_API_VERSION, json.getInt("apiVersion"))
        assertEquals("PLUGIN_STORAGE", json.getJSONArray("permissions").getString(0))
        assertEquals("ev.buan.plugins.agent.tools", json.getJSONArray("dependencies").getString(0))

        // APK 才用 cordis.* 前缀；写在 jar sidecar 里等于没写。
        assertTrue(!json.has("cordis.permissions"))
        assertTrue(!json.has("cordis.dependencies"))
    }

    @Test
    fun `没声明就是空数组而不是缺键`() {
        val json = JSONObject(sidecarJson(request.copy(permissions = emptyList(), dependencies = emptyList())))
        assertNotNull(json.getJSONArray("permissions"))
        assertNotNull(json.getJSONArray("dependencies"))
        assertEquals(0, json.getJSONArray("permissions").length())
    }

    @Test
    fun `插件 id 形状不对提前失败`() {
        assertNotNull(pluginIdProblem(""))
        assertNotNull(pluginIdProblem("taotie"))
        assertNotNull(pluginIdProblem("taotie forged"))
        assertNull(pluginIdProblem("taotie.forged"))
        assertNull(pluginIdProblem("ev.buan.plugins.forge.compiler"))
    }

    @Test
    fun `入口类形状不对提前失败`() {
        assertNotNull(entryClassProblem(""))
        assertNotNull(entryClassProblem("Forged Plugin"))
        assertNull(entryClassProblem("taotie.forged.ForgedPlugin"))
    }
}
