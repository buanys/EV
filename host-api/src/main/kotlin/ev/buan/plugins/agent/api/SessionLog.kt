package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * 会话里的一个事实。
 *
 * 只有写进这里的东西才算发生过：模型下一轮看到的消息历史必须能从事件重建，
 * 而不是另存一份谁也不知道该怎么对齐的聊天记录。
 *
 * ## 两类事件，一条硬边界
 *
 * - **[inModelContext] 为 true**：会出现在模型下一轮的输入里（目前只有 [SessionEvent.Message]）
 * - **为 false**：只进日志，供回放、审计、排障（轮次边界、审批、被放弃的工具调用）
 *
 * 这条边界是照抄 DeepSeek Harness 的取舍（它把 62 种事件里的 5 种标成 surface）：
 * 让"模型看到的"与"发生过的"分开，才能既保证可回放，又不把审计数据塞进上下文。
 *
 * ## 事件类型刻意做得很少
 *
 * 一个 [AgentMessage] 已经能表达角色、内容、工具调用和结果关联，再按"用户说了什么 /
 * 模型说了什么 / 工具返回了什么"拆成十种事件，投影时必须再写一遍同样的分派逻辑，
 * 多出来的只有漂移的机会。
 */
sealed interface SessionEvent {

    /** 事实发生的时间（epoch ms），不是落盘时间。 */
    val at: Long

    /** 这条事件是否进入模型可见的上下文。派生历史时只看它为 true 的事件。 */
    val inModelContext: Boolean get() = false

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
    ) : SessionEvent {
        override val inModelContext: Boolean get() = true
    }

    /** 一轮结束了，以及为什么。没有这一条，回放时看不出这一轮是被预算截断的。 */
    data class TurnEnded(
        val stop: AgentStop,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent

    /**
     * 某个危险工具被交给人审批了（**审计用，不进模型上下文**）。
     *
     * 有 [ApprovalAsked] 没有 [ApprovalDecided]，说明进程在等人回答时死了——
     * 这条悬空记录本身就是证据，恢复时不要再把它当成"没人问过"。
     */
    data class ApprovalAsked(
        val id: String,
        val toolName: String,
        val callId: String? = null,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent

    /** 人（或替代它的一层）给出了答案。与 [ApprovalAsked] 用同一个 [id] 配对。 */
    data class ApprovalDecided(
        val id: String,
        val outcome: ApprovalOutcome,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent

    /**
     * 一次工具调用**没有结果**：进程被杀、被取消、或执行器失联。
     *
     * 单独一个事件而不是"什么都不记"，因为"没调用"和"调了但不知道结果"是两件事，
     * 恢复时对它们的处置完全不同：前者可以直接重试，后者必须先核实外部状态。
     * 投影时它会变成一条合成的 [AgentRole.TOOL] 消息，让模型知道这一步失败了。
     */
    data class ToolAbandoned(
        val callId: String,
        val toolName: String,
        val reason: AbandonReason,
        override val at: Long = System.currentTimeMillis(),
    ) : SessionEvent
}

/**
 * 审批问题的最终归宿。
 *
 * 比 [ApprovalDecision] 多一个 [UNAVAILABLE]：那个类型表达"人怎么答的"，
 * 这个类型表达"审批这一环最后怎么了"。没有门、门抛错、回答非法，最终都是 [UNAVAILABLE]，
 * 按 fail-closed 一律当作拒绝执行（见 `ev-brain` 的审批链）。
 */
enum class ApprovalOutcome {
    /** 人点了允许，只此一次。 */
    ALLOWED_ONCE,

    /** 人点了"本次会话内都允许"。作用域由审批门自己定义。 */
    ALLOWED_SESSION,

    /** 人明确拒绝。 */
    REJECTED,

    /** 问不到人：没有审批门、门抛错、或回答不可解析。fail-closed。 */
    UNAVAILABLE,
}

/** 工具调用为什么没有结果。 */
enum class AbandonReason {
    /** 记了调用，但执行器还没开始跑就中断了。可以直接重试。 */
    NOT_STARTED,

    /** 已经开始跑了，但不知道跑完没有。动过外部状态的话，重试前必须先核实。 */
    OUTCOME_UNKNOWN,
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
