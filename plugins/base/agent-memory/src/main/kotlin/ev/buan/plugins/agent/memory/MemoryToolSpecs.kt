package ev.buan.plugins.agent.memory

import ev.buan.plugins.agent.api.ToolSpec

/**
 * The notebook tools, as the model sees them.
 *
 * Kept apart from [MemoryTools] so names, descriptions and schemas are reviewable data instead of
 * strings buried in call sites - the same split the file tools use.
 *
 * None of them is `dangerous`: they only touch this plugin's own user-data area, and every call is
 * reversible (`delete_memory` archives, it does not erase). Making the model ask for approval to
 * write its own notes would train it to stop writing them.
 */
internal object MemoryToolSpecs {

    val remember = ToolSpec(
        name = "remember",
        description = "把一条值得跨会话保留的事实写进记事本。适合稳定的偏好、约定、长期任务；" +
            "一次对话里的临时细节不要写。已有几乎相同的条目时会合并进去而不是重复新增；" +
            "要推翻一条旧结论就传 replaces。写入后可以用 pin_memory 让它常驻上下文。",
        parametersJson = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"要记住的内容，一段话，写清结论而不是过程"},
              "kind":{"type":"string","enum":["preference","fact","task","convention"],"description":"条目类型，缺省 fact"},
              "title":{"type":"string","description":"可选，一行标题；缺省从正文首句截取"},
              "replaces":{"type":"string","description":"可选，被这条新结论取代的旧条目 id；旧条目会转为已取代"}
            },"required":["text"],"additionalProperties":false}
        """.trimIndent(),
    )

    val recall = ToolSpec(
        name = "recall",
        description = "在记事本里检索条目，返回 id、标题和类型。回答关于你自己的偏好或历史约定之前先查一次，不要凭印象猜。",
        parametersJson = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"关键词，中文直接写，例如 用户偏好 / 编译环境"},
              "limit":{"type":"integer","description":"返回条数，缺省 5，最多 20"}
            },"required":["query"],"additionalProperties":false}
        """.trimIndent(),
    )

    val read = ToolSpec(
        name = "read_memory",
        description = "读一条记事本的完整正文和来源。recall 只给摘要，需要细节时用它。",
        parametersJson = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"recall 返回的条目 id，例如 m_xxx"}
            },"required":["id"],"additionalProperties":false}
        """.trimIndent(),
    )

    val update = ToolSpec(
        name = "update_memory",
        description = "修改一条记事本的标题、正文或关键词。旧内容被新结论取代时改这一条，不要重复写一条新的。",
        parametersJson = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"要修改的条目 id"},
              "title":{"type":"string","description":"新标题，省略表示不改"},
              "body":{"type":"string","description":"新正文，省略表示不改"},
              "keywords":{"type":"array","items":{"type":"string"},"description":"新关键词，省略表示不改"}
            },"required":["id"],"additionalProperties":false}
        """.trimIndent(),
    )

    val delete = ToolSpec(
        name = "delete_memory",
        description = "归档一条记事本条目（不是物理删除，来源链仍可追溯）。内容确认为错或过期时用。",
        parametersJson = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"要归档的条目 id"}
            },"required":["id"],"additionalProperties":false}
        """.trimIndent(),
    )

    val pin = ToolSpec(
        name = "pin_memory",
        description = "把一条记忆放进常驻区：之后每一轮都会看到它。只给真正长期有效、每轮都用得上的条目用，常驻区有字数上限。",
        parametersJson = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"要常驻的条目 id"}
            },"required":["id"],"additionalProperties":false}
        """.trimIndent(),
    )

    val unpin = ToolSpec(
        name = "unpin_memory",
        description = "把一条记忆移出常驻区（条目本身保留，仍可 recall 到）。",
        parametersJson = """
            {"type":"object","properties":{
              "id":{"type":"string","description":"要移出的条目 id"}
            },"required":["id"],"additionalProperties":false}
        """.trimIndent(),
    )

    /** Registration order, which is also the order the model receives on every turn. */
    val all = listOf(remember, recall, read, update, delete, pin, unpin)
}
