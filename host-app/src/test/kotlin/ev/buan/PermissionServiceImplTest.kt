package ev.buan

import org.cordis.api.HostPermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The plugin-facing service: it reports the plugin's own live grants and fails closed on require.
 */
class PermissionServiceImplTest {

    private val store = PermissionStore(FakeSharedPreferences())
    private val service = PermissionServiceImpl("ev.buan.plugins.probe", store)

    @Test
    fun `只看到自己的授予`() {
        store.grant("ev.buan.plugins.other", HostPermission.NETWORK)

        assertEquals(false, service.isGranted(HostPermission.NETWORK))
        store.grant("ev.buan.plugins.probe", HostPermission.PLUGIN_STORAGE)
        assertEquals(true, service.isGranted(HostPermission.PLUGIN_STORAGE))
    }

    @Test
    fun `require 在已授予时放行`() {
        store.grant("ev.buan.plugins.probe", HostPermission.PLUGIN_STORAGE)
        service.require(HostPermission.PLUGIN_STORAGE)
    }

    @Test
    fun `require 在未授予时抛 PERMISSION_DENIED`() {
        val error = assertFailsWith<SecurityException> { service.require(HostPermission.PLUGIN_STORAGE) }

        assertContains(error.message.orEmpty(), "PERMISSION_DENIED")
        assertContains(error.message.orEmpty(), "PLUGIN_STORAGE")
        assertContains(error.message.orEmpty(), "ev.buan.plugins.probe")
    }

    @Test
    fun `撤销立即生效而不需要重新激活`() {
        store.grant("ev.buan.plugins.probe", HostPermission.PLUGIN_STORAGE)
        store.revoke("ev.buan.plugins.probe", HostPermission.PLUGIN_STORAGE)

        assertEquals(false, service.isGranted(HostPermission.PLUGIN_STORAGE))
        assertFailsWith<SecurityException> { service.require(HostPermission.PLUGIN_STORAGE) }
    }
}
