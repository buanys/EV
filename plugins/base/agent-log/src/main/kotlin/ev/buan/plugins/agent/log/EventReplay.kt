package ev.buan.plugins.agent.log

/**
 * 回放：把事件流投影成状态。规格 `/root/specs/01-event-stream.md` §2.3–2.4。
 *
 * 两条硬约束写在签名上，而不是注释里：
 *
 * 1. **派生状态不落盘**——这个类没有 `save`。要快照可以，但快照必须能被丢弃后由
 *    [replay] 重建；一旦有人给 [LogState] 加了持久化，真相源就变成两份了。
 * 2. **重放确定性**——实现里不读时钟、不用随机数、不碰网络。同样的输入两次调用
 *    必然得到相等的 [LogState]（[LogState] 是 data class，可以直接断言 equals）。
 */
object EventReplay {

    /**
     * 全量重放。
     *
     * 只做到 v0 需要的粒度：各类事件计数、被作废的集合、违规计数。
     * 更富的投影（配额、世界状态）属于后续规格，加在它们自己的 reducer 里，
     * 不要往这里堆——这里是"事件流的形状"，不是"业务状态"。
     */
    fun replay(events: List<EvEvent>): LogState {
        val counts = mutableMapOf<String, Int>()
        val voided = mutableSetOf<String>()

        for (e in events) {
            // 按命名空间计数：type 的具体后缀是发散的，前缀才有统计意义。
            val ns = e.type.substringBefore('.') + "."
            counts[ns] = (counts[ns] ?: 0) + 1

            if (e.type == EvTypes.SYS_VOID) {
                // 「纠正只能追加」的唯一落地方式：void 指向谁，谁就不再生效。
                // 注意被作废的事件仍然留在流里（不可删），只是不再参与派生。
                e.payload.optString("target", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let { voided += it }
            }
        }

        return LogState(
            lastSeq = events.lastOrNull()?.seq ?: 0,
            eventCount = events.size,
            countsByNamespace = counts.toSortedMap(),
            voidedIds = voided.toSortedSet(),
            violationCount = counts[EvTypes.SYS_VIOLATION] ?: 0,
        )
    }

    /**
     * 只取 `seq <= upTo` 的前缀重放。
     *
     * 回放某个历史时刻是审计的基本需求：给别人看"当时为什么这么判"时，
     * 不能把之后的事件也喂进去。越界即抛，避免静默截断。
     */
    fun replayUpTo(events: List<EvEvent>, upTo: Int): LogState {
        require(upTo >= 0) { "upTo 不能为负：$upTo" }
        return replay(events.filter { it.seq <= upTo })
    }

    /**
     * 派生状态。刻意做成 data class：
     * 幂等与确定性的验收（规格 §5.1）就是 `replay(x) == replay(x)`。
     */
    data class LogState(
        val lastSeq: Int,
        val eventCount: Int,
        /** 命名空间前缀（`sys.` `sensor.` …）到条数。 */
        val countsByNamespace: Map<String, Int>,
        /** 被 `sys.void` 指过的 id；这些事件仍在流里但不再生效。 */
        val voidedIds: Set<String>,
        val violationCount: Int,
    )
}
