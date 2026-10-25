package ev.buan.plugins.eta.ui.ui

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the read-only tools panel's state machine and its text shaping: the two "empty" states must
 * stay distinguishable, and a long schema must not be allowed to stretch a row.
 */
class EtaToolsScreenTest {

    @Test
    fun `no registry is the missing state`() {
        assertEquals(ToolsState.Missing, readTools { null })
    }

    @Test
    fun `a registry without tools is the empty state, not the missing one`() {
        assertEquals(ToolsState.Empty, readTools { FakeRegistry(emptyList()) })
    }

    @Test
    fun `specs keep their registration order`() {
        val specs = listOf(spec("file_read"), spec("file_write"), spec("file_list"))

        assertEquals(ToolsState.Loaded(specs), readTools { FakeRegistry(specs) })
    }

    @Test
    fun `a failing lookup is reported instead of looking uninstalled`() {
        val state = readTools { error("注册表坏了") }

        assertEquals(ToolsState.Failed("注册表坏了"), state)
    }

    @Test
    fun `a failing specs call is reported instead of crashing the page`() {
        val registry = object : ToolRegistry {
            override fun specs(): List<ToolSpec> = error("读取失败")
            override fun lookup(name: String): Tool? = null
            override fun register(owner: String, tools: List<Tool>) = Unit
            override fun unregister(owner: String) = Unit
        }

        assertEquals(ToolsState.Failed("读取失败"), readTools { registry })
    }

    @Test
    fun `a blank description has no summary line`() {
        assertNull(descriptionSummary("   "))
    }

    @Test
    fun `a long description is clipped`() {
        val summary = descriptionSummary("描".repeat(200))

        assertNotNull(summary)
        assertEquals(120, summary!!.length)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `a normal description is kept verbatim`() {
        assertEquals("读取文件", descriptionSummary(" 读取文件 "))
    }

    @Test
    fun `the preview is one line and empty schemas have none`() {
        assertNull(parameterPreview(""))
        assertNull(parameterPreview("   "))
        assertNull(parameterPreview("{}"))
        assertNull(parameterPreview(" { } "))

        val preview = parameterPreview(
            """
            {
              "type": "object",
              "properties": {"path": {"type": "string"}}
            }
            """.trimIndent(),
        )

        assertNotNull(preview)
        assertFalse(preview!!.contains('\n'))
        assertTrue(preview.contains("\"properties\""))
    }

    @Test
    fun `an unparseable schema is still shown`() {
        // A tool publishing a broken schema is exactly what this panel should reveal.
        assertEquals("{ not json", parameterPreview("{ not json"))
    }

    @Test
    fun `details pretty print the schema`() {
        val details = parameterDetails("""{"type":"object","properties":{"path":{"type":"string"}}}""")

        assertTrue(details.contains('\n'))
        assertTrue(details.contains("\"path\""))
        assertFalse(details.endsWith("…"))
    }

    @Test
    fun `details fall back to the raw text and truncate pathological input`() {
        assertEquals("{ not json", parameterDetails("{ not json"))

        val huge = (1..100).joinToString(",", "{", "}") { "\"key$it\":1" }
        val details = parameterDetails(huge)

        assertEquals(61, details.lines().size)
        assertTrue(details.endsWith("…"))
    }

    private fun spec(name: String) = ToolSpec(
        name = name,
        description = "$name 的描述",
        parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
    )
}

private class FakeRegistry(private val specs: List<ToolSpec>) : ToolRegistry {
    override fun specs(): List<ToolSpec> = specs

    override fun lookup(name: String): Tool? = null

    override fun register(owner: String, tools: List<Tool>) = Unit

    override fun unregister(owner: String) = Unit
}
