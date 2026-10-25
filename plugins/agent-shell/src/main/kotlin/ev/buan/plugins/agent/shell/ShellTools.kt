package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONObject

/**
 * The root shell tool offered to the agent.
 *
 * `run_shell` is [ToolSpec.dangerous]: the brain routes every call through the published approval
 * gate before this class ever sees it, and refuses outright when no UI is there to ask. This file
 * therefore contains no approval logic of its own - the human gate lives in exactly one place.
 *
 * Results are JSON text (`exit`, `timedOut`, `stdout`, `stderr`, optional `stdoutTruncated` /
 * `stderrTruncated`) so the model can quote precise lines; argument errors and a missing `su`
 * come back as an `error` field instead of an exception, because one bad call must not abort the
 * agent's whole turn.
 */
internal object ShellTools {

    /** Default per-call budget; the model may ask for less or more, up to [MAX_TIMEOUT_SECONDS]. */
    const val DEFAULT_TIMEOUT_SECONDS = 30

    /** Hard ceiling: no model turn gets to park a command for longer than this. */
    const val MAX_TIMEOUT_SECONDS = 120

    fun all(runner: ShellRunner): List<Tool> = listOf(RunShellTool(runner))

    val runShellSpec: ToolSpec = ToolSpec(
        name = "run_shell",
        description = "以 root 身份执行一条 shell 命令（经 su）。高危操作：每次执行前都会请求人工审批。" +
            "返回 JSON：exit（退出码）、timedOut、stdout、stderr；输出过长时带截断标记。",
        parametersJson = """
            {"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"},"timeout_seconds":{"type":"integer","description":"超时秒数，默认 30，上限 120"}},"required":["command"],"additionalProperties":false}
        """.trimIndent(),
        dangerous = true,
    )

    private class RunShellTool(private val runner: ShellRunner) : Tool {
        override val spec: ToolSpec = runShellSpec

        override suspend fun invoke(argumentsJson: String): String {
            val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
                ?: return errorJson("请求体不是合法 JSON")
            val command = arguments.optString("command", "").trim()
            if (command.isEmpty()) return errorJson("缺少 command 参数")
            val requested = arguments.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
            val timeoutSeconds = requested.coerceIn(1, MAX_TIMEOUT_SECONDS)

            val result = try {
                runner.exec(command, timeoutSeconds * 1_000L)
            } catch (error: Exception) {
                // ProcessBuilder throws IOException when the binary does not exist: an unrooted
                // phone is a normal runtime fact, not a crash.
                return errorJson("su 不可用（设备未 root 或已拒绝授权）：${error.message ?: error::class.simpleName}")
            }
            return JSONObject()
                .put("exit", result.exitCode ?: JSONObject.NULL)
                .put("timedOut", result.timedOut)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .apply {
                    if (result.stdoutTruncated) put("stdoutTruncated", true)
                    if (result.stderrTruncated) put("stderrTruncated", true)
                    if (result.timedOut) {
                        put("hint", "命令超时被杀。首次执行可能停在 Magisk 授权弹窗——到手机上确认后重试。")
                    }
                }
                .toString()
        }

        private fun errorJson(message: String): String =
            JSONObject().put("error", message).toString()
    }
}
