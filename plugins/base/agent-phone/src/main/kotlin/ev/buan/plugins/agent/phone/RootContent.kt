package ev.buan.plugins.agent.phone

/**
 * 经 root 访问厂商 ContentProvider（时钟、便签）。
 *
 * 为什么必须走 root：这两个 provider 在 manifest 上挂着 `oppo.permission.OPPO_COMPONENT_SAFE`
 * 这类签名级权限，第三方应用永远拿不到（实测：普通查询直接 SecurityException，root 下同样
 * 的 URI 一行不少）。所以 ma 这一层不是"图省事"，是唯一可用的通路。
 *
 * 为什么用 `content` 命令行而不是直接 binder：宿主进程不是 root，进程内拿不到系统服务，
 * 只能把一次调用交给 `su -c` 里跑的 `content` 二进制。代价是每次调用一次进程创建（约 200ms），
 * 换来的是不需要在设备上常驻一个 root 助手进程——那是下一层的事。
 *
 * **解析是这一层唯一的弱点**：`content query` 的输出是 `Row: N k=v, k2=v2`，值里带 `, x=y`
 * 就会切错。所以约定：**只对短字段（时间、开关、标题）用通用解析**，长文本（便签正文）
 * 单列查询。这条约束写在每个调用点上，不靠注释提醒。
 */
internal object RootContent {

    /** 一行记录：列名 → 原样字符串（`NULL` 一律变 null）。 */
    typealias Row = Map<String, String?>

    /** 查询；失败返回 null，由调用方翻译成给模型看的一句话。 */
    fun query(uri: String, projection: List<String>? = null, selection: String? = null): List<Row>? {
        val command = buildString {
            append("content query --uri ").append(arg(uri))
            projection?.let { append(" --projection ").append(arg(it.joinToString(","))) }
            selection?.let { append(" --where ").append(arg(it)) }
        }
        val output = exec(command) ?: return null
        return output.lineSequence()
            .filter { it.startsWith("Row:") }
            .mapNotNull { parseRow(it) }
            .toList()
    }

    /** 插入；返回被插入的 URI 或 null。 */
    fun insert(uri: String, values: Map<String, Any>): String? =
        exec(
            buildString {
                append("content insert --uri ").append(arg(uri))
                values.forEach { (column, value) -> append(" --bind ").append(arg(bind(column, value))) }
            },
        )

    fun update(uri: String, values: Map<String, Any>, selection: String): Boolean =
        exec(
            buildString {
                append("content update --uri ").append(arg(uri))
                values.forEach { (column, value) -> append(" --bind ").append(arg(bind(column, value))) }
                append(" --where ").append(arg(selection))
            },
        ) != null

    fun delete(uri: String, selection: String): Boolean =
        exec("content delete --uri ${arg(uri)} --where ${arg(selection)}") != null

    // ---------------------------------------------------------------- 内部

    /**
     * 跑一条 `su -c` 命令。
     *
     * 判据是**退出码 + 是否出现 "Exception while accessing provider"**：`content` 在失败时
     * 有时仍然返回 0，只把错误打在 stderr 上，只看退出码会把失败当成空结果。
     */
    private fun exec(command: String): String? {
        val process = runCatching {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0 || output.contains("Exception while accessing provider")) {
            android.util.Log.w("EV", "phone-root: exit=$exit cmd=$command out=${output.take(300)}")
        }
        if (exit != 0) return null
        if (output.contains("Exception while accessing provider")) return null
        if (output.contains("Permission Denial")) return null
        return output
    }

    /** `Row: 0 _id=1, hour=6, message=起床闹铃` → 列名到值。值为 `NULL` 或空串一律变 null。 */
    private fun parseRow(line: String): Row? {
        val body = line.removePrefix("Row: ").substringAfter(' ', "")
        if (body.isBlank()) return null
        val row = linkedMapOf<String, String?>()
        body.split(Regex(", (?=[A-Za-z_][A-Za-z_0-9]*=)")).forEach { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@forEach
            val value = part.substring(index + 1)
            row[part.substring(0, index).trim()] = value.takeIf { it.isNotEmpty() && it != "NULL" }
        }
        return row.takeIf { it.isNotEmpty() }
    }

    /** `--bind` 的 `<列>:<类型>:<值>`；整数走 `i`，其余走字符串（厂商表里没别的类型需要）。 */
    private fun bind(column: String, value: Any): String = when (value) {
        is Int -> "$column:i:$value"
        is Boolean -> "$column:i:${if (value) 1 else 0}"
        else -> "$column:s:$value"
    }

    /** 单引号转义：把 `'` 变成 `'\''`，其余原样。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 一个命令行参数。
     *
     * 非 ASCII **不能直接放进 argv**：`ProcessBuilder` 传给子进程的字节按平台编码来，
     * 中文经常在这一步变成 `?`——命令照样执行、只是匹配不到任何东西（真机踩过：
     * 同一个查询在终端里能查到，在插件里永远 0 条）。所以先把值 base64，让命令行保持纯 ASCII，
     * 再在 sh 里 `base64 -d` 解回来。
     */
    private fun arg(value: String): String =
        if (value.all { it.code < 128 }) {
            quote(value)
        } else {
            "\"$(echo " + java.util.Base64.getEncoder().encodeToString(value.toByteArray()) + " | base64 -d)\""
        }

    /** 这个 provider 现在通不通（不通就让工具如实说"root 或系统接口不可用"）。 */
    fun available(uri: String): Boolean = query(uri) != null
}
