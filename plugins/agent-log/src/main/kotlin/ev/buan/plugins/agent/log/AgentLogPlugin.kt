package ev.buan.plugins.agent.log

import ev.buan.plugins.agent.api.SessionLog
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import org.json.JSONObject

/**
 * 事件流插件：append-only 日志的落地（`/root/specs/01-event-stream.md`）。
 *
 * 两条流，各有各的用途：
 *
 * - **审计流**（`events.jsonl`）：全局、跨会话连续，`sys` / `sensor` / `proposal` / `verdict` /
 *   `action` / `report` / `rule` 七个命名空间。缺一条就是缺一条，`sys.gap` 必须补上。
 * - **会话流**（`sessions/<hash>.jsonl`，见 [SessionLogService]）：按会话分片，记录
 *   "模型看到了什么"，是 [SessionLog] 的实现，被 agent-brain 当作历史真相源。
 *
 * 本插件只做持久化：产生事件的是 brain 与其它业务插件，这里不产生、不判定、不解释。
 * 它必须先于任何 agent 可用，因为后续所有能力都以"能回放的事件流"为真相源。
 *
 * 启动时做两件规格要求的事：
 * 1. 读取时若发现序号缺口，**补一条 `sys.gap`** 进流（缺口必须被记录，不能静默跳过）；
 * 2. 全流校验一次，把 ERROR 级违规记进日志（`logcat`），不写进事件流——
 *    校验发现的违规要不要入流、由谁写，属于判定层的职责，这里不自作主张地写。
 */
class AgentLogPlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.log",
        name = "审计日志",
        version = "0.1.0",
        description = "把事件流写进自己的存储：截断、隔离与缺口都会被记录，重启后可恢复。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("event-log", org.cordis.api.PluginCapability.Kind.BACKGROUND, "事件日志", "按容量滚动写入，损坏的行进隔离区并补一条 sys.gap。"),
        ),
    )

    override val name = "agent-log"
    override val title = "agent 事件流"
    override val body = "append-only 事件日志：回放、校验、崩溃恢复。所有 agent 行为的真相源。"

    override val inject: Dependencies = dependencies(PluginStorage.Key, ServiceDirectory.Key)

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

        // 会话流：按 sessionId 分片，供 agent-brain 当作历史真相源。
        // 与审计流互不影响——清空一个会话不会在审计流里留下一个洞。
        val directory = ctx.require(ServiceDirectory.Key)
        val sessions = SessionLogService(storage)
        directory.publish(SessionLog.Key, sessions)
        android.util.Log.i(TAG, "会话事件流已发布（sessions/<hash>.jsonl）")
        effect.collect {
            if (directory.lookup(SessionLog.Key) === sessions) directory.retract(SessionLog.Key)
        }
    }

    private companion object {
        const val TAG = "agent-log"
        const val EVENTS_PATH = "events.jsonl"
        const val QUARANTINE_PATH = "quarantine.jsonl"
    }
}
