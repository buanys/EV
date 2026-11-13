package ev.buan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidesSpecTest {

    @Test
    fun `service and slot items are accepted`() {
        val parsed = ProvidesSpec.parse(listOf("service:agent/service", "slot:chat.composer"))

        assertEquals(setOf("service:agent/service", "slot:chat.composer"), parsed.items)
        assertTrue(parsed.rejected.isEmpty())
        assertTrue(parsed.isLazy)
    }

    @Test
    fun `empty declaration means must stay resident`() {
        val parsed = ProvidesSpec.parse(emptyList())

        assertTrue(parsed.items.isEmpty())
        assertFalse(parsed.isLazy)
    }

    @Test
    fun `unknown kind is dropped instead of rejected`() {
        val parsed = ProvidesSpec.parse(listOf("command:deploy", "service:agent/service"))

        assertEquals(setOf("service:agent/service"), parsed.items)
        assertEquals(listOf("command:deploy"), parsed.rejected)
    }

    @Test
    fun `malformed entries are reported`() {
        val parsed = ProvidesSpec.parse(
            listOf(
                "service",           // 没有冒号
                ":agent",            // 缺 kind
                "service:",          // 缺 name
                "service:has space", // 非法字符
                "service:ok/name",   // 合法，斜杠是 ServiceKey 的分段符
            ),
        )

        assertEquals(setOf("service:ok/name"), parsed.items)
        assertEquals(listOf("service", ":agent", "service:", "service:has space"), parsed.rejected)
    }

    @Test
    fun `duplicates collapse keeping first order`() {
        val parsed = ProvidesSpec.parse(
            listOf("service:b", "service:a", "service:b", " service:a "),
        )

        assertEquals(setOf("service:b", "service:a"), parsed.items)
        assertEquals(listOf("service:b", "service:a"), parsed.items.toList())
    }

    @Test
    fun `apk meta value splits on commas`() {
        assertEquals(
            listOf("service:agent/service", "slot:chat.composer"),
            ProvidesSpec.splitMetaValue(" service:agent/service , slot:chat.composer ,"),
        )
        assertTrue(ProvidesSpec.splitMetaValue(null).isEmpty())
    }

    @Test
    fun `item builders match parser output`() {
        val built = ProvidesSpec.serviceItem("agent/service")
        assertEquals("service:agent/service", built)
        assertEquals(setOf(built), ProvidesSpec.parse(listOf(built)).items)

        val slot = ProvidesSpec.slotItem("chat.composer")
        assertEquals("slot:chat.composer", slot)
        assertEquals(setOf(slot), ProvidesSpec.parse(listOf(slot)).items)
    }
}
