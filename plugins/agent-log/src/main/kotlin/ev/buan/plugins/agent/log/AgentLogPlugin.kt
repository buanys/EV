package ev.buan.plugins.agent.log

import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
import org.cordis.dependencies
import org.json.JSONObject

/**
 * 事件流插件：append-only 日志的落地（`/root/specs/01-event-stream.md`）。
 *
 * v0 的职责刻意很窄——**只有存、读、恢复、校验**，不产生任何事件、不做判定、不接 agent。
 * 按 `world-design-v2.md` §8 的顺序，这一层要先于任何 agent 存在，因为所有后续能力
 * （提案、判定、执行、汇报）都以"能回放的事件流"为真相源。
 *
 * 启动时做两件规格要求的事：
 * 1. 读取时若发现序号缺口，**补一条 `sys.gap`** 进流（缺口必须被记录，不能静默跳过）；
 * 2. 全流校验一次，把 ERROR 级违规记进日志（`logcat`），不写进事件流——
 *    校验发现的违规要不要入流、由谁写，属于判定层的职责，这里不自作主张地写。
 */
class AgentLogPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-log"
    override val title = "agent 事件流"
    override val body = "append-only 事件日志：回放、校验、崩溃恢复。所有 agent 行为的真相源。"

    override val inject: Dependencies = dependencies(PluginStorage.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val storage = ctx.require(PluginStorage.Key)
        val log = EventLog(
            store = PluginStorageLogStore(storage, EVENTS_PATH),
            quarantineStore = PluginStorageLogStore(storage, QUARANTINE_PATH),
        )

        val events = log.readAll()
        if (!log.lastRecovery.isClean) {
            android.util.Log.w(
                TAG,
                "事件流恢复：截断于行 ${log.lastRecovery.truncatedLine}，" +
                    "隔离 ${log.lastRecovery.quarantinedLines} 行，" +
                    "缺口 ${log.lastRecovery.gaps.joinToString { "${it.first}..${it.last}" }}",
            )
        }

        // 规格 §2.1：恢复后若发现缺口，必须写一条 sys.gap 说明。缺一条补一条，各自成事件。
        for (gap in log.lastRecovery.gaps) {
            log.append(
                type = EvTypes.SYS_GAP,
                actor = EvActors.CODE,
                payload = JSONObject().apply {
                    put("missing_from", gap.first)
                    put("missing_to", gap.last)
                    put("reason", "recovery")
                },
            )
            android.util.Log.w(TAG, "已补 sys.gap：${gap.first}..${gap.last}")
        }

        val violations = EventValidator.validate(events)
        violations.filter { it.severity == EventValidator.Severity.ERROR }.forEach {
            android.util.Log.e(TAG, "事件流不变量违反 [${it.rule}] seq=${it.seq}：${it.detail}")
        }
    }

    private companion object {
        const val TAG = "agent-log"
        const val EVENTS_PATH = "events.jsonl"
        const val QUARANTINE_PATH = "quarantine.jsonl"
    }
}
