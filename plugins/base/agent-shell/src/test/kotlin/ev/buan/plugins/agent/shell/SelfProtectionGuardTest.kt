package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.AgentToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守界的用例和拦截的用例一样重要：
 * 每一条 assertNull 都在守住"不许把只读操作用当成破坏"。
 */
class SelfProtectionGuardTest {

    private fun veto(command: String, tool: String = "run_shell"): String? = runBlocking {
        SelfProtectionGuard.beforeTool(
            AgentToolCall(id = "c1", name = tool, argumentsJson = playload(command)),
        )
    }

    private fun playload(command: String): String =
        "{\"command\":" + "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"

    @Test
    fun `killing the host is refused`() {
        assertTrue(veto("am force-stop ev.buan")!!.contains("终止 EV"))
        assertTrue(veto("pkill -f ev.buan")!!.contains("终止 EV"))
        assertTrue(veto("am kill ev.buan")!!.contains("终止 EV"))
    }

    @Test
    fun `wiping the host data directory is refused`() {
        assertTrue(veto("rm -rf /data/data/ev.buan/files")!!.contains("数据目录"))
        assertTrue(veto("chmod -R 777 /data/user/0/ev.buan")!!.contains("数据目录"))
        assertTrue(veto("pm clear ev.buan")!!.contains("数据目录"))
    }

    @Test
    fun `reading the host data directory is allowed`() {
        // 读没有危害，拦它只会让 agent 以为拿不到信息。
        assertNull(veto("cat /data/data/ev.buan/shared_prefs/ev.unsigned.xml"))
        assertNull(veto("ls -la /data/data/ev.buan"))
        assertNull(veto("grep -r foo /data/user/0/ev.buan/files"))
    }

    @Test
    fun `unrelated commands are allowed`() {
        assertNull(veto("ls -la /sdcard"))
        assertNull(veto("rm -rf /data/local/tmp/build"))
        assertNull(veto("kill 1234"))
    }

    @Test
    fun `other tools are not this guard's business`() {
        assertNull(veto("am force-stop ev.buan", tool = "file_write"))
    }

    @Test
    fun `a broken payload is left to the other layers`() {
        assertNull(
            runBlocking {
                SelfProtectionGuard.beforeTool(
                    AgentToolCall(id = "c1", name = "run_shell", argumentsJson = "not json"),
                )
            },
        )
    }

    @Test
    fun `the rejection explains what to do instead`() {
        val message = veto("am force-stop ev.buan")!!
        assertEquals(true, message.contains("用户手动操作"))
    }
}
