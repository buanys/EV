package ev.buan

import ev.buan.hostcore.storage.PluginStorageFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.api.PluginStorage

/**
 * P2-A: [PluginStorage] is gated twice, and both layers are pinned here.
 *
 * At activation the host provides the binding only to a plugin whose metadata declared
 * [HostPermission.PLUGIN_STORAGE]; an undeclared plugin's `ctx[PluginStorage.Key]` is null. At the
 * service, every data call re-checks the plugin's own grant through [PermissionService], so a
 * revoked capability fails closed on the next call instead of leaving a working file area behind.
 * `usageBytes`/`clear` stay unguarded because they are the host's management path.
 *
 * The grant state comes from the real [PermissionStore] (in-memory [SharedPreferences]) and the real
 * [PermissionServiceImpl], so these tests cover the same wiring the host uses.
 */
class PluginStoragePermissionTest {

    private val pluginId = "ev.buan.plugins.probe"
    private val store = PermissionStore(FakeSharedPreferences())
    private val permissionService = PermissionServiceImpl(pluginId, store)
    private val created = mutableListOf<File>()

    private class Fixture(val storage: PluginStorageImpl, val dataRoot: File)

    private fun fixture(permissions: PermissionService? = permissionService): Fixture {
        val base = Files.createTempDirectory("ev-storage-gate-").toFile()
        created += base
        val dataRoot = File(base, "files/plugins/$pluginId")
        return Fixture(
            PluginStorageImpl(
                pluginId,
                PluginStorageFiles(
                    dataRoot = dataRoot,
                    cacheRoot = File(base, "cache/plugins/$pluginId"),
                ),
                permissions,
            ),
            dataRoot,
        )
    }

    private fun grant() = store.grant(pluginId, HostPermission.PLUGIN_STORAGE)

    private fun revoke() = store.revoke(pluginId, HostPermission.PLUGIN_STORAGE)

    private fun cleanup() {
        created.forEach { it.deleteRecursively() }
        created.clear()
    }

    @Test
    fun `未授权时数据调用全部抛出 PERMISSION_DENIED`() {
        try {
            val fixture = fixture()

            val readError = assertFailsWith<SecurityException> { fixture.storage.read("state.txt") }
            assertContains(readError.message.orEmpty(), "PERMISSION_DENIED")
            assertContains(readError.message.orEmpty(), "PLUGIN_STORAGE")
            assertContains(readError.message.orEmpty(), pluginId)

            assertFailsWith<SecurityException> { fixture.storage.write("state.txt", byteArrayOf(1)) }
            assertFailsWith<SecurityException> { fixture.storage.atomicWrite("state.txt", byteArrayOf(1)) }
            assertFailsWith<SecurityException> { fixture.storage.delete("state.txt") }
            assertFailsWith<SecurityException> { fixture.storage.list("") }

            // The gate runs before any I/O: a refused write must not create the file.
            assertFalse(File(fixture.dataRoot, "state.txt").exists())
        } finally {
            cleanup()
        }
    }

    @Test
    fun `授予后读写可用，撤销立即拒绝，再授予恢复`() {
        try {
            grant()
            val fixture = fixture()

            fixture.storage.write("state.txt", "v1".encodeToByteArray())
            assertContentEquals("v1".encodeToByteArray(), fixture.storage.read("state.txt"))
            fixture.storage.atomicWrite("atomic.txt", "v2".encodeToByteArray())
            assertContentEquals("v2".encodeToByteArray(), fixture.storage.read("atomic.txt"))
            assertEquals(listOf("atomic.txt", "state.txt"), fixture.storage.list(""))
            assertTrue(fixture.storage.delete("atomic.txt"))

            revoke()
            assertFailsWith<SecurityException> { fixture.storage.read("state.txt") }

            grant()
            assertContentEquals("v1".encodeToByteArray(), fixture.storage.read("state.txt"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun `usageBytes 与 clear 不受权限影响`() {
        try {
            grant()
            val fixture = fixture()
            fixture.storage.write("data.bin", ByteArray(5))
            assertEquals(5L, fixture.storage.usageBytes())

            revoke()

            // Management accounting and「清理」must work while the plugin cannot use the API.
            assertEquals(5L, fixture.storage.usageBytes())
            fixture.storage.clear()
            assertEquals(0L, fixture.storage.usageBytes())
            assertFalse(File(fixture.dataRoot, "data.bin").exists())
        } finally {
            cleanup()
        }
    }

    @Test
    fun `宿主管理路径的实例不带权限服务`() {
        try {
            val fixture = fixture(permissions = null)

            // HostRuntime.storageUsage/clearStorage pass no PermissionService; the instance is not
            // handed to a plugin, so usage accounting stays independent from its grants.
            fixture.storage.write("host-managed.txt", byteArrayOf(1, 2, 3))
            assertEquals(3L, fixture.storage.usageBytes())
            fixture.storage.clear()
            assertEquals(0L, fixture.storage.usageBytes())
        } finally {
            cleanup()
        }
    }

    @Test
    fun `未声明 PLUGIN_STORAGE 的插件拿不到 storage`() = runBlocking {
        try {
            val scope = Context().isolate(PluginStorage.Key)
            var constructed = false

            val provided = providePluginStorageIfDeclared(
                scope,
                requested = setOf(HostPermission.NETWORK),
            ) {
                constructed = true
                fixture().storage
            }

            assertFalse(provided)
            assertFalse(constructed, "未声明时连实现都不应构造（构造需要 Android context）")
            assertNull(scope[PluginStorage.Key])
        } finally {
            cleanup()
        }
    }

    @Test
    fun `声明 PLUGIN_STORAGE 的插件拿到经过权限门的 storage`() = runBlocking {
        try {
            grant()
            val fixtureScope = Context().isolate(PluginStorage.Key)
            val fixture = fixture()

            val provided = providePluginStorageIfDeclared(
                fixtureScope,
                requested = setOf(HostPermission.PLUGIN_STORAGE),
            ) { fixture.storage }

            assertTrue(provided)
            assertSame(fixture.storage, fixtureScope[PluginStorage.Key])

            // The provided instance is the gated one: usable now, refuses the next call after a revoke.
            val visible = fixtureScope[PluginStorage.Key]
            visible!!.write("state.txt", "v1".encodeToByteArray())
            assertContentEquals("v1".encodeToByteArray(), visible.read("state.txt"))
            revoke()
            val denied = assertFailsWith<SecurityException> { visible.read("state.txt") }
            assertContains(denied.message.orEmpty(), "PERMISSION_DENIED")
        } finally {
            cleanup()
        }
    }
}
