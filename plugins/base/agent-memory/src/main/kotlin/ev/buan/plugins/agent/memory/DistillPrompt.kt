package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.MemoryNote
import org.json.JSONArray
import org.json.JSONObject

/** One note the distillation model proposed, before the store turns it into a [MemoryNote]. */
internal data class DistillDraft(
    val title: String,
    val body: String,
    val kind: String,
    val keywords: List<String>,
    /** L1 references, e.g. `l1:<session>:12`; never empty. */
    val sources: List<String>,
)

/**
 * The distillation prompt and its parser, kept pure so both can be tested without a model.
 *
 * The parser is deliberately strict: a note without at least one in-range `sources` is dropped.
 * That is the whole point of the traceability chain - a distilled note the user cannot trace back
 * to something they said is worse than no note, because it looks like the agent made it up.
 */
internal object DistillPrompt {

    /** A batch writes at most this many notes; a chat that yields more is a prompt problem. */
    const val MAX_NOTES = 5

    private const val MAX_TITLE_CHARS = 40
    private const val MAX_BODY_CHARS = 500
    private const val MAX_KEYWORDS = 8
    private const val MAX_SOURCES = 8

    /** The model's input is bounded: the oldest uncovered messages are summarised, not replayed. */
    private const val MAX_TRANSCRIPT_CHARS = 12_000
    private const val MAX_MESSAGE_CHARS = 400

    fun system(existingTitles: List<String>): String = buildString {
        appendLine("你是记忆提炼器。读一段对话，挑出值得跨会话长期保留的内容，写成记事本条目。")
        appendLine()
        appendLine("只记稳定的东西：用户的偏好与习惯、长期有效的约定、正在推进的任务、关于设备或环境的事实。")
        appendLine("不要记：一次性的请求、寒暄、能从对话里随手再问出来的东西、模型自己的推测。")
        appendLine()
        appendLine("不要重复已有条目（标题见下）。已有条目里的信息不要换个说法再写一遍；")
        appendLine("如果新对话推翻了旧结论，就写新条目，不要试图合并。")
        appendLine()
        appendLine("输出严格的 JSON，不要 Markdown 代码围栏，不要解释：")
        appendLine(
            """{"notes":[{"title":"≤40字","body":"≤500字","kind":"preference|fact|task|convention","keywords":["…"],"sources":[1,2]}]}""",
        )
        appendLine()
        appendLine("sources 是这条结论依据的消息序号（取 user 消息里的 [n]），至少一个，必须落在本次范围内。")
        appendLine("没有值得记的就输出 {\"notes\":[]}。最多 $MAX_NOTES 条。")
        if (existingTitles.isNotEmpty()) {
            appendLine()
            appendLine("已有条目标题（不要重复）：")
            existingTitles.take(40).forEach { appendLine("- $it") }
        }
    }

    /**
     * The uncovered messages as numbered lines. [from] and [to] are 1-based inclusive positions in
     * the session history, which is also what `sources` points at.
     */
    fun transcript(messages: List<AgentMessage>, from: Int, to: Int): String {
        val start = (from - 1).coerceAtLeast(0)
        val end = to.coerceAtMost(messages.size)
        if (start >= end) return ""
        val out = StringBuilder()
        var budget = MAX_TRANSCRIPT_CHARS
        for (index in start until end) {
            val message = messages[index]
            if (message.role == AgentRole.SYSTEM) continue
            val line = line(index + 1, message)
            if (line.length + 1 > budget) {
                out.appendLine("…（片段过长，后面 ${end - index} 条未展开）")
                break
            }
            out.appendLine(line)
            budget -= line.length + 1
        }
        return out.toString().trimEnd()
    }

    /**
     * Parses [reply] into drafts.
     *
     * Anything unparseable is dropped rather than failing the batch: a model that answered in
     * prose still leaves the cursor untouched, so the next idle moment retries with the same
     * messages instead of losing them.
     */
    fun parse(
        reply: String,
        sessionId: String,
        from: Int,
        to: Int,
        existingTitles: Collection<String>,
    ): List<DistillDraft> {
        val document = extractObject(reply) ?: return emptyList()
        val items = document.optJSONArray("notes") ?: return emptyList()
        val seen = existingTitles.mapTo(HashSet()) { normalizeTitle(it) }
        val drafts = ArrayList<DistillDraft>()
        for (index in 0 until items.length()) {
            if (drafts.size >= MAX_NOTES) break
            val item = items.optJSONObject(index) ?: continue
            val title = item.optString("title", "").trim().take(MAX_TITLE_CHARS)
            val body = item.optString("body", "").trim().take(MAX_BODY_CHARS)
            if (title.isEmpty() || body.isEmpty()) continue
            if (!seen.add(normalizeTitle(title))) continue
            val sources = sourcesOf(item.optJSONArray("sources"), sessionId, from, to)
            if (sources.isEmpty()) continue
            drafts += DistillDraft(
                title = title,
                body = body,
                kind = kindOf(item.optString("kind", "")),
                keywords = keywordsOf(item.optJSONArray("keywords")),
                sources = sources,
            )
        }
        return drafts
    }

    /** Strips a code fence and any prose around the JSON object. */
    private fun extractObject(reply: String): JSONObject? {
        val text = reply.trim()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun sourcesOf(array: JSONArray?, sessionId: String, from: Int, to: Int): List<String> {
        if (array == null) return emptyList()
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val seq = array.optInt(index, 0)
            if (seq in from..to) {
                result += "l1:$sessionId:$seq"
                if (result.size >= MAX_SOURCES) break
            }
        }
        return result.toList()
    }

    private fun keywordsOf(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index, "").trim().ifEmpty { null } }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun kindOf(raw: String): String {
        val kind = raw.trim().lowercase()
        return if (kind in MemoryNote.KINDS) kind else MemoryNote.KIND_FACT
    }

    private fun line(seq: Int, message: AgentMessage): String {
        val text = message.content.replace('\n', ' ').trim().take(MAX_MESSAGE_CHARS)
        val label = when (message.role) {
            AgentRole.USER -> "user"
            AgentRole.ASSISTANT -> "assistant"
            AgentRole.TOOL -> "tool(${message.name ?: "?"})"
            AgentRole.SYSTEM -> "system"
        }
        val calls = message.toolCalls
            .takeIf { it.isNotEmpty() }
            ?.joinToString("；") { "${it.name}(${it.argumentsJson.take(120)})" }
        return buildString {
            append("[$seq] $label: ")
            append(text.ifEmpty { "（无文本）" })
            if (!calls.isNullOrEmpty()) append(" → 调用 $calls")
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
