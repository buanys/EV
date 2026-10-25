package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelParameters
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure-function tests for the OpenAI-compatible wire mapping: no network, no Android, no storage.
 *
 * Both directions are checked against parsed JSON rather than string literals, so a formatting
 * change in the serializer does not fail the suite while an actual mapping change does.
 */
class OpenAiProtocolTest {

    private val requested = ModelRef(provider = "ev.buan.plugins.agent.model", model = "gpt-requested")

    // ------------------------------------------------------------------ request body

    @Test
    fun `request body keeps message order and maps every role`() {
        val body = OpenAiProtocol.requestBody(
            model = "gpt-test",
            messages = listOf(
                AgentMessage(AgentRole.SYSTEM, "be brief"),
                AgentMessage(AgentRole.USER, "hello"),
                AgentMessage(AgentRole.ASSISTANT, "hi"),
                AgentMessage(
                    role = AgentRole.TOOL,
                    content = """{"ok":true}""",
                    name = "read_file",
                    toolCallId = "call_1",
                ),
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("gpt-test", root["model"]!!.jsonPrimitive.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals(
            listOf("system", "user", "assistant", "tool"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertEquals("be brief", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val toolMessage = messages[3].jsonObject
        assertEquals("read_file", toolMessage["name"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
        // An empty tools list must be omitted, not serialized as [].
        assertFalse(root.containsKey("tools"))
    }

    @Test
    fun `assistant tool calls are serialized with null content on the request wire`() {
        val body = OpenAiProtocol.requestBody(
            model = "gpt-test",
            messages = listOf(
                AgentMessage(AgentRole.USER, "读 a.txt"),
                AgentMessage(
                    role = AgentRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        AgentToolCall(id = "call_1", name = "read_file", argumentsJson = """{"path":"a.txt"}"""),
                    ),
                ),
                AgentMessage(
                    role = AgentRole.TOOL,
                    content = "hello",
                    name = "read_file",
                    toolCallId = "call_1",
                ),
            ),
        )

        val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        val assistantTurn = messages[1].jsonObject
        // Strict servers reject an empty-string content next to tool_calls; null is the protocol.
        assertEquals(JsonNull, assistantTurn["content"])
        val call = assistantTurn["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val function = call["function"]!!.jsonObject
        assertEquals("read_file", function["name"]!!.jsonPrimitive.content)
        assertEquals("""{"path":"a.txt"}""", function["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank tool arguments degrade to an empty object on the wire`() {
        val body = OpenAiProtocol.requestBody(
            model = "gpt-test",
            messages = listOf(
                AgentMessage(
                    role = AgentRole.ASSISTANT,
                    content = "",
                    toolCalls = listOf(AgentToolCall(id = "call_1", name = "ping", argumentsJson = "  ")),
                ),
            ),
        )

        val call = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .single().jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("{}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool specs become function declarations with a parsed schema`() {
        val schema = """{"type":"object","properties":{"path":{"type":"string"}}}"""
        val body = OpenAiProtocol.requestBody(
            model = "m",
            messages = listOf(AgentMessage(AgentRole.USER, "read it")),
            tools = listOf(ToolSpec(name = "read_file", description = "Reads a file", parametersJson = schema)),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
        val function = root["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject
        assertEquals("read_file", function["name"]!!.jsonPrimitive.content)
        assertEquals("Reads a file", function["description"]!!.jsonPrimitive.content)
        assertEquals("object", function["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed tool schema degrades to an empty object schema`() {
        val body = OpenAiProtocol.requestBody(
            model = "m",
            messages = emptyList(),
            tools = listOf(ToolSpec(name = "broken", description = "", parametersJson = "not json")),
        )

        val parameters = Json.parseToJsonElement(body).jsonObject["tools"]!!
            .jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!
        assertTrue(parameters.jsonObject.isEmpty())
    }

    @Test
    fun `content is JSON-escaped, not spliced into the body`() {
        val content = "line1\nline2 \"quoted\" \\ 反斜杠"
        val body = OpenAiProtocol.requestBody(
            model = "m",
            messages = listOf(AgentMessage(AgentRole.USER, content)),
        )

        val parsed = Json.parseToJsonElement(body).jsonObject["messages"]!!
            .jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(content, parsed)
    }

    @Test
    fun `model parameters are written as temperature top_p and max_tokens`() {
        val body = OpenAiProtocol.requestBody(
            model = "m",
            messages = emptyList(),
            parameters = ModelParameters(temperature = 0.3, topP = 0.9, maxTokens = 512),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(0.3, root["temperature"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.9, root["top_p"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(512, root["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unset model parameters are omitted`() {
        val root = Json.parseToJsonElement(
            OpenAiProtocol.requestBody(
                model = "m",
                messages = emptyList(),
                parameters = ModelParameters(topP = 0.5),
            ),
        ).jsonObject

        assertFalse(root.containsKey("temperature"))
        assertFalse(root.containsKey("max_tokens"))
        assertEquals(0.5, root["top_p"]!!.jsonPrimitive.double, 1e-9)
    }

    // ------------------------------------------------------------------ response parsing

    @Test
    fun `assistant text reply is mapped with the response model`() {
        val body = """
            {
              "id": "chatcmpl-1",
              "model": "gpt-real-2024",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "你好"}, "finish_reason": "stop"}
              ]
            }
        """.trimIndent()

        val reply = OpenAiProtocol.parseReply(body, requested)

        assertEquals(AgentRole.ASSISTANT, reply.message.role)
        assertEquals("你好", reply.message.content)
        assertEquals(ModelRef(requested.provider, "gpt-real-2024"), reply.model)
        assertTrue(reply.events.isEmpty())
        assertNull(reply.usage, "a response without a usage object has no usage")
    }

    @Test
    fun `usage is parsed from the completion response`() {
        val body = """
            {
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ok"}}
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
        """.trimIndent()

        val reply = OpenAiProtocol.parseReply(body, requested)

        assertEquals(AgentUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15), reply.usage)
    }

    @Test
    fun `malformed usage is null instead of a partially wrong row`() {
        val nonObject = """
            {"choices":[{"message":{"content":"ok"}}],"usage":"not an object"}
        """.trimIndent()
        assertNull(OpenAiProtocol.parseReply(nonObject, requested).usage)

        val noReadableFields = """
            {"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":"many","completion_tokens":true}}
        """.trimIndent()
        assertNull(OpenAiProtocol.parseReply(noReadableFields, requested).usage)
    }

    @Test
    fun `partial usage keeps reported fields and leaves the rest null`() {
        val body = """
            {"choices":[{"message":{"content":"ok"}}],"usage":{"completion_tokens":7}}
        """.trimIndent()

        assertEquals(
            AgentUsage(promptTokens = null, completionTokens = 7, totalTokens = null),
            OpenAiProtocol.parseReply(body, requested).usage,
        )
    }

    @Test
    fun `tool calls become TOOL_CALL events correlated by id`() {
        val body = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_a",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{\"path\":\"a.txt\"}"}
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
        """.trimIndent()

        val reply = OpenAiProtocol.parseReply(body, requested)

        assertEquals("", reply.message.content)
        val event = reply.events.single()
        assertEquals(AgentEvent.Kind.TOOL_CALL, event.kind)
        assertEquals("read_file", event.toolName)
        assertEquals("call_a", event.toolCallId)
        assertEquals("""{"path":"a.txt"}""", event.argumentsJson)
        // The assistant message carries the same call, so the brain can replay the native protocol.
        assertEquals(
            listOf(AgentToolCall(id = "call_a", name = "read_file", argumentsJson = """{"path":"a.txt"}""")),
            reply.message.toolCalls,
        )
        // No model field in the body: the requested model stays as the reply identity.
        assertEquals(requested, reply.model)
    }

    @Test
    fun `a missing tool call id is synthesized so results can correlate`() {
        val body = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "tool_calls": [
                      {"type": "function", "function": {"name": "read_file", "arguments": "{}"}}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val reply = OpenAiProtocol.parseReply(body, requested)

        assertEquals("call_0", reply.events.single().toolCallId)
        assertEquals("call_0", reply.message.toolCalls.single().id)
    }

    @Test
    fun `object-shaped tool arguments are serialized as text`() {
        val body = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "tool_calls": [
                      {
                        "id": "call_b",
                        "function": {"name": "write_file", "arguments": {"path": "b.txt", "text": "x"}}
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val event = OpenAiProtocol.parseReply(body, requested).events.single()

        assertEquals("""{"path":"b.txt","text":"x"}""", event.argumentsJson)
    }

    @Test
    fun `content parts array is flattened into assistant text`() {
        val body = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": [
                      {"type": "text", "text": "你"},
                      {"type": "text", "text": "好"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals("你好", OpenAiProtocol.parseReply(body, requested).message.content)
    }

    @Test
    fun `provider error payload becomes an OpenAiFailure carrying its message`() {
        val failure = assertFailsWith<OpenAiFailure> {
            OpenAiProtocol.parseReply(
                """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""",
                requested,
            )
        }

        assertTrue(failure.message.orEmpty().contains("Invalid API key"), failure.message)
    }

    @Test
    fun `malformed or choice-less responses are failures`() {
        assertFailsWith<OpenAiFailure> { OpenAiProtocol.parseReply("not json", requested) }
        assertFailsWith<OpenAiFailure> { OpenAiProtocol.parseReply("""{"id":"x"}""", requested) }
        assertFailsWith<OpenAiFailure> { OpenAiProtocol.parseReply("""{"choices":[]}""", requested) }
        assertFailsWith<OpenAiFailure> { OpenAiProtocol.parseReply("""{"choices":[{"index":0}]}""", requested) }
    }
}
