package ev.buan.plugins.agent.tools

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bus-level tests only: a tool call reaches the host-provided storage and builds its answer with
 * `org.json`, neither of which exists on a plain JVM. Path confinement is tested where the policy
 * lives (`host-core` PluginStoragePathTest), not here.
 */
class ToolRegistryBusTest {
    @Test
    fun `specs flatten owner groups in first-registration order`() {
        val bus = ToolRegistryBus()
        bus.register("a", listOf(FakeTool(tool("a1")), FakeTool(tool("a2"))))
        bus.register("b", listOf(FakeTool(tool("b1"))))

        assertEquals(listOf("a1", "a2", "b1"), bus.specs().map { it.name })
    }

    @Test
    fun `lookup returns the registered tool`() {
        val read = FakeTool(tool("read"))
        val bus = ToolRegistryBus()
        bus.register("files", listOf(read))

        assertSame(read, bus.lookup("read"))
    }

    @Test
    fun `lookup is null for a name no tool offers`() {
        val bus = ToolRegistryBus()
        bus.register("files", listOf(FakeTool(tool("read"))))

        assertNull(bus.lookup("write"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate tool names within one owner are rejected`() {
        ToolRegistryBus().register("files", listOf(FakeTool(tool("dup")), FakeTool(tool("dup"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a name clashing with another owner is rejected`() {
        val bus = ToolRegistryBus()
        bus.register("files", listOf(FakeTool(tool("read"))))

        bus.register("shell", listOf(FakeTool(tool("read"))))
    }

    @Test
    fun `re-registering an owner replaces its group in place`() {
        val bus = ToolRegistryBus()
        bus.register("files", listOf(FakeTool(tool("read")), FakeTool(tool("write"))))
        bus.register("shell", listOf(FakeTool(tool("run"))))

        bus.register("files", listOf(FakeTool(tool("read"))))

        // The replacement drops "write" and keeps the owner's original position in the order.
        assertEquals(listOf("read", "run"), bus.specs().map { it.name })
        assertNull(bus.lookup("write"))
    }

    @Test
    fun `unregister drops the whole owner group`() {
        val bus = ToolRegistryBus()
        bus.register("files", listOf(FakeTool(tool("read"))))
        bus.register("shell", listOf(FakeTool(tool("run"))))

        bus.unregister("files")

        assertEquals(listOf("run"), bus.specs().map { it.name })
        assertNull(bus.lookup("read"))
    }

    @Test
    fun `unregistering an unknown owner is a no-op`() {
        val bus = ToolRegistryBus()
        bus.register("files", listOf(FakeTool(tool("read"))))

        bus.unregister("ghost")

        assertEquals(listOf("read"), bus.specs().map { it.name })
    }

    @Test
    fun `file tools expose the four stable capabilities`() {
        assertEquals(
            listOf("file_read", "file_write", "file_list", "file_delete"),
            StorageToolSpecs.all.map { it.name },
        )
        // The model only ever sees the spec, so every tool must describe itself and carry a schema.
        StorageToolSpecs.all.forEach { spec ->
            assertTrue(spec.name, spec.description.isNotBlank())
            assertTrue(spec.name, spec.parametersJson.trimStart().startsWith("{"))
        }
    }

    private fun tool(name: String) = ToolSpec(name = name, description = name, parametersJson = "{}")

    private class FakeTool(override val spec: ToolSpec) : Tool {
        override suspend fun invoke(argumentsJson: String): String = "ok"
    }
}
