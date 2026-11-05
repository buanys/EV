package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall

/**
 * 把一次被中断的工具调用补成合法的历史。
 *
 * ## 为什么需要
 *
 * 进程在工具执行期间被杀时，事件流会停在 `assistant(tool_calls)` 上：tool 结果和
 * 收尾事件都没来得及写。这是**如实记录**，但它让下一轮必然失败——OpenAI 协议要求
 * 每个 `tool_call` 后面必须跟一条同 id 的 `tool` 消息，缺失时服务端直接 400：
 *
 * > An assistant message with 'tool_calls' must be followed by tool messages responding
 * > to each 'tool_call_id'.
 *
 * 实测过：一次 kill -9 之后，这个会话的每一条新消息都得到同一条 400，永久卡死。
 *
 * ## 为什么是"读取时补"而不是"启动时修"
 *
 * 补出来的消息不写回日志。日志只记录真实发生的事（谁做了什么、什么时候），
 * 模型看到的是"上次那个调用没完成"。把合成结果写进日志等于伪造历史，
 * 将来回放时就分不清哪条是真的工具返回。
 *
 * 代价是每轮读取多一次线性扫描，几百条消息的量级可以忽略。
 */
internal object HistoryRepair {

    /** 补出来的工具结果。措辞要让模型知道可以重试，而不是以为工具坏了。 */
    private const val INTERRUPTED =
        "（中断）这次工具调用没有完成：EV 在它执行期间退出了。需要的话请重新调用。"

    /**
     * 如果历史末尾停在一条带 [AgentMessage.toolCalls] 的 assistant 消息上，
     * 为每个还没有结果的调用补一条 tool 消息。
     *
     * 只处理**末尾**：崩溃只会在尾部留下悬挂，中间的悬挂属于数据损坏，
     * 悄悄补掉反而会掩盖问题。
     */
    fun closeDanglingToolCalls(messages: List<AgentMessage>): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        // 不能只看末尾：一次 400 之后，那条错误消息会被当作 assistant 回复写进历史，
        // 悬挂的 tool_call 就跑到中间去了。而任何位置缺一条 tool 消息都会让下一次请求 400，
        // 所以这里扫全程——遇到新的 user / assistant 就把上一批没着落的调用补掉。
        val out = ArrayList<AgentMessage>(messages.size + 2)
        val pending = ArrayList<AgentToolCall>()

        fun flush() {
            for (call in pending) {
                out += AgentMessage(
                    role = AgentRole.TOOL,
                    content = INTERRUPTED,
                    name = call.name,
                    toolCallId = call.id,
                )
            }
            pending.clear()
        }

        for (message in messages) {
            if (message.role == AgentRole.USER || message.role == AgentRole.ASSISTANT) flush()
            out += message
            when (message.role) {
                AgentRole.ASSISTANT -> pending += message.toolCalls
                AgentRole.TOOL -> pending.removeAll { it.id == message.toolCallId }
                else -> Unit
            }
        }
        flush()
        return out
    }
}
