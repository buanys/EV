package ev.buan.hostcore.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `hostApi` 范围的解析与判定。
 *
 * 重点不在"能解析对的"，而在**错的必须明确拒绝**：一个把 `^1.0.0` 当 `>=1.0.0` 理解的
 * 解析器，会让插件作者以为自己声明的上界生效了。
 */
class HostApiRangeTest {

    private fun range(text: String): HostApiRange =
        when (val parsed = HostApiRange.parse(text)) {
            is HostApiRange.Result.Ok -> parsed.range
            is HostApiRange.Result.Invalid -> throw AssertionError("应当解析成功：$text（${parsed.reason}）")
        }

    private fun invalid(text: String): HostApiRange.Result.Invalid =
        assertIs<HostApiRange.Result.Invalid>(HostApiRange.parse(text))

    private fun v(text: String): HostApiVersion =
        HostApiVersion.parse(text) ?: throw AssertionError("版本应当合法：$text")

    // ---- 版本点 ----------------------------------------------------------

    @Test
    fun `三段的版本按数值比较而不是按字符串`() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertEquals(0, v("1.0.0").compareTo(v("1.0.0")))
    }

    @Test
    fun `版本必须严格三段数字`() {
        assertEquals(null, HostApiVersion.parse("1.0"))
        assertEquals(null, HostApiVersion.parse("v1.0.0"))
        assertEquals(null, HostApiVersion.parse("1.0.0-rc1"))
        assertEquals(null, HostApiVersion.parse("1..0"))
        assertEquals(null, HostApiVersion.parse(""))
    }

    // ---- 范围：命中的 ----------------------------------------------------

    @Test
    fun `spec 里的例子原样可用`() {
        val r = range(">=1.0.0 <2.0.0")
        assertTrue(r.contains(v("1.0.0")))
        assertTrue(r.contains(v("1.7.3")))
        assertFalse(r.contains(v("2.0.0")))
        assertFalse(r.contains(v("0.9.9")))
    }

    @Test
    fun `不带符号等价于精确匹配`() {
        assertTrue(range("1.0.0").contains(v("1.0.0")))
        assertFalse(range("1.0.0").contains(v("1.0.1")))
        assertTrue(range("=1.0.0").contains(v("1.0.0")))
    }

    @Test
    fun `四种比较符各自的边界`() {
        assertFalse(range(">1.0.0").contains(v("1.0.0")))
        assertTrue(range(">1.0.0").contains(v("1.0.1")))
        assertTrue(range("<=1.0.0").contains(v("1.0.0")))
        assertFalse(range("<=1.0.0").contains(v("1.0.1")))
        assertFalse(range("<1.0.0").contains(v("1.0.0")))
        assertTrue(range(">=1.0.0").contains(v("1.0.0")))
    }

    @Test
    fun `多余空白不影响解析`() {
        val r = range("  >=1.0.0   <2.0.0  ")
        assertTrue(r.contains(v("1.2.0")))
    }

    // ---- 范围：必须拒绝的 ------------------------------------------------

    @Test
    fun `npm 风格的简写一律拒绝而不是猜`() {
        invalid("^1.0.0")
        invalid("~1.2.3")
        invalid("1.x")
        invalid("1.0.x")
    }

    @Test
    fun `通配与并集运算符不支持`() {
        invalid("*")
        invalid(">=1.0.0 || >=3.0.0")
    }

    @Test
    fun `空声明与残缺版本都报错并给出可照着改的理由`() {
        assertTrue(invalid("").reason.contains("空"))
        val twoSegment = invalid(">=1.0")
        assertTrue(twoSegment.reason.contains(">=1.0"), "理由里要回显原文")
        assertTrue(twoSegment.reason.contains("docs/sdk/versioning.md"), "理由里要指路")
    }

    @Test
    fun `原文在报错时被完整保留`() {
        assertEquals("^1.2.3", invalid("^1.2.3").text)
    }
}
