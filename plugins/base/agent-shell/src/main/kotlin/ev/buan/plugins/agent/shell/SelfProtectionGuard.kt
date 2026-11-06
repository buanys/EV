package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ToolGuard
import org.json.JSONObject

/**
 * 不许动 EV 自己。
 *
 * ## 为什么这条属于 guard 而不是审批策略
 *
 * [ev.buan.plugins.agent.api.ApprovalPolicy] 决定"要不要问人"，问过之后人点头就能执行。
 * 这两条不行：**人点头也不该允许**。它们不是一个判断，是一个边界。
 *
 * - 杀掉 EV 进程：会话当场中断，正在跑的任务全丢。agent 想"重开一下"时很可能这么干。
 * - 改写 EV 自己的数据目录：插件存储、信任表、会话日志都在那儿，写坏了下次启动直接起不来。
 *
 * ## 为什么按命令文本判断，而不是按工具语义
 *
 * `run_shell` 什么都不保证，只能看它要跑什么。规则刻意做得窄：
 * 只认"明确的写命令 + 明确的路径"，只读命令（cat / ls / grep）一律放行——
 * 拦住读没有意义，还会让 agent 误以为拿不到信息。
 */
internal object SelfProtectionGuard : ToolGuard {

    private const val SHELL_TOOL = "run_shell"
    private const val PACKAGE = "ev\\.buan"
    private const val OWN_DATA = "/data/(?:data|user/[0-9]+)/[^\\s]*ev\\.buan"

    private val WHITESPACE = Regex("""\s+""")

    /** 终止自身：am force-stop / am kill / pkill / killall，都要点到自己的包名或进程。 */
    private val KILLS_SELF = listOf(
        Regex("""\bam\s+(force-stop|kill)\b[\s\S]*\b$PACKAGE\b"""),
        Regex("""\b(pkill|killall)\b[\s\S]*\b$PACKAGE\b"""),
    )

    /** 写自身数据目录：只认带写语义的命令，读命令不在此列。 */
    private val WRITES_OWN_DATA = listOf(
        Regex("""\b(rm|mv|cp|chmod|chown|truncate|dd|sed\s+-i)\b[\s\S]*$OWN_DATA"""),
        Regex("""\b(pm|cmd)\b[\s\S]*\b(clear|uninstall)\b[\s\S]*\b$PACKAGE\b"""),
    )

    override suspend fun beforeTool(call: AgentToolCall): String? {
        if (call.name != SHELL_TOOL) return null
        val command = commandOf(call.argumentsJson) ?: return null
        val normalized = command.lowercase().replace(WHITESPACE, " ")

        if (KILLS_SELF.any { it.containsMatchIn(normalized) }) {
            return "已拦截：这条命令会终止 EV 自身，正在进行的会话会直接中断。如果确实需要重启，" +
                "请让用户手动操作。"
        }
        if (WRITES_OWN_DATA.any { it.containsMatchIn(normalized) }) {
            return "已拦截：这条命令会改写 EV 自己的数据目录（插件存储、信任表、会话日志都在里面）。" +
                "要清理的话请用插件管理页的「清理」，不要动这个目录。"
        }
        return null
    }

    private fun commandOf(argumentsJson: String): String? =
        runCatching { JSONObject(argumentsJson).optString("command", "") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
