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
                return errorJson("shell 启动失败：${error.message ?: error::class.simpleName}")
            }
            return JSONObject()
                .put("exit", result.exitCode ?: JSONObject.NULL)
                .put("timedOut", result.timedOut)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                // 身份必须回报：同一条命令在 root 下能跑、在应用 uid 下会 Permission denied。
                // agent 不知道自己是哪种身份，就会把"没权限"误读成"文件不存在"。
                .put("shell", result.shell)
                .put("isRoot", result.isRoot)
                .apply {
                    if (result.stdoutTruncated) put("stdoutTruncated", true)
                    if (result.stderrTruncated) put("stderrTruncated", true)
                    if (result.timedOut) {
                        put("hint", "命令超时被杀。若 shell=su，可能停在授权弹窗；否则命令本身太慢。")
                    }
                    // 只在"确实跑完且失败"时补这条提示。超时时 exitCode 是 null，
                    // `null != 0` 同样成立，会把超时提示覆盖成权限提示——那会误导 agent
                    // 以为命令跑过了只是没权限。
                    if (!result.isRoot && !result.timedOut && (result.exitCode ?: 0) != 0) {
                        put("hint", "当前不是 root（shell=${result.shell}），访问 /data 等受保护路径会失败。")
                    }
                }
                .toString()
        }

        private fun errorJson(message: String): String =
            JSONObject().put("error", message).toString()
    }
}
