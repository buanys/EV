package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * 会话里的一个事实。
 *
 * 只有写进这里的东西才算发生过：模型下一轮看到的消息历史必须能从事件重建，
 * 而不是另存一份谁也不知道该怎么对齐的聊天记录。
 *
 * 事件类型刻意做得很少。一个 [AgentMessage] 已经能表达角色、内容、工具调用和
 * 结果关联，再按"用户说了什么 / 模型说了什么 / 工具返回了什么"拆成十种事件，
 * 投影时必须再写一遍同样的分派逻辑，多出来的只有漂移的机会。
 */
sealed interface SessionEvent {
    /** 事实发生的时间（epoch ms），不是落盘时间。 */
    val at: Long

    /**
     * 一条模型可见的消息。
     *
     * [AgentMessage.images] 不落盘：图片是 1 MB 级的 transient 数据，模型随时可以重新读，
     * 而把截图塞进每一轮的事件里，一天就能吃掉存储预算。重建出来的消息因此只保留
     * 说明文字，这与当前 memory 的取舍一致。
     */
    data class Message(
        val message: AgentMessage,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent

    /** 一轮结束了，以及为什么。没有这一条，回放时看不出这一轮是被预算截断的。 */
    data class TurnEnded(
        val stop: AgentStop,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent
}

/**
 * 会话事件流：模型可见历史的唯一真相源。
 *
 * 与 [MemoryService] 的分工：
 *  - 这里是**权威**。写进来的事件不可改写，纠正只能追加新事件。
 *  - [MemoryService] 是**派生物**。蒸馏、笔记、索引仍然由它做，但它不再定义"历史是什么"。
 *
 * 为什么值得多这一层：没有一个可回放的事件流，就没有崩溃恢复（不知道最后一轮停在哪）、
 * 没有 fork（想试另一条路只能污染原对话）、也没有办法回答"模型当时到底看到了什么"。
 *
 * ## 实现方的义务
 *
 * 1. **单写者**：同一次 [append] 必须串行，序号由实现分配，调用方给不了。
 * 2. **顺序**：[messages] 返回的顺序必须与 [append] 的顺序一致，最旧在前。
 * 3. **不丢**：读失败要么抛给调用方，要么返回已知的最好结果，绝不能静默返回空表
 *    当成"这个会话没有历史"——调用方会据此以为自己是第一轮。
 * 4. **可缺失**：语法允许为空事件（新建会话），[messages] 返回空表是合法状态。
 */
interface SessionLog {
    /**
     * 追加一条事件。
     *
     * 挂起直到落盘（或实现确认已进入持久化队列）。调用方在 [AgentService] 的循环里
     * 按模型调用的顺序写，实现不得重排。
     */
    suspend fun append(sessionId: String, event: SessionEvent)

    /**
     * 从事件重建的完整历史，最旧在前。
     *
     * 这是 `memory.history` 的替代品：模型下一轮拿到的历史就是它。重建是纯函数式的，
     * 同样的日志永远得到同样的消息序列——回放就是重新派生，不是另跑一遍。
     */
    suspend fun messages(sessionId: String): List<AgentMessage>

    /**
     * 原始事件，供轨迹、调试和 fork 使用。
     *
     * 与 [messages] 的区别是它保留了 [SessionEvent.TurnEnded] 和原始顺序，调用方
     * 可以据此画出"第几轮为什么停下"。
     */
    suspend fun events(sessionId: String): List<SessionEvent>

    /** 丢掉一个会话的全部事件。用户清空对话时调用，不是日常路径。 */
    suspend fun clear(sessionId: String)

    companion object {
        val Key = ServiceKey<SessionLog>("agent/session-log")
    }
}
