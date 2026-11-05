package ev.buan.hostcore.trust

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 四条路径，缺一条就会在某个真实场景里出错：
 * 第一次见、没开 opt-in、开了但能力面变了、开了且能力面没变。
 */
class CapabilityTrustTest {

    private fun surface(vararg permissions: String): CapabilitySurface =
        CapabilitySurface.of(permissions.toList(), emptyList())

    private fun withDeps(dependency: String): CapabilitySurface =
        CapabilitySurface.of(emptyList(), listOf(dependency))

    @Test
    fun `first sight always asks`() {
        val outcome = CapabilityTrust.decide(
            previous = null,
            current = surface("PLUGIN_STORAGE"),
            optedIn = true,
        )
        assertEquals(TrustOutcome.Ask(TrustOutcome.Reason.FIRST_SIGHT), outcome)
    }

    @Test
    fun `opt-in is required even when nothing changed`() {
        val outcome = CapabilityTrust.decide(
            previous = surface("PLUGIN_STORAGE"),
            current = surface("PLUGIN_STORAGE"),
            optedIn = false,
        )
        assertEquals(TrustOutcome.Ask(TrustOutcome.Reason.NOT_OPTED_IN), outcome)
    }

    @Test
    fun `unchanged surface with opt-in approves`() {
        val outcome = CapabilityTrust.decide(
            previous = surface("PLUGIN_STORAGE"),
            current = surface("PLUGIN_STORAGE"),
            optedIn = true,
        )
        assertEquals(TrustOutcome.Approve, outcome)
    }

    @Test
    fun `a new permission requires confirmation`() {
        val outcome = CapabilityTrust.decide(
            previous = surface("PLUGIN_STORAGE"),
            current = surface("PLUGIN_STORAGE", "NETWORK"),
            optedIn = true,
        )
        assertEquals(TrustOutcome.Ask(TrustOutcome.Reason.SURFACE_CHANGED), outcome)
    }

    @Test
    fun `a new dependency requires confirmation`() {
        // 依赖是扩权的第二条路：它可以 lookup 别的高权限插件发布的服务。
        val outcome = CapabilityTrust.decide(
            previous = surface("PLUGIN_STORAGE"),
            current = CapabilitySurface.of(listOf("PLUGIN_STORAGE"), listOf("ev.buan.plugins.agent.api")),
            optedIn = true,
        )
        assertEquals(TrustOutcome.Ask(TrustOutcome.Reason.SURFACE_CHANGED), outcome)
    }

    @Test
    fun `dropping a permission also counts as a change`() {
        val outcome = CapabilityTrust.decide(
            previous = surface("PLUGIN_STORAGE", "NETWORK"),
            current = surface("PLUGIN_STORAGE"),
            optedIn = true,
        )
        assertEquals(TrustOutcome.Ask(TrustOutcome.Reason.SURFACE_CHANGED), outcome)
    }

    @Test
    fun `dependency order does not matter`() {
        val a = CapabilitySurface.of(emptyList(), listOf("a", "b"))
        val b = CapabilitySurface.of(emptyList(), listOf("b", "a"))
        assertEquals(a, b)
    }

    @Test
    fun `descriptions name the actual reason`() {
        assertEquals(
            "第一次见到这个插件，需要确认",
            CapabilityTrust.describe(TrustOutcome.Ask(TrustOutcome.Reason.FIRST_SIGHT)),
        )
        assertEquals(
            "能力面未变，自动通过",
            CapabilityTrust.describe(TrustOutcome.Approve),
        )
    }
}
