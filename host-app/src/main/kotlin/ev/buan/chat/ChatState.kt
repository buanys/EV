package ev.buan.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.StepState
import ev.buan.ui.EvTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.cordis.api.ServiceDirectory

/**
 * 聊天页的全部状态。
 *
 * 它**不拥有**对话历史：历史在 `ev-log` 的会话事件流里，这里只是一次会话的投影。
 * 换走这个 Activity（或进程被杀）时，丢掉的是"屏幕上的这几条"，不是那次对话——
 * 下次要用真正的恢复路径把它们读回来，而不是靠这里缓存。
 *
 * 现在这一版只做一次会话的内存投影：宿主还没有接"从事件流恢复"的入口，
 * 那是下一步的事，接口已经留在这里（[sessionId] 固定）。
 */
class ChatState(
    private val services: () -> ServiceDirectory?,
    /** 按能力项现装。对话页要的是 `service:agent/service`，声明它的容器才会被装载。 */
    private val ensure: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
) {

    var input by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var connected by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** 顶部胶囊里的"真实信息"：当前会用的模型名。没有可用端点时为 null。 */
    var modelLabel by mutableStateOf<String?>(null)
        private set

    private companion object {
        /** `AgentService.Key.name` 的字面量，避免 ui 层依赖 host-api 的服务常量。 */
        const val AGENT_SERVICE = "service:agent/service"
    }

    private var job: Job? = null
    private var nextId = 0L
    private var sessionId = "host-${System.currentTimeMillis()}"

    private val agent: AgentService? get() = services()?.lookup(AgentService.Key)

    suspend fun refresh() {
        // 第一次查之前先确保有人能提供它：这就是懒加载的触发点。
        // 插件内部再 looku 时不会触发装载（那边是同步路径），所以插件要靠自己的
        // provides 声明依赖，由宿主在装载它之前把依赖先 ensure 到位。
        if (agent == null) ensure(AGENT_SERVICE)
        connected = agent?.ready == true
        modelLabel = services()
            ?.lookup(ev.buan.plugins.agent.api.ModelProviderService.Key)
            ?.providers()
            ?.firstOrNull { it.enabled && it.apiKey.isNotBlank() && it.models.isNotEmpty() }
            ?.let { it.selectedModelId ?: it.models.firstOrNull()?.id }
    }

    fun onInputChange(value: String) {
        input = value
    }

    fun newConversation() {
        job?.cancel()
        job = null
        items = emptyList()
        input = ""
        sending = false
        notice = null
        sessionId = "host-${System.currentTimeMillis()}"
        scope.launch { refresh() }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val brain = agent
        if (brain == null) {
            notice = "还没有对话插件（ev-brain）。装上并启用后就能说话。"
            return
        }
        if (!brain.ready) {
            notice = "对话插件已启动，但模型还没连上（缺 ev-model 或没配模型）。"
            return
        }

        notice = null
        input = ""
        sending = true
        val session = sessionId
        items = items + ChatItem.User(++nextId, text)
        job = scope.launch {
            var roundAssistantId: Long? = null
            try {
                brain.sendStreaming(session, text).collect { progress ->
                    when (progress) {
                        is AgentProgress.RoundStarted -> roundAssistantId = null

                        is AgentProgress.TextDelta -> {
                            val id = roundAssistantId
                                ?: ChatItem.Assistant(++nextId, "").also {
                                    roundAssistantId = it.id
                                    items = items + it
                                }.id
                            items = items.map {
                                if (it is ChatItem.Assistant && it.id == id) it.copy(text = it.text + progress.text) else it
                            }
                        }

                        is AgentProgress.ToolStarted -> items = items + ChatItem.Tool(
                            key = progress.id,
                            name = progress.name,
                            argsJson = progress.argsJson,
                            tone = EvTone.Accent,
                            preview = null,
                            elapsedMs = 0,
                        )

                        is AgentProgress.ToolFinished -> items = items.map {
                            if (it is ChatItem.Tool && it.key == progress.id) {
                                it.copy(
                                    tone = progress.state.toTone(),
                                    preview = progress.preview,
                                    elapsedMs = progress.elapsedMs,
                                )
                            } else {
                                it
                            }
                        }

                        is AgentProgress.RoundFinished -> Unit

                        AgentProgress.Finalizing -> Unit

                        is AgentProgress.Done -> {
                            // 用最终消息校正流式拼出来的内容：增量可能被 provider 合并过顺序。
                            val finalText = progress.reply.message.content
                            val id = roundAssistantId
                            if (id != null) {
                                items = items.map {
                                    if (it is ChatItem.Assistant && it.id == id) it.copy(text = finalText) else it
                                }
                            } else if (finalText.isNotBlank()) {
                                items = items + ChatItem.Assistant(++nextId, finalText)
                            }
                            val stop = progress.reply.stop
                            if (stop != null && stop.reason != ev.buan.plugins.agent.api.AgentStop.Reason.FINISHED) {
                                items = items + ChatItem.Notice("这一轮结束在：${stop.reason}${stop.detail?.let { " · $it" } ?: ""}")
                            }
                        }

                        is AgentProgress.ToolAwaitingApproval -> items = items.map {
                            if (it is ChatItem.Tool && it.key == progress.id) it.copy(tone = EvTone.Accent) else it
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                items = items + ChatItem.Notice("已停止")
                throw cancelled
            } catch (error: Throwable) {
                items = items + ChatItem.Notice("出错了：${error.message ?: error::class.simpleName}")
            } finally {
                sending = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sending = false
    }
}

private fun StepState.toTone(): EvTone = when (this) {
    StepState.Ok -> EvTone.Normal
    StepState.Failed -> EvTone.Danger
    StepState.Skipped -> EvTone.Muted
    StepState.Running, StepState.AwaitingApproval -> EvTone.Accent
    StepState.Cancelled -> EvTone.Muted
}

/** 屏幕上的一条。与 `SessionEvent` 不是一回事：这是**投影**，可以丢、可以重排、可以没有 id。 */
sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    data class Assistant(override val id: Long, val text: String) : ChatItem

    /** 一次工具调用。收起时只占一行。 */
    data class Tool(
        val key: String,
        val name: String,
        val argsJson: String,
        val tone: EvTone,
        val preview: String?,
        val elapsedMs: Long,
        override val id: Long = key.hashCode().toLong(),
    ) : ChatItem

    /** 一条系统说明：停止、报错、预算用尽。不进模型上下文，只给人看。 */
    data class Notice(val text: String, override val id: Long = text.hashCode().toLong()) : ChatItem
}
