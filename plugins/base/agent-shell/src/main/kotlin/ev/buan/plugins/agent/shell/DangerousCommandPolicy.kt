package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.ApprovalPolicy
import org.json.JSONObject

/**
 * 明确灾难性的 shell 命令直接拒绝，其余一律交给人（`ASK`）。
 *
 * ## 为什么清单必须这么窄
 *
 * `DENY` 是唯一会**跳过人类**的一档。它只该收「执行完这台机器就废了」的命令：
 * 误拦会让 agent 以为这条路走不通而放弃，漏拦只是回到"每次问人"——后者永远更便宜。
 * 所以这里不做任何"看起来危险"的推测：`rm -rf` 删工作目录、`reboot`、`pm uninstall`、
 * `chmod 777` 全都**不在**清单里，它们照常走人工审批。
 *
 * ## 为什么要先规范化再匹配
 *
 * 模型写出来的命令行千奇百怪：多余空格、大小写、`&&` 串联。不规范化就会漏掉
 * `RM  -RF   /` 这种。
 *
 * ## 它解决的是哪一种失败
 *
 * 没有策略时，headless 形态（没有 UI 就没有审批通道）对所有高危工具 fail-closed，
 * 等于 `run_shell` 永远不能用。有了它，至少"不能执行"只落在真正不可逆的那几条上。
 */
internal object DangerousCommandPolicy : ApprovalPolicy {

    private val WHITESPACE = Regex("""\s+""")

    private val DENY_PATTERNS = listOf(
        // rm 指向根目录。`-rf /` 与 `-rf /*` 都算；参数顺序与合并写法都覆盖。
        Regex("""\brm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)\s+/(\s|\*|$)"""),
        // GNU rm 在删根时需要显式豁免，出现它就说明意图明确。
        Regex("""\brm\s+.*--no-preserve-root"""),
        // 格式化文件系统。
        Regex("""\bmkfs(\.[a-z0-9]+)?\s"""),
        // 直接写块设备：dd 与 shell 重定向两种写法。
        Regex("""\bdd\b.*\bof=/dev/(block/|sd|mmcblk|nvme)"""),
        Regex(""">\s*/dev/(block/|sd|mmcblk|nvme)"""),
        // 抹掉文件系统签名，效果等同格式化。
        Regex("""\bwipefs\b"""),
    )

    override fun judge(toolName: String, argumentsJson: String): ApprovalPolicy.Verdict {
        // 只有 run_shell 有"命令行"这个概念；别的工具即使危险也不该被这个策略误判。
        if (toolName != SHELL_TOOL) return ApprovalPolicy.Verdict.ASK
        val command = commandOf(argumentsJson) ?: return ApprovalPolicy.Verdict.ASK
        val normalized = command.lowercase().replace(WHITESPACE, " ")
        return if (DENY_PATTERNS.any { it.containsMatchIn(normalized) }) {
            ApprovalPolicy.Verdict.DENY
        } else {
            ApprovalPolicy.Verdict.ASK
        }
    }

    /** 参数不是合法 JSON 或没有 command 时交回给人判断，不在这里猜。 */
    private fun commandOf(argumentsJson: String): String? =
        runCatching { JSONObject(argumentsJson).optString("command", "") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private const val SHELL_TOOL = "run_shell"
}
