package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.MemoryNote
import ev.buan.plugins.agent.api.MemoryStoreService
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The notebook tools, wired to a [MemoryStoreService].
 *
 * Every call returns JSON, and a rejected call returns an `error` object instead of throwing: one
 * bad model argument must not abort the whole agent turn. The store itself throws on a corrupt
 * document, which is deliberate - that is a real problem, and the error text reaches the model so
 * it can tell the user rather than silently losing notes.
 */
internal object MemoryTools {

    fun all(store: MemoryStoreService): List<Tool> = listOf(
        MemoryTool(MemoryToolSpecs.remember) { args -> remember(store, args) },
        MemoryTool(MemoryToolSpecs.recall) { args -> recall(store, args) },
        MemoryTool(MemoryToolSpecs.read) { args -> read(store, args) },
        MemoryTool(MemoryToolSpecs.update) { args -> update(store, args) },
        MemoryTool(MemoryToolSpecs.delete) { args -> archive(store, args) },
        MemoryTool(MemoryToolSpecs.pin) { args -> pin(store, args, pinned = true) },
        MemoryTool(MemoryToolSpecs.unpin) { args -> pin(store, args, pinned = false) },
    )

    private fun remember(store: MemoryStoreService, args: JSONObject): String {
        val text = args.optString("text", "").trim()
        require(text.isNotEmpty()) { "缺少 text 参数" }
        val note = store.remember(
            text = text,
            kind = args.optString("kind", MemoryNote.KIND_FACT),
            title = args.optString("title", "").trim().ifEmpty { null },
        )
        return JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("kind", note.kind)
            .toString()
    }

    private fun recall(store: MemoryStoreService, args: JSONObject): String {
        val query = args.optString("query", "").trim()
        require(query.isNotEmpty()) { "缺少 query 参数" }
        val hits = store.recall(query, args.optInt("limit", DEFAULT_LIMIT))
        val array = JSONArray()
        hits.forEach { hit ->
            array.put(
                JSONObject()
                    .put("id", hit.id)
                    .put("title", hit.title)
                    .put("kind", hit.kind)
                    .put("updatedAt", format(hit.updatedAt)),
            )
        }
        return JSONObject().put("query", query).put("hits", array).toString()
    }

    private fun read(store: MemoryStoreService, args: JSONObject): String {
        val id = requireId(args)
        val note = store.read(id) ?: return errorOf("没有这条记忆：$id")
        return note.toJson().toString()
    }

    private fun update(store: MemoryStoreService, args: JSONObject): String {
        val id = requireId(args)
        val keywords = args.optJSONArray("keywords")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index, "").trim().ifEmpty { null }
            }
        }
        val changed = store.update(
            id = id,
            title = args.optText("title"),
            body = args.optText("body"),
            keywords = keywords,
        )
        return JSONObject().put("id", id).put("updated", changed).toString()
    }

    private fun archive(store: MemoryStoreService, args: JSONObject): String {
        val id = requireId(args)
        val archived = store.archive(id)
        return JSONObject().put("id", id).put("archived", archived).toString()
    }

    private fun pin(store: MemoryStoreService, args: JSONObject, pinned: Boolean): String {
        val id = requireId(args)
        val changed = store.pin(id, pinned)
        return JSONObject().put("id", id).put("pinned", pinned).put("changed", changed).toString()
    }

    private fun requireId(args: JSONObject): String {
        val id = args.optString("id", "").trim()
        require(id.isNotEmpty()) { "缺少 id 参数" }
        return id
    }

    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().ifEmpty { null }
    }

    private fun format(millis: Long): String =
        if (millis <= 0) {
            ""
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
        }

    private const val DEFAULT_LIMIT = 5
}

/** One note tool: parse the model's JSON, run the operation, turn a failure into a result. */
internal class MemoryTool(
    override val spec: ToolSpec,
    private val operation: (JSONObject) -> String,
) : Tool {
    override suspend fun invoke(argumentsJson: String): String = runCatching {
        operation(memoryArgumentsOf(argumentsJson))
    }.getOrElse { error ->
        errorOf("${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}")
    }
}

private fun memoryArgumentsOf(argumentsJson: String): JSONObject =
    if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)

private fun errorOf(message: String): String = JSONObject().put("error", message).toString()
