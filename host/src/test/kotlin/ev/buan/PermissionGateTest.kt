package ev.buan

import org.cordis.api.HostPermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The activation-time decision: declared minus granted, surfaced under a stable code. */
class PermissionGateTest {

    @Test
    fun `缺少的权限按声明顺序列出`() {
        val missing = PermissionGate.missing(
            requested = setOf(HostPermission.PLUGIN_STORAGE, HostPermission.NETWORK),
            granted = setOf(HostPermission.PLUGIN_STORAGE),
        )

        assertEquals(setOf(HostPermission.NETWORK), missing)
    }

    @Test
    fun `全部授予时没有缺失`() {
        assertTrue(
            PermissionGate.missing(
                requested = setOf(HostPermission.BACKGROUND),
                granted = setOf(HostPermission.BACKGROUND),
            ).isEmpty(),
        )
    }

    @Test
    fun `拒绝文案带 PERMISSION_DENIED 代码且只列缺失项`() {
        val message = PermissionGate.denialMessage(
            pluginId = "ev.buan.plugins.probe",
            missing = setOf(HostPermission.NETWORK, HostPermission.PLUGIN_STORAGE),
        )

        assertContains(message, "PERMISSION_DENIED")
        assertContains(message, "ev.buan.plugins.probe")
        assertContains(message, "NETWORK")
        assertContains(message, "PLUGIN_STORAGE")
        assertContains(message, "授予")
    }

    /**
     * The management-page sequence, at the level HostRuntime uses: default deny, grant to recover,
     * revoke to stop again. Kept here because HostRuntime itself needs an Android device.
     */
    @Test
    fun `默认拒绝 撤销拒绝 授权恢复`() {
        val store = PermissionStore(FakeSharedPreferences())
        val pluginId = "ev.buan.plugins.probe"
        val requested = setOf(HostPermission.PLUGIN_STORAGE)

        // Fresh install: nothing granted yet, activation must be refused.
        assertTrue(PermissionGate.missing(requested, store.granted(pluginId)).isNotEmpty())

        store.grant(pluginId, HostPermission.PLUGIN_STORAGE)
        assertTrue(PermissionGate.missing(requested, store.granted(pluginId)).isEmpty())

        store.revoke(pluginId, HostPermission.PLUGIN_STORAGE)
        assertTrue(PermissionGate.missing(requested, store.granted(pluginId)).isNotEmpty())

        store.grant(pluginId, HostPermission.PLUGIN_STORAGE)
        assertTrue(PermissionGate.missing(requested, store.granted(pluginId)).isEmpty())
    }
}

