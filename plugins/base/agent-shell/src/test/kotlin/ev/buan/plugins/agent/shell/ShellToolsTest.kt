package ev.buan.plugins.agent.shell

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tool-logic tests over a fake shell (argument validation, timeout capping, result encoding),
 * plus an end-to-end pass of the real runner against `sh` when the test machine has one -
 * the rooted-phone path differs only in the binary name.
 */
class ShellToolsTest {

    @Test
    fun `run_shell is declared dangerous with a valid schema`() {
        val spec = ShellTools.runShellSpec

        assertTrue(spec.dangerous)
        val schema = JSONObject(spec.parametersJson)
        assertEquals("command", schema.getJSONArray("required").getString(0))
        assertTrue(schema.getJSONObject("properties").has("timeout_seconds"))
    }

    @Test
    fun `a blank or missing command is an error result, not an exception`() {
        val tool = tool(FakeRunner())

        assertTrue(invoke(tool, "{}").getString("error").contains("command"))
        assertTrue(invoke(tool, """{"command":"   "}""").getString("error").contains("command"))
        assertTrue(invoke(tool, "not json").getString("error").contains("JSON"))
    }

    @Test
    fun `the timeout is clamped to the documented ceiling`() {
        val runner = FakeRunner()
        val tool = tool(runner)

        invoke(tool, """{"command":"x","timeout_seconds":9999}""")
        assertEquals(ShellTools.MAX_TIMEOUT_SECONDS * 1_000L, runner.lastTimeoutMillis)

        invoke(tool, """{"command":"x","timeout_seconds":-5}""")
        assertEquals(1_000L, runner.lastTimeoutMillis)

        invoke(tool, """{"command":"x"}""")
        assertEquals(ShellTools.DEFAULT_TIMEOUT_SECONDS * 1_000L, runner.lastTimeoutMillis)
    }

    @Test
    fun `a finished command reports exit code and both streams`() {
        val tool = tool(
            FakeRunner(RawShellResult(exitCode = 3, stdout = "out", stderr = "err", timedOut = false)),
        )

        val json = invoke(tool, """{"command":"false"}""")
        assertEquals(3, json.getInt("exit"))
        assertEquals(false, json.getBoolean("timedOut"))
        assertEquals("out", json.getString("stdout"))
        assertEquals("err", json.getString("stderr"))
        assertFalse(json.has("stdoutTruncated"))
    }

    @Test
    fun `a timeout reports no exit code and explains the Magisk hint`() {
        val tool = tool(
            FakeRunner(RawShellResult(exitCode = null, stdout = "partial", stderr = "", timedOut = true)),
        )

        val json = invoke(tool, """{"command":"sleep 99"}""")
        assertTrue(json.getBoolean("timedOut"))
        assertEquals(JSONObject.NULL, json.get("exit"))
        assertEquals("partial", json.getString("stdout"))
        // 提示不再承诺"一定是 Magisk 弹窗"——现在可能是普通 shell 里的慢命令。
        assertTrue(json.getString("hint").contains("超时"))
    }

    @Test
    fun `truncated streams are flagged instead of looking complete`() {
        val tool = tool(
            FakeRunner(
                RawShellResult(
                    exitCode = 0, stdout = "x".repeat(300_000), stderr = "",
                    timedOut = false, stdoutTruncated = true,
                ),
            ),
        )

        val json = invoke(tool, """{"command":"yes"}""")
        assertEquals(true, json.getBoolean("stdoutTruncated"))
    }

    @Test
    fun `a shell that cannot start comes back as a readable error result`() {
        val tool = tool(FakeRunner(failWith = IOException("No such file or directory")))

        val json = invoke(tool, """{"command":"id"}""")
        // 以前这里断言的是「su 不可用」——那是把"没有 root"当成"命令跑不了"。
        // 现在没有 root 会降级到普通 shell，只有两个都起不来才算失败。
        assertTrue(json.getString("error").contains("shell 启动失败"))
    }

    @Test
    fun `结果里回报实际身份，agent 才知道自己能不能碰受保护路径`() {
        val tool = tool(
            FakeRunner(
                RawShellResult(
                    exitCode = 0, stdout = "ok", stderr = "", timedOut = false,
                    shell = "/system/bin/sh", isRoot = false,
                ),
            ),
        )

        val json = invoke(tool, """{"command":"ls /data"}""")
        assertEquals("/system/bin/sh", json.getString("shell"))
        assertFalse(json.getBoolean("isRoot"))
    }

    @Test
    fun `the real runner executes, captures and times out against sh`() {
        assumeTrue("sh available", java.io.File("/bin/sh").exists())
        val runner = ProcessShellRunner(shellBinary = "/bin/sh")

        val hello = runner.exec("echo hello; echo oops >&2; exit 3", 5_000)
        assertEquals(3, hello.exitCode)
        assertEquals(false, hello.timedOut)
        assertTrue(hello.stdout.contains("hello"))
        assertTrue(hello.stderr.contains("oops"))

        val slow = runner.exec("sleep 30", 400)
        assertTrue(slow.timedOut)
        assertNull(slow.exitCode)
    }

    private fun tool(runner: ShellRunner): ev.buan.plugins.agent.api.Tool = ShellTools.all(runner).single()

    private fun invoke(tool: ev.buan.plugins.agent.api.Tool, arguments: String): JSONObject =
        JSONObject(runBlocking { tool.invoke(arguments) })

    /** Records the timeout it was given and replies with a scripted result (or throws). */
    private class FakeRunner(
        private val result: RawShellResult =
            RawShellResult(exitCode = 0, stdout = "", stderr = "", timedOut = false),
        private val failWith: Exception? = null,
    ) : ShellRunner {
        var lastTimeoutMillis: Long = -1
            private set

        override fun exec(command: String, timeoutMillis: Long): RawShellResult {
            lastTimeoutMillis = timeoutMillis
            failWith?.let { throw it }
            return result
        }
    }
}
