package ev.buan.plugins.agent.gui

import kotlinx.coroutines.runBlocking
import org.cordis.api.ScreenAction
import org.cordis.api.ScreenDump
import org.cordis.api.ScreenService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析和 diff 是这套闭环的地基：解析错了就会点到别的地方，diff 错了模型会以为没生效。
 * 所以这里测的是格式本身，不是\"流程能跑通\"。
 */
class GuiFlowTest {

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析普通可点行`() {
        val node = GuiLines.parse("12 确定 @540,1200 c")
        assertNotNull(node)
        assertEquals(12, node!!.index)
        assertEquals("确定", node.label)
        assertEquals(540, node.x)
        assertEquals(1200, node.y)
        assertEquals("c", node.flags)
        assertTrue(node.clickable)
    }

    @Test
    fun `解析没有标记的行`() {
        val node = GuiLines.parse("3 微信 @100,200")
        assertEquals("微信", node?.label)
        assertEquals("", node?.flags)
        assertFalse(node!!.clickable)
    }

    @Test
    fun `解析输入框的 hint`() {
        val node = GuiLines.parse("5 · @540,320 e hint=搜索")
        assertEquals("·", node?.label)
        assertTrue(node!!.editable)
        assertEquals("搜索", node.hint)
    }

    @Test
    fun `文字里带 at 和空格也不会被当成坐标`() {
        val node = GuiLines.parse("7 发送 @张三 2 条消息 @500,1500 c")
        assertEquals("发送 @张三 2 条消息", node?.label)
        assertEquals(500, node?.x)
        assertEquals(1500, node?.y)
    }

    @Test
    fun `畸形行返回 null 而不是猜一个坐标`() {
        assertNull(GuiLines.parse(""))
        assertNull(GuiLines.parse("没有坐标的一行"))
        assertNull(GuiLines.parse("12 确定 @abc,1200 c"))
        assertNull(GuiLines.parse("x 确定 @540,1200"))
    }

    // ---------------------------------------------------------------- diff

    private fun dump(vararg lines: String, pkg: String = "com.demo") =
        ScreenDump(lines.toList(), pkg)

    @Test
    fun `界面没变会被标出来，模型就不会原地重试`() {
        val before = dump("0 设置 @100,100 c", "1 返回 @50,50 c")
        val after = dump("0 设置 @100,100 c", "1 返回 @50,50 c")

        val diff = GuiDiff.describe(before, after)
        assertTrue(diff.getBoolean("unchanged"))
        assertFalse(diff.getBoolean("layoutMoved"))
        assertEquals(0, diff.getJSONArray("added").length())
    }

    @Test
    fun `只有坐标变说明是滚动，不是换了页面`() {
        val before = dump("0 第一条 @100,300 c", "1 第二条 @100,500 c")
        val after = dump("0 第一条 @100,100 c", "1 第二条 @100,300 c")

        val diff = GuiDiff.describe(before, after)
        assertTrue(diff.getBoolean("layoutMoved"))
        assertFalse(diff.getBoolean("unchanged"))
    }

    @Test
    fun `文字增减和包名切换分开报告`() {
        val before = dump("0 首页 @100,100 c", pkg = "com.home")
        val after = dump("0 详情 @100,100 c", pkg = "com.detail")

        val diff = GuiDiff.describe(before, after)
        assertTrue(diff.getBoolean("packageChanged"))
        assertEquals("首页|c", diff.getJSONArray("removed").getString(0))
        assertEquals("详情|c", diff.getJSONArray("added").getString(0))
        assertFalse(diff.getBoolean("layoutMoved"))
    }

    // ---------------------------------------------------------------- 等待

    private class FakeScreen(private var current: ScreenDump) : ScreenService {
        var taps = 0
            private set
        var dumps = 0
            private set

        override val available: Boolean get() = true
        override fun openSettings(): Boolean = true
        override fun dump(maxNodes: Int): ScreenDump {
            dumps++
            return current
        }

        override fun tap(x: Int, y: Int): ScreenAction {
            taps++
            return ScreenAction.Ok
        }

        override fun tapText(text: String): ScreenAction = ScreenAction.Ok
        override fun input(text: String): ScreenAction = ScreenAction.Ok
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ScreenAction =
            ScreenAction.Ok

        override fun key(key: String): ScreenAction = ScreenAction.Ok
        override fun currentPackage(): String? = current.packageName
    }

    @Test
    fun `等文字出现，命中就返回当前屏幕`() = runBlocking {
        val screen = FakeScreen(dump("0 正在加载 @100,100"))
        val result = JSONObject(
            GuiWaitTool { screen }.invoke("""{"text":"加载完成","timeout_ms":300}"""),
        )

        // 一直没出现，于是走到超时分支——但仍然要把现在的屏幕给模型。
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getBoolean("timeout"))
        assertEquals(1, result.getJSONArray("lines").length())
        assertTrue(screen.dumps >= 1)
    }

    @Test
    fun `等文字消失，一开始就没有就直接成功`() = runBlocking {
        val screen = FakeScreen(dump("0 首页 @100,100 c"))
        val result = JSONObject(
            GuiWaitTool { screen }.invoke("""{"text":"弹窗","mode":"disappear","timeout_ms":1000}"""),
        )

        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("matched"))
        assertEquals(1, result.getInt("polls"))
    }

    @Test
    fun `没有屏幕服务时明确报错，不假装成功`() = runBlocking {
        val result = JSONObject(GuiWaitTool { null }.invoke("""{"text":"任意"}"""))
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("宿主"))
    }

    // ---------------------------------------------------------------- 翻页读全文

    /** 每滑一次换一页，最后一页停住不动——模拟滚到底。 */
    private class PagedScreen(private val pages: List<ScreenDump>) : ScreenService {
        private var index = 0
        var swipes = 0
            private set

        override val available: Boolean get() = true
        override fun openSettings(): Boolean = true
        override fun dump(maxNodes: Int): ScreenDump = pages[index.coerceAtMost(pages.lastIndex)]
        override fun tap(x: Int, y: Int): ScreenAction = ScreenAction.Ok
        override fun tapText(text: String): ScreenAction = ScreenAction.Ok
        override fun input(text: String): ScreenAction = ScreenAction.Ok
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ScreenAction {
            swipes++
            if (index < pages.lastIndex) index++
            return ScreenAction.Ok
        }

        override fun key(key: String): ScreenAction = ScreenAction.Ok
        override fun currentPackage(): String? = pages.first().packageName
    }

    @Test
    fun `翻页读到没有新内容就停，正文按顺序合并`() = runBlocking {
        val screen = PagedScreen(
            listOf(
                dump("0 第一段 @0,0"),
                dump("0 第二段 @0,0"),
                dump("0 第二段 @0,0"),
            ),
        )
        val result = JSONObject(
            GuiReadAllTool { screen }.invoke("""{"max_pages":10,"settle_ms":100}"""),
        )

        assertTrue(result.getBoolean("ok"))
        assertEquals("bottom", result.getString("stop"))
        assertEquals("第一段\n第二段", result.getString("text"))
        assertEquals(3, result.getInt("pages"))
    }

    @Test
    fun `超过字数上限就截断，并说明截断了`() = runBlocking {
        // 30 行 × 约 19 字符 ≈ 570，超过 500 的上限。
        val rows = (0 until 30).map { i -> "$i 内容$i${"字".repeat(15)} @0,$i" }
        val screen = PagedScreen(listOf(dump(*rows.toTypedArray())))
        val result = JSONObject(
            GuiReadAllTool { screen }.invoke("""{"max_chars":500,"settle_ms":100}"""),
        )

        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("truncated"))
        assertEquals("max_chars", result.getString("stop"))
        assertTrue(result.getInt("chars") <= 500)
    }
}
