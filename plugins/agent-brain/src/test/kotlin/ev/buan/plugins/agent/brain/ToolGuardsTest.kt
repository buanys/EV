package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * guard 注册表的语义，全部围绕"不能变成放行"这一条：
 * 注册顺序就是询问顺序、第一条拒绝生效、撤销只影响自己、坏插件按拒绝算。
 */
class ToolGuardsTest {

    private fun call(name: String = "run_shell") = AgentToolCall("call_1", name, "{}")

    @Test
    fun `no guards means no rejection`() = runBlocking {
        assertNull(ToolGuards().vet(call()))
    }

    @Test
    fun `a guard's rejection is returned verbatim`() = runBlocking {
        val guards = ToolGuards()
        guards.register { "路径不在工作区" }

        assertEquals("路径不在工作区", guards.vet(call()))
    }

    @Test
    fun `the first rejection wins, later guards are not asked`() = runBlocking {
        val guards = ToolGuards()
        var secondAsked = false
        guards.register { "第一条" }
        guards.register {
            secondAsked = true
            "第二条"
        }

        assertEquals("第一条", guards.vet(call()))
        assertEquals(false, secondAsked)
    }

    @Test
    fun `a guard that declines lets the next one decide`() = runBlocking {
        val guards = ToolGuards()
        guards.register { null }
        guards.register { "第二条不放行" }

        assertEquals("第二条不放行", guards.vet(call()))
    }

    @Test
    fun `a throwing guard rejects instead of allowing`() = runBlocking {
        val guards = ToolGuards()
        guards.register { error("插件炸了") }

        val rejection = guards.vet(call())

        assertEquals(true, rejection != null && rejection.contains("安全规则插件出错"))
    }

    @Test
    fun `cancel removes only the registration it came from`() = runBlocking {
        val guards = ToolGuards()
        val first = guards.register { "第一条" }
        guards.register { null }

        first()

        assertNull(guards.vet(call()))
    }

    @Test
    fun `cancelling twice is harmless`() = runBlocking {
        val guards = ToolGuards()
        val cancel = guards.register { "第一条" }
        guards.register { "第二条" }

        cancel()
        cancel()

        assertEquals("第二条", guards.vet(call()))
    }
}
