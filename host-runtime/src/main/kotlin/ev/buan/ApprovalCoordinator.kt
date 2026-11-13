package ev.buan

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 宿主侧的审批门：把 brain 的"问人"变成界面上的一次选择。
 *
 * ## 为什么放在宿主而不是插件
 *
 * UI 搬进宿主之后，这道门就应该跟着搬。放在插件的后果很具体：没装 UI 插件时
 * `ApprovalService` 没有发布者，brain 的 fail-closed 生效，**所有危险工具都被拒绝**——
 * 功能不崩，但"agent 能动手"这件事等于废了一半。
 *
 * ## 队列而不是"同时问好几个"
 *
 * 一次只问一个问题。两个弹窗叠在一起时，用户分不清哪个决定对应哪次调用，
 * 而这两次调用的后果可能完全不同（一个是改亮度，一个是删文件）。后来的请求排队等前一个。
 *
 * ## 超时是兜底，不是正常路径
 *
 * 界面上没人回答（Activity 被销毁、用户锁屏走了），请求会一直挂着——
 * 挂着的轮次不会自己结束。所以给一个上限：到点按 [ApprovalDecision.UNAVAILABLE] 收尾，
 * 由 brain 记成"问不到人"。**不记成 DENY**：那是伪造一个从未发生的决定。
 */
class ApprovalCoordinator(
    private val timeoutMs: Long = 120_000,
) : ApprovalService {

    /** 正在等人回答的那个。null 表示没问题在等。 */
    private val _pending = mutableStateOf<Pending?>(null)
    val pending: State<Pending?> get() = _pending

    private var inFlight: CompletableDeferred<ApprovalDecision>? = null

    /** 本会话内已经一键放行的工具名。作用域就是这道门的生命期。 */
    private val sessionAllowed = mutableSetOf<String>()

    data class Pending(val id: Long, val toolName: String, val argumentsJson: String)

    private var nextId = 0L

    override suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision {
        synchronized(sessionAllowed) {
            if (toolName in sessionAllowed) return ApprovalDecision.ALLOW_SESSION
        }

        val id = ++nextId
        val answer = CompletableDeferred<ApprovalDecision>()
        inFlight = answer
        // 先挂上，再等：界面靠这个 State 才知道要弹什么。
        _pending.value = Pending(id, toolName, argumentsJson)

        val decision = try {
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: ApprovalDecision.UNAVAILABLE
        } finally {
            // 无论怎么结束（回答、超时、协程被取消），都要把界面上的问题撤掉，
            // 否则用户会看到一个点了也没反应的弹窗。
            if (_pending.value?.id == id) _pending.value = null
            if (inFlight === answer) inFlight = null
        }

        if (decision == ApprovalDecision.ALLOW_SESSION) {
            synchronized(sessionAllowed) { sessionAllowed += toolName }
        }
        return decision
    }

    /**
     * 界面上点了按钮。id 不匹配说明这条问题已经超时收尾了——静默忽略，
     * 不要拿旧答案去回答新问题。
     */
    fun decide(id: Long, decision: ApprovalDecision) {
        if (_pending.value?.id != id) return
        inFlight?.complete(decision)
    }
}
