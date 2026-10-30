package ev.buan.plugins.forge

import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 产物定位的契约测试。
 *
 * 编译器和安装工具是两个插件，唯一的接口是磁盘上的路径。路径规则写错时，
 * 模型看到的是"找不到产物"——一个看起来像没编译、实际是拼错路径的假象。
 * 这段逻辑纯 Kotlin，所以这类错误能在这里拦住，不必上真机。
 */
class ForgeArtifactsTest {

    private val root: File = Files.createTempDirectory("forge-artifacts").toFile()

    private fun produce(id: String, bytes: String = "jar-bytes") {
        val dir = ForgeArtifacts.directory(root, id).apply { mkdirs() }
        File(dir, "$id.jar").writeText(bytes)
        File(dir, "$id.json").writeText("""{"id":"$id","entryClass":"x.Y","apiVersion":1}""")
    }

    @Test
    fun `两个产物都在就可以装`() {
        produce("taotie.smoke")
        assertNull(ForgeArtifacts.problem(root, "taotie.smoke"))
    }

    @Test
    fun `没编译过时给出可照做的原因`() {
        val problem = ForgeArtifacts.problem(root, "taotie.never")
        assertNotNull(problem)
        assertTrue("要说清下一步：$problem", problem!!.contains("forge_build"))
    }

    @Test
    fun `有 jar 没 sidecar 也算不完整`() {
        val dir = ForgeArtifacts.directory(root, "taotie.half").apply { mkdirs() }
        File(dir, "taotie.half.jar").writeText("x")
        val problem = ForgeArtifacts.problem(root, "taotie.half")
        assertNotNull(problem)
        assertTrue("要点名缺的是 sidecar：$problem", problem!!.contains("sidecar"))
    }

    @Test
    fun `空产物被拦住`() {
        produce("taotie.empty", bytes = "")
        val problem = ForgeArtifacts.problem(root, "taotie.empty")
        assertNotNull(problem)
        assertTrue("要识别空产物：$problem", problem!!.contains("空文件"))
    }

    @Test
    fun `非法 id 不进文件系统`() {
        assertEquals("缺少 plugin_id", ForgeArtifacts.problem(root, ""))
        // 带斜杠的 id 会把产物指到目录外，必须在拼路径之前就拒掉。
        assertNotNull(ForgeArtifacts.problem(root, "../escape"))
        assertNotNull(ForgeArtifacts.problem(root, ".hidden"))
    }

    @Test
    fun `jar 与 sidecar 同目录且同名`() {
        val jar = ForgeArtifacts.jar(root, "a.b")
        val sidecar = ForgeArtifacts.sidecar(root, "a.b")
        assertEquals(jar.parentFile, sidecar.parentFile)
        assertEquals("a.b.jar", jar.name)
        assertEquals("a.b.json", sidecar.name)
    }
}
