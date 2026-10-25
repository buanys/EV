package ev.buan

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Fiber
import org.cordis.FiberState
import org.cordis.Plugin
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T06 spike, kept as a regression test: the per-plugin permission provider must be reachable from
 * that plugin's descendants and from nowhere else, without changing the shared Registry.
 *
 * The first wiring attempt - `cordis.extend { isolate(Key) }.provide(...)` - installed the binding on
 * the root Fiber's service snapshot. The kernel resolver then hands the *last* provided instance to
 * any plugin that reads the key without declaring `inject` in [Plugin.inject], i.e. one plugin could
 * observe another plugin's [PermissionService]. The host now gives each plugin a dedicated scope
 * Fiber ([HostRuntime.scopePlugin]) and installs the provider there; the cases below pin that.
 */
class PermissionScopeTest {

    private class Impl(private val label: String) : PermissionService {
        override fun isGranted(permission: HostPermission): Boolean = false

        override fun require(permission: HostPermission) {
            throw SecurityException("PERMISSION_DENIED $label")
        }

        override fun toString(): String = "impl:$label"
    }

    @Test
    fun `每个插件只解析到自己 scope 上的 PermissionService`() = runBlocking {
        val root = Context()
        val implA = Impl("a")
        val implB = Impl("b")
        val (hostA, scopeA) = hostScope(root, "a", implA)
        val (hostB, scopeB) = hostScope(root, "b", implB)

        assertNull(root[PermissionService.Key], "根 Context 不得解析到某个插件的授权服务")

        val consumerA = Consumer()
        val consumerB = Consumer()
        val fiberA = scopeA.plugin(consumerA, Unit)
        val fiberB = scopeB.plugin(consumerB, Unit)
        withTimeout(5_000) { fiberA.await(); fiberB.await() }

        assertSame(implA, consumerA.seen)
        assertSame(implB, consumerB.seen)

        withTimeout(5_000) { fiberA.dispose(); fiberB.dispose() }
        withTimeout(5_000) { hostA.dispose(); hostB.dispose() }
    }

    @Test
    fun `未注入的插件无法从兄弟 scope 读到授权服务`() = runBlocking {
        val root = Context()
        val (hostA, _) = hostScope(root, "a", Impl("a"))

        // A plugin that never declares the dependency must not reach the service through the root
        // Fiber snapshot; the resolver has to refuse with the "without inject" error.
        val siblingScope = root.isolate(PermissionService.Key)
        val sibling = Consumer(injectService = false)
        val siblingFiber = siblingScope.plugin(sibling, Unit)
        withTimeout(5_000) { siblingFiber.await() }

        assertNull(sibling.seen)
        assertContains(sibling.failure?.message.orEmpty(), "without inject")

        withTimeout(5_000) { siblingFiber.dispose() }
        withTimeout(5_000) { hostA.dispose() }
    }

    @Test
    fun `per-plugin scope 不改变共享 Registry 的分组`() = runBlocking {
        val root = Context()
        val (hostA, scopeA) = hostScope(root, "a", Impl("a"))
        val (hostB, scopeB) = hostScope(root, "b", Impl("b"))

        // One plugin instance, two scopes: the Registry must still group both Fibers under one
        // runtime, which is what hot reload and service-change fan-out rely on.
        val shared = Consumer()
        val first = scopeA.plugin(shared, Unit)
        val second = scopeB.plugin(shared, Unit)
        withTimeout(5_000) { first.await(); second.await() }

        assertSame(root.registry, scopeA.registry)
        assertSame(root.registry, scopeB.registry)
        assertEquals(1, root.registry.values().count { it.plugin === shared })
        assertTrue(root.registry.has(shared))
        assertEquals(FiberState.ACTIVE, first.state)
        assertEquals(FiberState.ACTIVE, second.state)

        withTimeout(5_000) { first.dispose(); second.dispose() }
        withTimeout(5_000) { hostA.dispose(); hostB.dispose() }
    }

    @Test
    fun `撤销状态由服务实时读取`() = runBlocking {
        val store = PermissionStore(FakeSharedPreferences())
        val service = PermissionServiceImpl("plugin.a", store)

        store.grant("plugin.a", HostPermission.PLUGIN_STORAGE)
        assertTrue(service.isGranted(HostPermission.PLUGIN_STORAGE))
        store.revoke("plugin.a", HostPermission.PLUGIN_STORAGE)
        assertEquals(false, service.isGranted(HostPermission.PLUGIN_STORAGE))
    }

    // ---------------------------------------------------------------- helpers

    /** Mirrors HostRuntime.activate: a host Fiber owns the provider, the plugin lives below it. */
    private suspend fun hostScope(
        root: Context,
        id: String,
        service: PermissionService,
    ): Pair<Fiber<Unit>, Context> {
        val host = root.plugin(plugin<Unit>(name = "host/scope/$id") { _, _ -> }, Unit)
        withTimeout(5_000) { host.await() }
        val scope = host.ctx.isolate(PermissionService.Key)
        scope.provide(PermissionService.Key, service).awaitReady()
        return host to scope
    }

    private class Consumer(private val injectService: Boolean = true) : Plugin<Unit> {
        var seen: Any? = null
        var failure: Throwable? = null

        override val inject: Dependencies =
            if (injectService) dependencies(PermissionService.Key) else Dependencies.Empty

        override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
            runCatching { ctx[PermissionService.Key] }
                .onSuccess { seen = it }
                .onFailure { failure = it }
        }
    }
}
