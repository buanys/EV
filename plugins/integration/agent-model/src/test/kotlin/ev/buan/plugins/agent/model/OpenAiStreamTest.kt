package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The streaming reassembly, without a network: a `chat.completion.chunk` stream is text
 * fragments plus tool-call fragments keyed by index, and this pins that both survive the trip.
 */
class OpenAiStreamTest {

    private val requested = ModelRef(provider = "p", model = "m")

    @Test
    fun `text fragments concatenate and each one is reported once`() {
        val stream = OpenAiStream(requested)

        assertEquals("你", stream.feed("""{"choices":[{"delta":{"role":"assistant","content":"你"}}]}"""))
        assertEquals("好", stream.feed("""{"choices":[{"delta":{"content":"好"}}]}"""))
        assertNull("a chunk without text reports nothing", stream.feed("""{"choices":[{"delta":{}}]}"""))

        assertEquals("你好", stream.reply().message.content)
    }

    @Test
    fun `tool call fragments are joined by index into one call`() {
        val stream = OpenAiStream(requested)

        stream.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"file_read","arguments":"{\"pa"}}]}}]}""",
        )
        stream.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.txt\"}"}}]}}]}""",
        )
        stream.feed("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")

        val reply = stream.reply()
        val call = reply.message.toolCalls.single()
        assertEquals("call_a", call.id)
        assertEquals("file_read", call.name)
        assertEquals("""{"path":"a.txt"}""", call.argumentsJson)
        // The same call has to appear as an event: that is what the loop replays next round.
        assertEquals("call_a", reply.events.single().toolCallId)
        assertEquals("tool_calls", stream.finishReason())
    }

    @Test
    fun `usage and model reported on the final chunk survive`() {
        val stream = OpenAiStream(requested)

        stream.feed(
            """{"model":"m-2026","choices":[{"delta":{"content":"x"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
        )

        val reply = stream.reply()
        assertEquals(10, reply.usage?.promptTokens)
        assertEquals(5, reply.usage?.completionTokens)
        assertEquals(15, reply.usage?.totalTokens)
        assertEquals("m-2026", reply.model?.model)
    }

    @Test
    fun `an error payload fails loudly instead of producing an empty reply`() {
        val stream = OpenAiStream(requested)

        val failure = runCatching {
            stream.feed("""{"error":{"message":"rate limited"}}""")
        }.exceptionOrNull()

        assert(failure is OpenAiFailure) { "expected OpenAiFailure, got $failure" }
    }
}
