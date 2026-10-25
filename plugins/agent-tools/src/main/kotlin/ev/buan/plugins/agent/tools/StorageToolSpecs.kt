package ev.buan.plugins.agent.tools

import ev.buan.plugins.agent.api.ToolSpec

/**
 * The tool declarations this plugin publishes - the only part of a tool a model ever sees.
 *
 * Kept apart from the behaviour in [StorageTools] so names, descriptions and JSON schemas are plain
 * reviewable data instead of strings buried in call sites.
 */
internal object StorageToolSpecs {
    val read = ToolSpec(
        name = "file_read",
        description = "读取本插件存储区里的文本文件。路径是存储区内相对路径，不能越界到其它插件。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"存储区内的相对路径，例如 notes/todo.md"}},"required":["path"],"additionalProperties":false}
        """.trimIndent(),
    )

    val write = ToolSpec(
        name = "file_write",
        description = "把文本整文件写入本插件存储区（覆盖同名文件，原子写）。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"存储区内的相对路径"},"content":{"type":"string","description":"要写入的完整文本"}},"required":["path","content"],"additionalProperties":false}
        """.trimIndent(),
    )

    val list = ToolSpec(
        name = "file_list",
        description = "列出本插件存储区里某个目录下的条目名；空路径表示存储区根目录。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"目录的相对路径，缺省或空串表示根目录"}},"additionalProperties":false}
        """.trimIndent(),
    )

    val delete = ToolSpec(
        name = "file_delete",
        description = "删除本插件存储区里的文件或目录（目录递归删除）。",
        parametersJson = """
            {"type":"object","properties":{"path":{"type":"string","description":"存储区内的相对路径"}},"required":["path"],"additionalProperties":false}
        """.trimIndent(),
        // Recursive and irreversible: exactly the kind of call a human must see first.
        dangerous = true,
    )

    /** Registration order, which is also the order the model receives on every turn. */
    val all = listOf(read, write, list, delete)
}
