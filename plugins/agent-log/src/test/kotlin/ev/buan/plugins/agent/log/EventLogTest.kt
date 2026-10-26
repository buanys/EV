package ev.buan.plugins.agent.log

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 事件流地基的验收。对应 `/root/specs/01-event-stream.md` §5 与 `02-schemas.md` §7
 * 里每一条"机器可查"的断言。
 *
 * 这些测试刻意都从**字节层面**造假数据（直接往文件里写半行、写坏行），
 * 而不是调用被测类自己的写入路径——用被测代码造异常，测的是它自己的假设。
 */
class EventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun log(): Pair<EventLog, File> {
        val file = File(tmp.root, "events.jsonl")
        return EventLog(FileLogStore(file), FileLogStore(File(tmp.root, "quarantine.jsonl"))) to file
    }

    // ---- 不变量 1：seq 严格 +1，由写入方分配 ----

    @Test
    fun `append 从 1 开始连续分配 seq`() {
        val (log, _) = log()
        repeat(3) { log.append("sys.test", EvActors.CODE, JSONObject()) }
        assertEquals(listOf(1, 2, 3), log.readAll().map { it.seq })
    }

    @Test
    fun `id 稳定且不重复`() {
        val (log, _) = log()
        repeat(50) { log.append("sys.test", EvActors.CODE, JSONObject()) }
        val ids = log.readAll().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- 失败与边界：尾部半行（正常断电）----

    @Test
    fun `尾部半行被丢弃且不隔离`() {
        val (log, file) = log()
        log.append("sys.test", EvActors.CODE, JSONObject().put("n", 1))
        // 模拟进程在写第二行时被杀：没有换行，JSON 不完整。
        file.appendText("""{"seq":2,"id":"ev_2_aa""")

        val events = log.readAll()

        assertEquals(1, events.size)
        assertEquals(1, events[0].seq)
        assertNotNull("尾部不完整必须被识别", log.lastRecovery.truncatedLine)
        assertEquals("正常断电不是数据损坏，不该隔离", 0, log.lastRecovery.quarantinedLines)
    }

    @Test
    fun `尾部半行之后可以继续追加，seq 不跳号`() {
        val (log, file) = log()
        log.append("sys.test", EvActors.CODE, JSONObject())
        file.appendText("""{"seq":2,"id":"ev_2_aa""")

        val next = log.append("sys.test", EvActors.CODE, JSONObject())

        assertEquals("丢弃半行后，下一条仍应是 2", 2, next.seq)
    }

    // ---- 失败与边界：中间坏行（真损坏）----

    @Test
    fun `中间坏行之后的内容全部隔离，且不静默丢弃`() {
        val (log, file) = log()
        repeat(3) { log.append("sys.test", EvActors.CODE, JSONObject()) }
        val lines = file.readLines().toMutableList()
        // 第二行被外部改坏
        lines[1] = """{"seq":2,"id":"""
        file.writeText(lines.joinToString("\n") + "\n")

        val events = log.readAll()

        assertEquals("只保留坏行之前的完整事件", 1, events.size)
        assertEquals(2, log.lastRecovery.quarantinedLines)
        val quarantined = File(tmp.root, "quarantine.jsonl").readText()
        assertTrue("坏行本身必须进隔离区", quarantined.contains("""{"seq":2,"id":"""))
        assertTrue("坏行之后的完整行也要隔离（它们已无法定位）", quarantined.contains("ev_3"))
    }

    @Test
    fun `中间坏行修复后重新读取不会再次撞上同一行`() {
        val (log, file) = log()
        repeat(3) { log.append("sys.test", EvActors.CODE, JSONObject()) }
        val lines = file.readLines().toMutableList()
        lines[1] = "not json at all"
        file.writeText(lines.joinToString("\n") + "\n")

        log.readAll() // 第一次：隔离尾部并物理截断
        val second = log.readAll()

        assertEquals("截断后只剩坏行之前的事件", 1, second.size)
        assertEquals("第二次读取不该再发现坏行", 0, log.lastRecovery.quarantinedLines)
    }

    // ---- 不变量 3：不可改，纠正只能追加 sys.void ----

    @Test
    fun `void 事件让目标事件不再生效但仍在流里`() {
        val (log, _) = log()
        val target = log.append("sensor.battery", EvActors.HOST, JSONObject().put("pct", 83))
        log.append(EvTypes.SYS_VOID, EvActors.USER, JSONObject().put("target", target.id))

        val events = log.readAll()
        val state = EventReplay.replay(events)

        assertEquals("被作废的事件不能从流里消失", 2, events.size)
        assertTrue(state.voidedIds.contains(target.id))
    }

    // ---- 不变量 4：重放确定性 ----

    @Test
    fun `重放两次结果相等`() {
        val (log, _) = log()
        repeat(5) { log.append("sensor.battery", EvActors.HOST, JSONObject().put("pct", it)) }
        log.append("proposal.fs.read", "agent:w1", JSONObject())
        val events = log.readAll()

        assertEquals(EventReplay.replay(events), EventReplay.replay(events))
    }

    @Test
    fun `前缀重放只看到当时的事件`() {
        val (log, _) = log()
        repeat(5) { log.append("sys.test", EvActors.CODE, JSONObject()) }

        val atThree = EventReplay.replayUpTo(log.readAll(), 3)

        assertEquals(3, atThree.lastSeq)
        assertEquals(3, atThree.eventCount)
    }
}
