package ev.buan.plugins.forge.compiler

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.OutputMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * 真编译器：K2（跑在 ART 上）→ 字节码 → d8 → dex → 插件容器。
 *
 * 三个必须守住的点，都在 `docs/research/on-device-compiler.md` 里有出处：
 *  - 用 `K2JVMCompiler().exec(...)` 而不是 `main()`：后者会退出宿主进程；
 *  - 用 `D8.run(D8Command)` 而不是 `D8.main()`：同上；
 *  - `kotlin-stdlib` 只作 `--lib` 参考，**绝不进 dex**（否则 `DUPLICATE_PLATFORM_CLASS`）。
 *
 * 调用是阻塞的（编译器本身就是阻塞的），所以整体切到 IO 调度器，不占 UI 线程。
 */
internal class KotlinForgeCompiler(
    private val loader: ClassLoader,
    private val root: File,
) : ForgeCompiler {

    private val payload by lazy { ForgePayload(loader, File(root, PAYLOAD_DIR)) }

    override suspend fun compile(request: ForgeRequest): ForgeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        fun failed(stage: ForgeStage, diagnostics: String): ForgeResult =
            ForgeResult.Failed(stage, diagnostics, System.currentTimeMillis() - started)

        // 形状不对就不用启动编译器了——这些错误只有几行，比一次完整编译便宜三个数量级。
        pluginIdProblem(request.pluginId)?.let { return@withContext failed(ForgeStage.PACKAGE, it) }
        entryClassProblem(request.entryClass)?.let { return@withContext failed(ForgeStage.PACKAGE, it) }
        if (request.source.isBlank()) return@withContext failed(ForgeStage.COMPILE, "源码为空")

        if (!root.isDirectory && !root.mkdirs()) {
            return@withContext failed(
                ForgeStage.PAYLOAD,
                "工作目录不可写：${root.absolutePath}。锻造需要共享存储访问权限（宿主的 MANAGE_EXTERNAL_STORAGE）。",
            )
        }

        val work = File(root, request.pluginId).apply { mkdirs() }
        val srcDir = File(work, "src").apply { mkdirs() }
        val classDir = File(work, "classes").apply { deleteRecursively(); mkdirs() }
        val dexDir = File(work, "dex").apply { deleteRecursively(); mkdirs() }

        val jars = try {
            payload.classpathJars()
        } catch (error: Throwable) {
            return@withContext failed(ForgeStage.PAYLOAD, error.message ?: error.toString())
        }

        val source = File(srcDir, request.fileName)
        source.writeText(request.source)

        // 1. 编译
        val diagnostics = ByteArrayOutputStream()
        val exit = try {
            K2JVMCompiler().exec(
                PrintStream(diagnostics, true),
                "-no-stdlib",
                "-no-jdk",
                "-jvm-target", "17",
                "-d", classDir.absolutePath,
                "-classpath", jars.joinToString(File.pathSeparator) { it.absolutePath },
                source.absolutePath,
            )
        } catch (error: Throwable) {
            return@withContext failed(ForgeStage.COMPILE, summarize(diagnostics.toString(), error))
        }

        val classFiles = classDir.walkTopDown().filter { it.extension == "class" }.toList()
        if (exit != ExitCode.OK || classFiles.isEmpty()) {
            return@withContext failed(ForgeStage.COMPILE, summarize(diagnostics.toString(), null))
        }

        // 2. 转 dex
        try {
            val builder = D8Command.builder()
            classFiles.forEach { builder.addClassProgramData(it.readBytes(), Origin.unknown()) }
            builder.addLibraryFiles(payload.androidStub().toPath())
            builder.setMinApiLevel(33)
            builder.setOutput(dexDir.toPath(), OutputMode.DexIndexed)
            D8.run(builder.build())
        } catch (error: Throwable) {
            return@withContext failed(ForgeStage.DEX, summarize(null, error))
        }

        // 3. 打包
        val dexFiles = dexDir.listFiles { file -> file.extension == "dex" }.orEmpty().sortedBy { it.name }
        if (dexFiles.isEmpty()) return@withContext failed(ForgeStage.PACKAGE, "d8 运行完成但没有产出 dex")

        val jar = File(work, "${request.pluginId}.jar")
        try {
            ZipOutputStream(jar.outputStream().buffered()).use { zip ->
                dexFiles.forEach { dex ->
                    zip.putNextEntry(ZipEntry(dex.name))
                    dex.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            return@withContext failed(ForgeStage.PACKAGE, summarize(null, error))
        }

        val sidecar = sidecarJson(request)
        File(work, "${request.pluginId}.json").writeText(sidecar)

        ForgeResult.Ok(
            pluginId = request.pluginId,
            jarPath = jar.absolutePath,
            jarBytes = jar.length(),
            sidecarJson = sidecar,
            dexCount = dexFiles.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 诊断必须留得住 `Caused by`。
     *
     * 编译器最外层只给一句 `Exception while generating code`，真原因在 `Caused by` 里
     * （研究文档 §4.8）。所以超长时不按"前 N 个字符"截断，而是保住所有错误/因果行。
     */
    private fun summarize(text: String?, error: Throwable?): String {
        val head = text?.trim().orEmpty()
        val tail = error?.let { "\n" + it.stackTraceToString() }.orEmpty()
        val full = (head + tail).ifBlank {
            "编译器没有给出任何诊断——这通常意味着载荷有问题（缺 class 或 services），而不是源码写错了。"
        }
        if (full.length <= MAX_DIAGNOSTICS) return full

        val keep = full.lineSequence()
            .filter { line ->
                line.contains("error:") || line.contains("Caused by") ||
                    line.contains("Exception") || line.contains("Unresolved")
            }
            .take(MAX_KEY_LINES)
            .joinToString("\n")
        return full.take(MAX_DIAGNOSTICS / 2) +
            "\n…（省略 " + (full.length - MAX_DIAGNOSTICS) + " 字符）…\n" + keep
    }

    private companion object {
        const val PAYLOAD_DIR = ".payload"
        const val MAX_DIAGNOSTICS = 8_000
        const val MAX_KEY_LINES = 24
    }
}
