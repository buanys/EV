package ev.buan.plugins.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 作用域边界是这个类唯一真正危险的地方，所以测试几乎全在打它。
 *
 * 用真文件系统（[TemporaryFolder]）而不是 mock：`canonicalFile` 对 `..` 和符号链接的折叠
 * 行为是 JDK 和文件系统共同决定的，mock 掉就测不到真东西了。
 */
class DeviceFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun files(): DeviceFiles = DeviceFiles(tmp.root)

    @Test
    fun `列目录按目录优先、名字排序返回`() {
        File(tmp.root, "b.txt").writeText("x")
        File(tmp.root, "a.txt").writeText("y")
        File(tmp.root, "sub").mkdirs()

        val result = files().list("") as DeviceFiles.Result.Ok
        val names = result.value.map { it.name }

        assertEquals(listOf("sub", "a.txt", "b.txt"), names)
        assertTrue("目录大小报 0，不报一个假的数字", result.value.first().size == 0L)
    }

    @Test
    fun `相对路径按根解析`() {
        File(tmp.root, "notes.md").writeText("hello")

        val result = files().readText("notes.md", 1024)

        assertTrue(result is DeviceFiles.Result.Ok)
        assertEquals("hello", (result as DeviceFiles.Result.Ok).value)
    }

    @Test
    fun `根内的绝对路径可以读`() {
        val f = File(tmp.root, "abs.txt")
        f.writeText("ok")

        val result = files().readText(f.absolutePath, 1024)

        assertTrue("agent 通常写绝对路径，拒绝它只会让它反复试错", result is DeviceFiles.Result.Ok)
    }

    // ---- 越界：这是全部安全性的落点 ----

    @Test
    fun `点点穿透被拒绝`() {
        val outside = File(tmp.root.parentFile, "outside.txt")
        outside.writeText("secret")

        val result = files().readText("../outside.txt", 1024)

        assertTrue("`..` 必须在 canonical 阶段被折叠并拦下", result is DeviceFiles.Result.Fail)
        assertTrue((result as DeviceFiles.Result.Fail).error.contains("越界"))
    }

    @Test
    fun `绝对路径指向根外被拒绝`() {
        val outside = File(tmp.root.parentFile, "outside2.txt")
        outside.writeText("secret")

        val result = files().readText(outside.absolutePath, 1024)

        assertTrue(result is DeviceFiles.Result.Fail)
    }

    @Test
    fun `符号链接逃逸被拒绝`() {
        val outsideDir = File(tmp.root.parentFile, "outside-dir")
        outsideDir.mkdirs()
        File(outsideDir, "secret.txt").writeText("secret")
        // canonical 会把链接解析成真实路径，因此链接必须也被拦住——
        // 只做字符串前缀检查的实现会在这里漏。
        assertTrue(File(tmp.root, "link").let { runCatching { java.nio.file.Files.createSymbolicLink(it.toPath(), outsideDir.toPath()); true }.getOrDefault(false) })

        val result = files().readText("link/secret.txt", 1024)

        assertTrue("符号链接逃逸必须拒绝", result is DeviceFiles.Result.Fail)
    }

    @Test
    fun `越界错误要说清是什么范围`() {
        val result = files().readText("../../etc/passwd", 1024) as DeviceFiles.Result.Fail
        assertTrue("要告诉 agent 哪个目录可访问", result.error.contains(tmp.root.absolutePath))
    }

    // ---- 其它失败路径 ----

    @Test
    fun `不存在的文件返回可读错误`() {
        val result = files().readText("nope.txt", 1024) as DeviceFiles.Result.Fail
        assertTrue(result.error.contains("不存在"))
    }

    @Test
    fun `超过上限的文件拒绝读取并给出替代方案`() {
        File(tmp.root, "big.log").writeText("x".repeat(2048))

        val result = files().readText("big.log", 1024) as DeviceFiles.Result.Fail

        assertTrue(result.error.contains("超过单次读取上限"))
        assertTrue("要提示用 run_shell 分段读，否则 agent 会卡在这里", result.error.contains("run_shell"))
    }

    @Test
    fun `读目录返回明确错误而不是空白`() {
        File(tmp.root, "sub").mkdirs()

        val result = files().readText("sub", 1024) as DeviceFiles.Result.Fail

        assertTrue(result.error.contains("目录"))
    }
}
