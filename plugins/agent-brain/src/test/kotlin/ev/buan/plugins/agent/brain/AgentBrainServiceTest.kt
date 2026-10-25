package ev.buan.plugins.agent.brain

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
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
import kotlinx.coroutines.CancellationException
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
    fun `repeated identical tool calls stop the turn and a summary call follows`() {
        // Same tool, same arguments, same result every round: the repeat guard, not a round cap,
        // is what ends this now. The third attempt stops the turn, then one tool-free summary call
        // runs so the user is not left with half an answer.
        val replies = MutableList(MAX_REPLIES) { index ->
            AgentReply(
                message = AgentMessage(AgentRole.ASSISTANT, ""),
                events = listOf(toolCall("file_read", id = "call_$index")),
            )
        }
        val model = ScriptedModel(replies)
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "again")))

        val reply = runBlocking { brain(model = model, tools = registry).send("session", "循环") }

        assertEquals(AgentStop.Reason.REPEATED_CALLS, reply.stop?.reason)
        assertEquals("3 tool rounds then the summary call", 4, model.requests.size)
        assertTrue("summary call carries no tools", model.requests[3].tools.isEmpty())
        assertEquals(reply.stop?.toolCalls, 3)
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
        assertTrue(result.text.orEmpty().contains("ev-ui"))
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

    /**
     * L3 acceptance: one task that needs five tool calls.
     *
     * Rounds count model calls, not actions, so a three-round cap cannot finish this: the loop stops
     * after the second action with no summary. This is the case the budget work has to make pass.
     */
    @Test
    fun `L3 a five-call task completes and ends with a summary`() {
        val plan = listOf("fs_mkdir", "fs_write", "fs_write", "fs_write", "fs_list")
        val replies = plan.mapIndexed { index, name ->
            AgentReply(
                message = AgentMessage(AgentRole.ASSISTANT, ""),
                events = listOf(toolCall(name, id = "call_$index", arguments = """{"n":$index}""")),
            )
        }.toMutableList()
        replies += textReply("已建目录并写入三个文件")
        val model = ScriptedModel(replies)
        val tools = plan.distinct().map { FakeTool(ToolSpec(it, "工具 $it", "{}"), "ok") }
        val registry = FakeRegistry(tools)

        val reply = runBlocking { brain(model, registry).send("session", "建目录，写三个文件，再列出来") }

        assertEquals("已建目录并写入三个文件", reply.message.content)
        assertEquals("所有工具都应执行", plan.size, tools.sumOf { it.invocations })
        assertEquals(plan.size + 1, model.requests.size)
    }

    // ------------------------------------------------------- budgets, guards, recovery

    @Test
    fun `the round budget stops the turn and one summary call follows`() {
        // Distinct arguments each round so the repeat guard cannot fire first.
        val replies = MutableList(3) { index ->
            AgentReply(
                message = AgentMessage(AgentRole.ASSISTANT, ""),
                events = listOf(
                    toolCall("file_read", id = "call_$index", arguments = """{"n":$index}"""),
                ),
            )
        }.toMutableList()
        replies += textReply("已完成的部分：读了三段")
        val model = ScriptedModel(replies)
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "ok")))

        val reply = runBlocking {
            brain(Budget(maxRounds = 3), model, registry).send("session", "多步")
        }

        assertEquals(AgentStop.Reason.ROUNDS, reply.stop?.reason)
        assertEquals(3, reply.stop?.rounds)
        assertEquals(4, model.requests.size)
        assertTrue("收尾调用不带工具", model.requests.last().tools.isEmpty())
        assertEquals("已完成的部分：读了三段", reply.message.content)
    }

    @Test
    fun `a tool budget spent mid-round answers every remaining call`() {
        val names = listOf("t1", "t2", "t3")
        val firstRound = AgentReply(
            message = AgentMessage(AgentRole.ASSISTANT, ""),
            events = names.mapIndexed { index, name ->
                toolCall(name, id = "c$index", arguments = """{"i":$index}""")
            },
        )
        val model = ScriptedModel(mutableListOf(firstRound, textReply("只做完了一个")))
        val tools = names.map { FakeTool(ToolSpec(it, "工具 $it", "{}"), "ok") }

        val reply = runBlocking {
            brain(Budget(maxToolCalls = 1), model, FakeRegistry(tools)).send("session", "三件事")
        }

        assertEquals(AgentStop.Reason.TOOL_BUDGET, reply.stop?.reason)
        assertEquals("只有一个工具真正执行", 1, tools.sumOf { it.invocations })
        // Every call must be answered or the follow-up request is an invalid conversation.
        val toolMessages = model.requests[1].messages.filter { it.role == AgentRole.TOOL }
        assertEquals("每条 call 都要有 TOOL 回复", 3, toolMessages.size)
        assertEquals(2, toolMessages.count { it.content.contains("预算用尽") })
    }

    @Test
    fun `a tool that exceeds its timeout becomes a timeout result`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("slow_tool")),
                ),
                textReply("工具超时了"),
            ),
        )
        val registry = FakeRegistry(
            listOf(SlowTool(ToolSpec("slow_tool", "慢工具", "{}"), delayMs = 2_000)),
        )

        val reply = runBlocking {
            brain(Budget(toolTimeoutMs = 100), model, registry).send("session", "试试")
        }

        val result = reply.events.filter { it.kind == AgentEvent.Kind.TOOL_RESULT }.single()
        assertTrue(result.text.orEmpty(), result.text.orEmpty().contains("超时"))
        assertEquals("工具超时了", reply.message.content)
    }

    @Test
    fun `a failed summary call still returns the turn with its stop reason`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read")),
                ),
            ),
        )
        model.failure = IOException("summary died")
        model.failFromRequest = 2 // first call fine, the wrap-up call blows up
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "ok")))

        val reply = runBlocking {
            brain(Budget(maxRounds = 1), model, registry).send("session", "读一下")
        }

        assertEquals(AgentStop.Reason.ROUNDS, reply.stop?.reason)
        assertTrue(reply.message.content, reply.message.content.contains("预算用尽"))
    }

    @Test
    fun `a repeated call gets one reflection before the guard stops the turn`() {
        val repeat = { id: String ->
            AgentReply(
                message = AgentMessage(AgentRole.ASSISTANT, ""),
                events = listOf(toolCall("file_read", id = id)),
            )
        }
        val model = ScriptedModel(
            mutableListOf(repeat("c1"), repeat("c2"), repeat("c3"), textReply("停了")),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "same")))

        runBlocking { brain(model = model, tools = registry).send("session", "重复") }

        assertTrue(
            "第二次相同调用后应注入反思",
            model.requests[2].messages.any { it.content.contains("结果没有变化") },
        )
    }

    @Test
    fun `L4 the model recovers from a tool error and finishes`() {
        val write = ScriptedTool(
            spec = ToolSpec("file_write", "写文件", "{}"),
            results = listOf("错误：父目录不存在", "ok"),
        )
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(
                        toolCall("file_write", id = "c1", arguments = """{"path":"/bad/x"}"""),
                    ),
                ),
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(
                        toolCall("file_write", id = "c2", arguments = """{"path":"/good/x"}"""),
                    ),
                ),
                textReply("已修正路径并写入"),
            ),
        )

        val reply = runBlocking {
            brain(model = model, tools = FakeRegistry(listOf(write))).send("session", "写一个文件")
        }

        assertEquals(AgentStop.Reason.FINISHED, reply.stop?.reason)
        assertEquals("已修正路径并写入", reply.message.content)
        assertEquals(2, write.arguments.size)
        // The error text has to reach the model, otherwise it cannot correct itself.
        assertTrue(model.requests[1].messages.any { it.content.contains("父目录不存在") })
    }

    @Test
    fun `a reflection never splits the tool messages of one assistant turn`() {
        // Regression: one round asking for the same tool three times put the reflection between
        // result 2 and result 3, and the provider rejected the request with
        //   "An assistant message with 'tool_calls' must be followed by tool messages ...".
        val firstRound = AgentReply(
            message = AgentMessage(AgentRole.ASSISTANT, ""),
            events = listOf(
                toolCall("file_read", id = "c1"),
                toolCall("file_read", id = "c2"),
                toolCall("file_read", id = "c3"),
            ),
        )
        val model = ScriptedModel(mutableListOf(firstRound, textReply("停")))
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "same")))

        runBlocking { brain(model = model, tools = registry).send("session", "读三次") }

        val messages = model.requests[1].messages
        val assistantIndex = messages.indexOfLast { it.role == AgentRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        val rightAfter = messages.drop(assistantIndex + 1).take(3)
        assertEquals(
            "三条 tool 消息必须紧跟 assistant 且连续",
            listOf("c1", "c2", "c3"),
            rightAfter.map { it.toolCallId },
        )
        val nudge = messages.getOrNull(assistantIndex + 4)
        assertEquals("反思排在全部 tool 结果之后", AgentRole.USER, nudge?.role)
        assertTrue(nudge?.content.orEmpty().contains("结果没有变化"))
    }

    // ------------------------------------------------------------- progress stream

    @Test
    fun `progress events follow the documented order`() {
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("file_read")),
                ),
                textReply("读完了"),
            ),
        )
        val registry = FakeRegistry(listOf(FakeTool(ToolSpec("file_read", "读文件", "{}"), "data")))
        val seen = mutableListOf<AgentProgress>()

        runBlocking {
            brain(model = model, tools = registry).sendStreaming("session", "读").collect { seen += it }
        }

        assertEquals(
            listOf(
                AgentProgress.RoundStarted::class,
                AgentProgress.ToolStarted::class,
                AgentProgress.ToolFinished::class,
                AgentProgress.RoundFinished::class,
                AgentProgress.RoundStarted::class,
                AgentProgress.Done::class,
            ),
            seen.map { it::class },
        )
        assertEquals(StepState.Ok, (seen[2] as AgentProgress.ToolFinished).state)
    }

    @Test
    fun `approval waiting does not count against the wall clock`() {
        // The budget is 150ms; the human "thinks" for 300ms. If waiting were charged, the second
        // round would be killed by DEADLINE instead of finishing normally.
        val tool = FakeTool(ToolSpec("run_shell", "执行命令", "{}", dangerous = true), "exit=0")
        val gate = ScriptedGate(ApprovalDecision.ALLOW_ONCE, delayMs = 300)
        val model = ScriptedModel(
            mutableListOf(
                AgentReply(
                    message = AgentMessage(AgentRole.ASSISTANT, ""),
                    events = listOf(toolCall("run_shell")),
                ),
                textReply("执行完成"),
            ),
        )

        val reply = runBlocking {
            brain(Budget(deadlineMs = 150), model, FakeRegistry(listOf(tool)), gate)
                .send("session", "重启")
        }

        assertEquals(AgentStop.Reason.FINISHED, reply.stop?.reason)
        assertEquals("执行完成", reply.message.content)
    }

    @Test
    fun `cancelling the stream propagates instead of turning into a failure reply`() {
        val hangingModel = object : ModelService {
            override val models: List<ModelRef> = listOf(ModelRef("fake", "test-model"))
            override suspend fun complete(
                model: ModelRef,
                messages: List<AgentMessage>,
                tools: List<ToolSpec>,
            ): AgentReply = kotlinx.coroutines.awaitCancellation()
        }
        val seen = mutableListOf<AgentProgress>()
        var cancelled = false

        runBlocking {
            try {
                kotlinx.coroutines.withTimeout(200) {
                    brain(model = hangingModel).sendStreaming("session", "挂起").collect { seen += it }
                }
            } catch (cancel: CancellationException) {
                cancelled = true
            }
        }

        assertTrue("取消必须向外传播", cancelled)
        assertTrue("取消后不应再产生 Done", seen.none { it is AgentProgress.Done })
    }

    // ------------------------------------------------------------------ fakes

    private fun brain(
        model: ModelService? = null,
        tools: ToolRegistry? = null,
        memory: MemoryService? = null,
        approval: ApprovalService? = null,
    ): AgentBrainService = AgentBrainService(capabilities = { BrainCapabilities(model, tools, memory, approval) })

    private fun brain(
        budget: Budget,
        model: ModelService,
        tools: ToolRegistry? = null,
        approval: ApprovalService? = null,
        memory: MemoryService? = null,
    ): AgentBrainService = AgentBrainService(
        capabilities = { BrainCapabilities(model, tools, memory, approval) },
        budget = budget,
    )

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

        /** Request number (1-based) from which [failure] is thrown; null means never. */
        var failFromRequest: Int? = null

        override suspend fun complete(
            model: ModelRef,
            messages: List<AgentMessage>,
            tools: List<ToolSpec>,
        ): AgentReply {
            requests += Request(model, messages.toList(), tools.toList())
            val failAt = failFromRequest
            val boom = failure
            if (boom != null && (failAt == null || requests.size >= failAt)) throw boom
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

    /** Returns a different result per invocation, in order; the last one repeats. */
    private class ScriptedTool(
        override val spec: ToolSpec,
        private val results: List<String>,
    ) : Tool {
        val arguments = mutableListOf<String>()

        override suspend fun invoke(argumentsJson: String): String {
            arguments += argumentsJson
            return results.getOrElse(arguments.size - 1) { results.last() }
        }
    }

    /** Never answers within the test's timeout. */
    private class SlowTool(override val spec: ToolSpec, private val delayMs: Long) : Tool {
        override suspend fun invoke(argumentsJson: String): String {
            kotlinx.coroutines.delay(delayMs)
            return "too late"
        }
    }

    /** A gate that answers every request the same way, or fails like a dead UI would. */
    private class ScriptedGate(
        private val decision: ApprovalDecision,
        private val failWith: Throwable? = null,
        private val delayMs: Long = 0,
    ) : ApprovalService {
        val asked = mutableListOf<String>()

        override suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision {
            asked += toolName
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            failWith?.let { throw it }
            return decision
        }
    }

    private companion object {
        /** Enough scripted tool replies to exercise the guard under test, whatever it is. */
        const val MAX_REPLIES = 5
    }
}
