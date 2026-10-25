package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration tests over fake capabilities: no Android, no network, no plugin storage.
 *
 * They pin the degradation contract (a missing capability is a readable reply, never an exception),
 * the tool round (the model asks, the brain executes, the result is fed back for a final answer),
 * and the per-turn system prompt (placed first, never persisted).
 */
class AgentBrainServiceTest {

    @Test
    fun `not ready without a model plugin and answers with a readable fallback`() {
        val brain = brain()

        assertFalse(brain.ready)
        val reply = runBlocking { brain.send("session", "你好") }

        assertTrue(reply.message.content, reply.message.content.contains("未连接模型"))
        assertEquals(AgentEvent.Kind.ERROR, reply.events.single().kind)
    }

    @Test
    fun `not ready when the model plugin serves no configured model`() {
        val model = ScriptedModel(replies = mutableListOf(), models = emptyList())
        val brain = brain(model = model)

        assertFalse(brain.ready)
        val reply = runBlocking { brain.send("session", "你好") }

        assertTrue(reply.message.content, reply.message.content.contains("尚未配置"))
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `ready once a model is configured and a text turn is recorded in memory`() {
        val memory = RecordingMemory()
        val model = ScriptedModel(mutableListOf(textReply("你好，世界")))
        val brain = brain(model = model, memory = memory)

        assertTrue(brain.ready)
        val reply = runBlocking { brain.send("session", "hi") }

        assertEquals("你好，世界", reply.message.content)
        assertEquals(listOf("hi"), model.requests.single().messages.map { it.content })
        val stored = memory.sessions.getValue("session")
        assertEquals(listOf(AgentRole.USER, AgentRole.ASSISTANT), stored.map { it.role })
        assertEquals(listOf("hi", "你好，世界"), stored.map { it.content })
    }

    @Test
    fun `stored history is sent before the new user message`() {
        val memory = RecordingMemory().apply {
            sessions["session"] = mutableListOf(
                AgentMessage(AgentRole.USER, "旧问题"),
                AgentMessage(AgentRole.ASSISTANT, "旧回答"),
            )
        }
        val model = ScriptedModel(mutableListOf(textReply("新回答")))

        runBlocking { brain(model = model, memory = memory).send("session", "新问题") }

        assertEquals(
            listOf("旧问题", "旧回答", "新问题"),
            model.requests.single().messages.map { it.content },
        )
    }

    @Test
    fun `a tool call is executed and its result is fed back for the final answer`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read", arguments = """{"path":"a.txt"}""")),
                ),
                textReply("文件里写着 hello"),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")))

        val reply = runBlocking { brain(model = model, tools = registry).send("session", "读 a.txt") }

        assertEquals("文件里写着 hello", reply.message.content)
        assertEquals(2, model.requests.size)
        assertEquals(listOf("file_read"), model.requests[0].tools.map { it.name })
        // Native protocol: the assistant's tool-call turn precedes the tool result message.
        val messages = model.requests[1].messages
        val assistantTurn = messages[messages.size - 2]
        assertEquals(AgentRole.ASSISTANT, assistantTurn.role)
        assertEquals(
            listOf(AgentToolCall(id = "call_1", name = "file_read", argumentsJson = """{"path":"a.txt"}""")),
            assistantTurn.toolCalls,
        )
        val toolMessage = messages.last()
        assertEquals(AgentRole.TOOL, toolMessage.role)
        assertTrue(toolMessage.content, toolMessage.content.contains("hello"))
        assertEquals("file_read", toolMessage.name)
        assertEquals("call_1", toolMessage.toolCallId)
        assertEquals(
            listOf(AgentEvent.Kind.TOOL_CALL, AgentEvent.Kind.TOOL_RESULT),
            reply.events.map { it.kind },
        )
        assertEquals("call_1", reply.events.last().toolCallId)
    }

    @Test
    fun `tool turns are remembered so stored history replays the native protocol`() {
        val memory = RecordingMemory()
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read", arguments = """{"path":"a.txt"}""")),
                ),
                textReply("读完了"),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")))

        runBlocking { brain(model = model, tools = registry, memory = memory).send("session", "读 a.txt") }

        val stored = memory.sessions.getValue("session")
        assertEquals(
            listOf(AgentRole.USER, AgentRole.ASSISTANT, AgentRole.TOOL, AgentRole.ASSISTANT),
            stored.map { it.role },
        )
        assertEquals(listOf("file_read"), stored[1].toolCalls.map { it.name })
        assertEquals(stored[1].toolCalls.single().id, stored[2].toolCallId)
    }

    @Test
    fun `an unknown tool name becomes an error result instead of a crash`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(AgentMessage(AgentRole.ASSISTANT, ""), events = listOf(toolCall("file_write"))),
                textReply("无法写入"),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")))

        val reply = runBlocking { brain(model = model, tools = registry).send("session", "写文件") }

        assertEquals("无法写入", reply.message.content)
        assertTrue(model.requests[1].messages.last().content.contains("没有名为 file_write 的工具"))
        assertTrue(reply.events.last().text.orEmpty().contains("没有名为 file_write 的工具"))
    }

    @Test
    fun `a missing tool registry degrades to a readable result`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(AgentMessage(AgentRole.ASSISTANT, ""), events = listOf(toolCall("file_read"))),
                textReply("没有工具"),
            ),
        )

        val reply = runBlocking { brain(model = model).send("session", "读文件") }

        assertEquals("没有工具", reply.message.content)
        assertTrue(model.requests[1].messages.last().content.contains("工具系统未启用"))
        assertTrue(reply.events.last().text.orEmpty().contains("工具系统未启用"))
    }

    @Test
    fun `a model failure becomes an error reply instead of an exception`() {
        val model = ScriptedModel(mutableListOf()).apply {
            failure = SecurityException("PERMISSION_DENIED: 插件未获授权 NETWORK")
        }

        val reply = runBlocking { brain(model = model).send("session", "hi") }

        assertTrue(reply.message.content, reply.message.content.contains("模型调用失败"))
        assertTrue(reply.message.content, reply.message.content.contains("PERMISSION_DENIED"))
        assertEquals(AgentEvent.Kind.ERROR, reply.events.single().kind)
    }

    @Test
    fun `a requested model the provider does not serve is reported without calling it`() {
        val model = ScriptedModel(mutableListOf(textReply("never sent")))
        val requested = ModelRef(provider = "ev.buan.plugins.agent.model", model = "missing-model")

        val reply = runBlocking { brain(model = model).send("session", "hi", requested) }

        assertTrue(reply.message.content, reply.message.content.contains("未找到模型 missing-model"))
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `memory failures do not lose the turn`() {
        val memory = RecordingMemory().apply {
            readFailure = IOException("read broken")
            writeFailure = IOException("write broken")
        }
        val model = ScriptedModel(mutableListOf(textReply("仍然能回答")))

        val reply = runBlocking { brain(model = model, memory = memory).send("session", "hi") }

        assertEquals("仍然能回答", reply.message.content)
        assertEquals(listOf("hi"), model.requests.single().messages.map { it.content })
    }

    @Test
    fun `tool rounds are bounded and synthesise an answer when the model stops producing text`() {
        val replies = MutableList(MAX_REPLIES) { index ->
            AgentReply(
                message = AgentMessage(AgentRole.ASSISTANT, ""),
                events = listOf(toolCall("file_read", id = "call_$index")),
            )
        }
        val model = ScriptedModel(replies)
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "again")))

        val reply = runBlocking { brain(model = model, tools = registry).send("session", "循环") }

        assertEquals(3, model.requests.size)
        assertEquals("工具已执行，但模型没有给出文字总结。", reply.message.content)
        assertEquals(6, reply.events.size)
    }

    @Test
    fun `system prompt is placed before history and the current user message`() {
        val memory = RecordingMemory().apply {
            sessions["session"] = mutableListOf(
                AgentMessage(AgentRole.USER, "旧问题"),
                AgentMessage(AgentRole.ASSISTANT, "旧回答"),
            )
        }
        val model = ScriptedModel(mutableListOf(textReply("好的")))

        runBlocking {
            brain(model = model, memory = memory).send(
                "session",
                "新问题",
                systemPrompt = "你是 EV 的助手，只回答中文。",
            )
        }

        val messages = model.requests.single().messages
        assertEquals(
            listOf(AgentRole.SYSTEM, AgentRole.USER, AgentRole.ASSISTANT, AgentRole.USER),
            messages.map { it.role },
        )
        assertEquals("你是 EV 的助手，只回答中文。", messages.first().content)
    }

    @Test
    fun `system prompt is trimmed and a blank one is ignored`() {
        val model = ScriptedModel(mutableListOf(textReply("一"), textReply("二")))
        val brain = brain(model = model)

        runBlocking {
            brain.send("session", "第一个", systemPrompt = "  你是助手  ")
            brain.send("session", "第二个", systemPrompt = " \n\t ")
        }

        assertEquals(AgentRole.SYSTEM, model.requests[0].messages.first().role)
        assertEquals("你是助手", model.requests[0].messages.first().content)
        assertEquals(listOf(AgentRole.USER), model.requests[1].messages.map { it.role })
        assertEquals("第二个", model.requests[1].messages.single().content)
    }

    @Test
    fun `system prompt is not written into memory`() {
        val memory = RecordingMemory()
        val model = ScriptedModel(mutableListOf(textReply("好的")))

        runBlocking {
            brain(model = model, memory = memory).send(
                "session",
                "hi",
                systemPrompt = "角色设定：简洁",
            )
        }

        val stored = memory.sessions.getValue("session")
        assertEquals(listOf(AgentRole.USER, AgentRole.ASSISTANT), stored.map { it.role })
        assertFalse(stored.any { it.role == AgentRole.SYSTEM })
    }

    @Test
    fun `default and explicit model calls still work without a system prompt`() {
        val memory = RecordingMemory()
        val model = ScriptedModel(mutableListOf(textReply("默认"), textReply("指定")))
        val brain = brain(model = model, memory = memory)
        val requested = ModelRef("fake", "test-model")

        val first = runBlocking { brain.send("session", "默认调用") }
        val second = runBlocking { brain.send("session", "指定调用", requested) }

        assertEquals("默认", first.message.content)
        assertEquals("指定", second.message.content)
        assertEquals(listOf(AgentRole.USER, AgentRole.USER), model.requests.map { it.messages.first().role })
        assertFalse(model.requests.any { request -> request.messages.any { it.role == AgentRole.SYSTEM } })
    }

    @Test
    fun `system prompt stays in front across tool rounds`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(AgentMessage(AgentRole.ASSISTANT, ""), events = listOf(toolCall("file_read"))),
                textReply("读完了"),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")))

        runBlocking {
            brain(model = model, tools = registry).send(
                "session",
                "读文件",
                systemPrompt = "你是助手",
            )
        }

        assertEquals(2, model.requests.size)
        model.requests.forEach { request ->
            assertEquals(AgentRole.SYSTEM, request.messages.first().role)
            assertEquals("你是助手", request.messages.first().content)
        }
        assertTrue(model.requests[1].messages.last().content.contains("hello"))
    }

    @Test
    fun `usage of a single completion is returned`() {
        val usage = AgentUsage(promptTokens = 10, completionTokens = 4, totalTokens = 14)
        val model = ScriptedModel(mutableListOf(textReply("ok", usage = usage)))

        val reply = runBlocking { brain(model = model).send("session", "hi") }

        assertEquals(usage, reply.usage)
    }

    @Test
    fun `usage across tool rounds keeps the last prompt and sums completion and total`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read")),
                    usage = AgentUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
                ),
                textReply(
                    "done",
                    usage = AgentUsage(promptTokens = 30, completionTokens = 7, totalTokens = 37),
                ),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")))

        val reply = runBlocking { brain(model = model, tools = registry).send("session", "read") }

        assertEquals(
            AgentUsage(promptTokens = 30, completionTokens = 12, totalTokens = 52),
            reply.usage,
        )
    }

    @Test
    fun `a turn whose calls report no usage has none`() {
        val model = ScriptedModel(mutableListOf(textReply("no usage")))

        val reply = runBlocking { brain(model = model).send("session", "hi") }

        assertNull(reply.usage)
    }

    @Test
    fun `reported zero is kept while unreported usage fields stay null`() {
        val model = ScriptedModel(mutableListOf(textReply("ok", usage = AgentUsage(promptTokens = 0))))

        val reply = runBlocking { brain(model = model).send("session", "hi") }

        assertEquals(AgentUsage(promptTokens = 0, completionTokens = null, totalTokens = null), reply.usage)
    }

    // ------------------------------------------------------------ approval

    @Test
    fun `a dangerous tool without an approval gate is refused fail-closed`() {
        val tool = FakeTool(ToolSpec("run_shell", "执行命令", "{}", dangerous = true), "exit=0")
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("run_shell", arguments = """{"command":"reboot"}""")),
                ),
                textReply("被拦下了"),
            ),
        )

        val reply = runBlocking {
            brain(model = model, tools = FakeRegistry(listOf(tool))).send("session", "重启手机")
        }

        assertEquals(0, tool.invocations)
        val result = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.single()
        assertTrue(result.text.orEmpty(), result.text.orEmpty().contains("已拦截"))
        assertTrue(result.text.orEmpty().contains("eta-ui"))
        // The refusal is fed back as the tool result, so the model can explain it.
        assertEquals("被拦下了", reply.message.content)
    }

    @Test
    fun `a denied dangerous call does not run and the model sees the refusal`() {
        val tool = FakeTool(ToolSpec("run_shell", "执行命令", "{}", dangerous = true), "exit=0")
        val gate = ScriptedGate(ApprovalDecision.DENY)
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("run_shell")),
                ),
                textReply("好的，没有执行"),
            ),
        )

        val reply = runBlocking {
            brain(model = model, tools = FakeRegistry(listOf(tool)), approval = gate)
                .send("session", "重启手机")
        }

        assertEquals(0, tool.invocations)
        assertEquals(listOf("run_shell"), gate.asked)
        val result = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.single()
        assertEquals("用户拒绝了本次执行", result.text)
    }

    @Test
    fun `an allowed dangerous call runs, and a broken gate refuses rather than runs`() {
        val tool = FakeTool(ToolSpec("run_shell", "执行命令", "{}", dangerous = true), "exit=0")
        val allow = ScriptedGate(ApprovalDecision.ALLOW_ONCE)
        val allowModel = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("run_shell")),
                ),
                textReply("已执行"),
            ),
        )

        val reply = runBlocking {
            brain(model = allowModel, tools = FakeRegistry(listOf(tool)), approval = allow)
                .send("session", "重启手机")
        }

        assertEquals(1, tool.invocations)
        assertEquals("exit=0", reply.events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.single().text)

        val brokenTool = FakeTool(ToolSpec("run_shell", "执行命令", "{}", dangerous = true), "exit=0")
        val brokenGate = ScriptedGate(ApprovalDecision.ALLOW_ONCE, failWith = IOException("ui died"))
        val brokenModel = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("run_shell")),
                ),
                textReply("通道出错"),
            ),
        )

        val brokenReply = runBlocking {
            brain(model = brokenModel, tools = FakeRegistry(listOf(brokenTool)), approval = brokenGate)
                .send("session", "重启手机")
        }

        assertEquals(0, brokenTool.invocations)
        val result = brokenReply.events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.single()
        assertTrue(result.text.orEmpty(), result.text.orEmpty().contains("审批通道出错"))
    }

    @Test
    fun `an ordinary tool never touches the approval gate`() {
        val tool = FakeTool(ToolSpec("file_read", "读文件", "{}"), "hello")
        val gate = ScriptedGate(ApprovalDecision.DENY)
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read")),
                ),
                textReply("读完了"),
            ),
        )

        runBlocking {
            brain(model = model, tools = FakeRegistry(listOf(tool)), approval = gate)
                .send("session", "读文件")
        }

        assertEquals(1, tool.invocations)
        assertTrue(gate.asked.isEmpty())
    }

    // ------------------------------------------------------------------ fakes

    private fun brain(
        model: ModelService? = null,
        tools: ToolRegistry? = null,
        memory: MemoryService? = null,
        approval: ApprovalService? = null,
    ): AgentBrainService = AgentBrainService { BrainCapabilities(model, tools, memory, approval) }

    private fun textReply(text: String, usage: AgentUsage? = null): AgentReply =
        AgentReply(
            message = AgentMessage(AgentRole.ASSISTANT, text),
            model = ModelRef("fake", "test-model"),
            usage = usage,
        )

    private fun toolCall(name: String, id: String = "call_1", arguments: String = "{}"): AgentEvent =
        AgentEvent(
            kind = AgentEvent.Kind.TOOL_CALL,
            toolName = name,
            toolCallId = id,
            argumentsJson = arguments,
        )

    private data class Request(
        val model: ModelRef,
        val messages: List<AgentMessage>,
        val tools: List<ToolSpec>,
    )

    private class ScriptedModel(
        private val replies: MutableList<AgentReply>,
        override val models: List<ModelRef> = listOf(ModelRef("fake", "test-model")),
    ) : ModelService {
        val requests = mutableListOf<Request>()
        var failure: Throwable? = null

        override suspend fun complete(
            model: ModelRef,
            messages: List<AgentMessage>,
            tools: List<ToolSpec>,
        ): AgentReply {
            requests += Request(model, messages.toList(), tools.toList())
            failure?.let { throw it }
            check(replies.isNotEmpty()) { "ScriptedModel ran out of replies" }
            return replies.removeAt(0)
        }
    }

    private class RecordingMemory : MemoryService {
        val sessions = mutableMapOf<String, MutableList<AgentMessage>>()
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null

        override suspend fun history(sessionId: String): List<AgentMessage> {
            readFailure?.let { throw it }
            return sessions[sessionId].orEmpty()
        }

        override suspend fun append(sessionId: String, message: AgentMessage) {
            writeFailure?.let { throw it }
            sessions.getOrPut(sessionId) { mutableListOf() } += message
        }

        override suspend fun clear(sessionId: String) {
            sessions.remove(sessionId)
        }
    }

    private class FakeRegistry(tools: List<Tool>) : ToolRegistry {
        private val byName = tools.associateBy { it.spec.name }
        private val ordered = tools.toList()

        override fun specs(): List<ToolSpec> = ordered.map { it.spec }

        override fun lookup(name: String): Tool? = byName[name]

        override fun register(owner: String, tools: List<Tool>) =
            throw UnsupportedOperationException("fake registry is read-only")

        override fun unregister(owner: String) = Unit
    }

    private class FakeTool(
        override val spec: ToolSpec,
        private val result: String,
    ) : Tool {
        var invocations = 0
            private set

        override suspend fun invoke(argumentsJson: String): String {
            invocations++
            return result
        }
    }

    /** A gate that answers every request the same way, or fails like a dead UI would. */
    private class ScriptedGate(
        private val decision: ApprovalDecision,
        private val failWith: Throwable? = null,
    ) : ApprovalService {
        val asked = mutableListOf<String>()

        override suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision {
            asked += toolName
            failWith?.let { throw it }
            return decision
        }
    }

    private companion object {
        /** More than the brain's per-turn limit, so the bound is actually exercised. */
        const val MAX_REPLIES = 5
    }
}
