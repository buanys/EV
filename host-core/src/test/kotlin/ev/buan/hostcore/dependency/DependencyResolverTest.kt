package ev.buan.hostcore.dependency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The resolver decides the one order the host accepts containers in, so these tests pin three
 * properties: dependencies come first, the result does not depend on the input order (file names or
 * directory listing), and every cycle is reported as a closed ring the host can name.
 */
class DependencyResolverTest {

    // ---- ordering ----------------------------------------------------------

    @Test
    fun `链式依赖按依赖在前排序`() {
        val resolution = DependencyResolver.resolve(
            mapOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()),
        )
        assertEquals(listOf("a", "b", "c"), resolution.order)
        assertFalse(resolution.hasCycles)
    }

    @Test
    fun `任意输入顺序得到相同顺序`() {
        val graphs = listOf(
            linkedMapOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()),
            linkedMapOf("a" to emptyList(), "b" to listOf("a"), "c" to listOf("b")),
            linkedMapOf("b" to listOf("a"), "c" to listOf("b"), "a" to emptyList()),
        )
        val orders = graphs.map { DependencyResolver.resolve(it).order }
        assertEquals(1, orders.distinct().size, "同一依赖图应给出同一顺序：$orders")
        assertEquals(listOf("a", "b", "c"), orders.first())
    }

    @Test
    fun `菱形依赖把公共依赖排在最前`() {
        val resolution = DependencyResolver.resolve(
            mapOf("d" to listOf("b", "c"), "b" to listOf("a"), "c" to listOf("a"), "a" to emptyList()),
        )
        val order = resolution.order
        assertEquals(setOf("a", "b", "c", "d"), order.toSet())
        assertTrue(order.indexOf("a") < order.indexOf("b"), "$order")
        assertTrue(order.indexOf("a") < order.indexOf("c"), "$order")
        assertTrue(order.indexOf("b") < order.indexOf("d"), "$order")
        assertTrue(order.indexOf("c") < order.indexOf("d"), "$order")
    }

    @Test
    fun `无依赖的插件按 id 稳定排序`() {
        val resolution = DependencyResolver.resolve(
            mapOf("zeta" to emptyList(), "alpha" to emptyList(), "mike" to emptyList()),
        )
        assertEquals(listOf("alpha", "mike", "zeta"), resolution.order)
    }

    @Test
    fun `重复声明只算一次依赖`() {
        val resolution = DependencyResolver.resolve(mapOf("a" to listOf("b", "b"), "b" to emptyList()))
        assertEquals(listOf("b", "a"), resolution.order)
    }

    @Test
    fun `空图解析为空结果`() {
        val resolution = DependencyResolver.resolve(emptyMap())
        assertEquals(emptyList(), resolution.order)
        assertEquals(emptyList(), resolution.cycles)
        assertFalse(resolution.hasCycles)
    }

    @Test
    fun `长链不依赖递归深度`() {
        val ids = (0 until 1000).map { "p%04d".format(it) }
        val graph = ids.mapIndexed { index, id ->
            id to if (index == 0) emptyList() else listOf(ids[index - 1])
        }.toMap()
        val resolution = DependencyResolver.resolve(graph)
        assertEquals(ids, resolution.order)
    }

    // ---- cycles ------------------------------------------------------------

    @Test
    fun `两个插件的互相依赖被报告为闭环`() {
        val resolution = DependencyResolver.resolve(mapOf("a" to listOf("b"), "b" to listOf("a")))
        assertEquals(emptyList(), resolution.order)
        assertEquals(listOf(listOf("a", "b", "a")), resolution.cycles)
        assertEquals(listOf("a → b → a"), resolution.cycleRings)
        assertEquals(setOf("a", "b"), resolution.cyclicIds)
        assertTrue(resolution.hasCycles)
    }

    @Test
    fun `自依赖是长度为 1 的闭环`() {
        val resolution = DependencyResolver.resolve(mapOf("a" to listOf("a")))
        assertEquals(listOf(listOf("a", "a")), resolution.cycles)
        assertEquals(emptyList(), resolution.order)
    }

    @Test
    fun `三个插件的环按路径顺序报告`() {
        val resolution = DependencyResolver.resolve(
            mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to listOf("a")),
        )
        assertEquals(listOf(listOf("a", "b", "c", "a")), resolution.cycles)
        assertEquals(emptyList(), resolution.order)
    }

    @Test
    fun `多个互不相干的环各自报告一次`() {
        val resolution = DependencyResolver.resolve(
            mapOf("a" to listOf("b"), "b" to listOf("a"), "c" to listOf("d"), "d" to listOf("c")),
        )
        assertEquals(listOf(listOf("a", "b", "a"), listOf("c", "d", "c")), resolution.cycles)
        assertEquals(emptyList(), resolution.order)
    }

    @Test
    fun `环的检测结果与输入顺序无关`() {
        val first = DependencyResolver.resolve(linkedMapOf("b" to listOf("a"), "a" to listOf("b")))
        val second = DependencyResolver.resolve(linkedMapOf("a" to listOf("b"), "b" to listOf("a")))
        assertEquals(first.cycles, second.cycles)
    }

    @Test
    fun `依赖环之外的插件仍会被排序`() {
        // `x` links against a plugin that is stuck in a cycle: it still has to appear in the plan so
        // the host can reject it with a code and a message instead of dropping it silently.
        val resolution = DependencyResolver.resolve(
            mapOf("x" to listOf("a"), "a" to listOf("b"), "b" to listOf("a")),
        )
        assertEquals(listOf("x"), resolution.order)
        assertEquals(listOf(listOf("a", "b", "a")), resolution.cycles)
        assertEquals(setOf("a", "b"), resolution.cyclicIds)
    }

    // ---- declarations ------------------------------------------------------

    @Test
    fun `声明了宿主没有的插件不影响排序`() {
        val resolution = DependencyResolver.resolve(mapOf("a" to listOf("not.installed")))
        assertEquals(listOf("a"), resolution.order)
        assertFalse(resolution.hasCycles)
    }
}
