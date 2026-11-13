package ev.buan.hostcore.dependency

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 依赖版本：解析、比较、以及"这一轮装的东西够不够"。
 *
 * 这是规则里"依赖指向接口并声明版本，宿主在装配时检查"的落地。
 * 在此之前 `Dependencies` 是 `Map<String, Any?>`，零版本信息——"替换需版本满足"无法判定。
 */
class PluginVersionTest {

    // ── 版本解析 ────────────────────────────────────────────────────────────

    @Test
    fun `parses one two and three segment versions`() {
        assertEquals(PluginVersion(1, 0, 0), PluginVersion.parse("1"))
        assertEquals(PluginVersion(1, 2, 0), PluginVersion.parse("1.2"))
        assertEquals(PluginVersion(1, 2, 3), PluginVersion.parse("1.2.3"))
        assertEquals(PluginVersion(0, 0, 1), PluginVersion.parse(" 0.0.1 "))
    }

    @Test
    fun `rejects anything that is not a version`() {
        // 拼错的版本号必须被拒绝，而不是悄悄当成 0.0.0 —— 那会变成"永远满足"。
        listOf("", "latest", "1.x", "1.2.3.4", "-1", "1.-2", "1..2").forEach { text ->
            assertNull(PluginVersion.parse(text), "应拒绝：$text")
        }
    }

    @Test
    fun `compares numerically not lexically`() {
        // 按字符串比较 "1.10" < "1.9" 是错的。
        assertTrue(PluginVersion.parse("1.10.0")!! > PluginVersion.parse("1.9.0")!!)
        assertTrue(PluginVersion.parse("2.0.0")!! > PluginVersion.parse("1.99.99")!!)
        assertEquals(PluginVersion.parse("1.2")!!, PluginVersion.parse("1.2.0")!!)
    }

    // ── 依赖声明 ────────────────────────────────────────────────────────────

    @Test
    fun `parses a bare id and an id with a minimum version`() {
        assertEquals(PluginDependency("a.b"), PluginDependency.parse("a.b"))
        assertEquals(
            PluginDependency("a.b", PluginVersion(1, 2, 0)),
            PluginDependency.parse("a.b@1.2"),
        )
    }

    @Test
    fun `a malformed version makes the whole declaration invalid`() {
        // 与"没有版本要求"区分开：写坏了就是写坏了，不能当成不约束。
        assertNull(PluginDependency.parse("a.b@latest"))
        assertNull(PluginDependency.parse("a.b@"))
        assertNull(PluginDependency.parse("@1.0"))
        assertNull(PluginDependency.parse("  "))
    }

    @Test
    fun `round trips through toString`() {
        listOf("a.b", "a.b@1.2.0").forEach { text ->
            assertEquals(text, PluginDependency.parse(text)!!.toString())
        }
    }

    // ── 装配检查 ────────────────────────────────────────────────────────────

    @Test
    fun `an unversioned dependency only needs the plugin to exist`() {
        val failures = DependencyCheck.check(
            listOf(PluginDependency("tools")),
            accepted = mapOf("tools" to "1.0.0"),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a missing plugin is reported as absent not as a version mismatch`() {
        // 两种失败要分开：一个是"没装"，一个是"装了但太旧"，处理方式完全不同。
        val failures = DependencyCheck.check(
            listOf(PluginDependency("tools", PluginVersion(1, 0, 0))),
            accepted = emptyMap(),
        )
        assertEquals(1, failures.size)
        assertEquals(false, failures.single().versionMismatch)
        assertTrue(failures.single().message("x").contains("没有被接受"))
    }

    @Test
    fun `an older version is reported as a mismatch and names both numbers`() {
        val failures = DependencyCheck.check(
            listOf(PluginDependency("tools", PluginVersion(1, 2, 0))),
            accepted = mapOf("tools" to "1.1.9"),
        )
        assertEquals(1, failures.size)
        assertTrue(failures.single().versionMismatch)
        val message = failures.single().message("browse")
        assertTrue(message.contains("1.2.0") && message.contains("1.1.9"), message)
    }

    @Test
    fun `an equal or newer version passes`() {
        val required = PluginDependency("tools", PluginVersion(1, 2, 0))
        assertTrue(DependencyCheck.check(listOf(required), mapOf("tools" to "1.2.0")).isEmpty())
        assertTrue(DependencyCheck.check(listOf(required), mapOf("tools" to "1.3")).isEmpty())
        assertTrue(DependencyCheck.check(listOf(required), mapOf("tools" to "2.0.0")).isEmpty())
    }

    @Test
    fun `an unparsable installed version fails closed`() {
        val failures = DependencyCheck.check(
            listOf(PluginDependency("tools", PluginVersion(1, 0, 0))),
            accepted = mapOf("tools" to "1.0.0-beta"),
        )
        assertEquals(1, failures.size)
        assertTrue(failures.single().versionMismatch)
    }
}
