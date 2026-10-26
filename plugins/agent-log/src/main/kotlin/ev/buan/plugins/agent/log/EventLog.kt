package ev.buan.plugins.agent.log

import org.json.JSONObject
import org.cordis.api.PluginStorage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 事件日志的物理存储。
 *
 * 只暴露"读全文 / 写全文"两种操作，不暴露 append：数据量小时（v0 上限几千条）重写全文
 * 最简单也最安全；等日志真的变大，按规格 §3 加快照与分段，而不是在这里加半吊子的追加。
 *
 * 抽象这层是因为宿主的 [PluginStorage] 只有 `read/write` 整块字节，拿不到 [File]；
 * 而单测想用真文件把"断电半行""中间坏行"这些边界真的造出来。
 */
interface LogStore {
    /** 全文；文件不存在返回 null。 */
    fun readText(): String?

    /** 覆盖写入。 */
    fun writeText(text: String)
}

/** 单测与（未来）直接持有目录的宿主用。 */
class FileLogStore(private val file: File) : LogStore {
    override fun readText(): String? = if (file.exists()) file.readText() else null

    override fun writeText(text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

/** 真实插件用：路径落在插件自己的存储区里。 */
class PluginStorageLogStore(
    private val storage: PluginStorage,
    private val path: String,
) : LogStore {
    override fun readText(): String? = storage.read(path)?.toString(Charsets.UTF_8)

    override fun writeText(text: String) {
        // atomicWrite：读者永远看不到写了一半的正文。这是"半行事件"的第一道防线。
        storage.atomicWrite(path, text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * append-only 事件日志。规格 `/root/specs/01-event-stream.md`。
 *
 * 三条不变量由这个类自己守住：
 *
 * 1. **单写者**：所有写入串行。存储句柄只由本类持有，外部不能追加。
 * 2. **seq 严格 +1**：写入时由本类分配，调用方给不了 seq——给得了就一定有人给错。
 * 3. **不可改**：只有 [append]，没有 update/delete。纠正只能追加 `sys.void`。
 *
 * 读取（[readAll]）区分两种损坏，后果完全不同：
 *
 * - **尾部半行**：进程被杀时正在写。丢弃即可，这是正常断电，不是数据损坏。
 * - **中间坏行**：文件被外部改过或磁盘出错。从这行起全部隔离进 `quarantine.jsonl`，
 *   **不静默丢弃**——静默丢弃等于承认"剩下的部分还能当真相源"，而它已经不能了。
 */
class EventLog(
    private val store: LogStore,
    private val quarantineStore: LogStore? = null,
) {
    /** 上次 [readAll] 的恢复情况，供启动流程决定要不要补 `sys.gap`。 */
    var lastRecovery: Recovery = Recovery()
        private set

    /**
     * 读全流。
     *
     * 返回的事件保证是文件里**连续可解析**的那一段；缺口记录在 [Recovery.gaps]，
     * 由调用方补 `sys.gap` 事件——补事件本身也要进流，不能在这里偷偷补。
     */
    fun readAll(): List<EvEvent> {
        val text = store.readText()
        if (text.isNullOrEmpty()) {
            lastRecovery = Recovery()
            return emptyList()
        }
        // 保留末尾空串，才能判断最后一行是否以换行结束（有换行 = 那行写完了）。
        val lines = text.split("\n")
        val events = mutableListOf<EvEvent>()
        val quarantine = mutableListOf<String>()
        var truncatedLine: Int? = null
        var quarantined = 0

        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val event = EvEvent.fromJson(line)
            if (event == null) {
                truncatedLine = index
                val isLastContent = index == lines.lastIndex || lines.drop(index + 1).all { it.isBlank() }
                if (!isLastContent) {
                    // 中间坏行：它之后的内容都不能再当真相源，整段隔离。
                    quarantine += lines.drop(index).filter { it.isNotBlank() }
                    quarantined = quarantine.size
                }
                break
            }
            events += event
        }

        if (quarantine.isNotEmpty() && quarantineStore != null) {
            val existing = quarantineStore.readText().orEmpty()
            quarantineStore.writeText(existing + quarantine.joinToString("\n", postfix = "\n"))
        }
        if (truncatedLine != null && quarantine.isNotEmpty()) {
            // 物理截断到最后一个完整事件，否则下次读还会撞上同一行。
            store.writeText(if (events.isEmpty()) "" else events.joinToString("\n", postfix = "\n") { it.toJson().toString() })
        }

        lastRecovery = Recovery(
            truncatedLine = truncatedLine,
            quarantinedLines = quarantined,
            gaps = gapsOf(events),
        )
        return events
    }

    /**
     * 追加一条事件并返回写入后的完整事件。
     *
     * 每次重新读全文以拿到当前 seq：本类不缓存"下一条 seq"，因为存储才是真相源。
     * 缓存与存储不一致时，错误会静默地偏向缓存。
     */
    fun append(
        type: String,
        actor: String,
        payload: JSONObject,
        refs: List<String> = emptyList(),
        ts: Long = System.currentTimeMillis(),
    ): EvEvent {
        val existing = readAll()
        val nextSeq = (existing.lastOrNull()?.seq ?: 0) + 1
        val event = EvEvent(
            seq = nextSeq,
            id = newId(nextSeq),
            ts = ts,
            type = type,
            actor = actor,
            payload = payload,
            refs = refs,
        )
        val body = existing.joinToString("\n", postfix = "\n") { it.toJson().toString() }
        store.writeText(body + event.toJson().toString() + "\n")
        return event
    }

    /** 缺口：应有而未有的 seq。 */
    private fun gapsOf(events: List<EvEvent>): List<IntRange> {
        val gaps = mutableListOf<IntRange>()
        var expected = 1
        for (e in events) {
            if (e.seq > expected) gaps += expected..(e.seq - 1)
            expected = e.seq + 1
        }
        return gaps
    }

    /**
     * id 规则：`ev_<seq>_<4 hex>`。
     *
     * 随机段只用于区分"同一 seq 被复用过"这种异常；正常路径下 seq 本身就唯一。
     * 不用 UUID：日志里 id 要能一眼看出对应哪条序号。
     */
    private fun newId(seq: Int): String {
        val rand = ((counter.getAndIncrement().toLong() xor (System.nanoTime() shr 4)) and 0xffff).toInt()
        return "ev_" + seq + "_" + rand.toString(16).padStart(4, '0')
    }

    /**
     * 一次读取的恢复结果。全部字段为空表示"干净读完"。
     */
    data class Recovery(
        /** 被判定为不完整的行号（0 基）；null 表示没有截断。 */
        val truncatedLine: Int? = null,
        /** 被隔离到 quarantine.jsonl 的行数。 */
        val quarantinedLines: Int = 0,
        /** 序号缺口。非空时调用方应补 `sys.gap`。 */
        val gaps: List<IntRange> = emptyList(),
    ) {
        val isClean: Boolean get() = truncatedLine == null && quarantinedLines == 0 && gaps.isEmpty()
    }

    private companion object {
        private val counter = AtomicInteger(0)
    }
}
