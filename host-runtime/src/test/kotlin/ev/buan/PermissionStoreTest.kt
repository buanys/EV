package ev.buan

import org.cordis.api.HostPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Encoding and decoding of `ev.permissions`: one wire-name set per plugin id, default deny. */
class PermissionStoreTest {

    private val preferences = FakeSharedPreferences()
    private val store = PermissionStore(preferences)

    @Test
    fun `没有记录就是未授予任何权限`() {
        assertEquals(emptySet(), store.granted("ev.buan.plugins.probe"))
        assertEquals(emptyMap(), store.grants())
    }

    @Test
    fun `授予与撤销按插件 id 隔离`() {
        store.grant("plugin.a", HostPermission.PLUGIN_STORAGE)
        store.grant("plugin.b", HostPermission.NETWORK)

        assertEquals(setOf(HostPermission.PLUGIN_STORAGE), store.granted("plugin.a"))
        assertEquals(setOf(HostPermission.NETWORK), store.granted("plugin.b"))

        store.revoke("plugin.a", HostPermission.PLUGIN_STORAGE)
        assertEquals(emptySet(), store.granted("plugin.a"))
        assertEquals(setOf(HostPermission.NETWORK), store.granted("plugin.b"))
    }

    @Test
    fun `grants 返回全部插件的授予表给安装期策略`() {
        store.grant("plugin.a", HostPermission.PLUGIN_STORAGE)
        store.grant("plugin.a", HostPermission.NETWORK)
        store.grant("plugin.b", HostPermission.CLIPBOARD)

        assertEquals(
            mapOf(
                "plugin.a" to setOf(HostPermission.PLUGIN_STORAGE, HostPermission.NETWORK),
                "plugin.b" to setOf(HostPermission.CLIPBOARD),
            ),
            store.grants(),
        )
    }

    @Test
    fun `setGranted 覆盖而不是累加`() {
        store.grant("plugin.a", HostPermission.NETWORK)
        store.setGranted("plugin.a", setOf(HostPermission.BACKGROUND))

        assertEquals(setOf(HostPermission.BACKGROUND), store.granted("plugin.a"))
    }

    @Test
    fun `存储里出现未知权限名时读取会丢弃而不是变成能力`() {
        preferences.edit()
            .putStringSet("plugin.a", linkedSetOf("NETWORK", "TELEPORT"))
            .putString("not-a-permission-set", "ignored")
            .apply()

        assertEquals(setOf(HostPermission.NETWORK), store.granted("plugin.a"))
        assertEquals(mapOf("plugin.a" to setOf(HostPermission.NETWORK)), store.grants())
    }

    @Test
    fun `权限按 wire name 存储而不是序号`() {
        store.grant("plugin.a", HostPermission.INSTALL_PACKAGES)
        val raw = preferences.getStringSet("plugin.a", mutableSetOf()).orEmpty()

        assertTrue("INSTALL_PACKAGES" in raw, "实际存储为 $raw")
    }
}
