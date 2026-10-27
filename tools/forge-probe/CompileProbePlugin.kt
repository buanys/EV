package ev.buan.plugins.compileprobe

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompileProbePlugin : Plugin<Unit>, AgentSurface {
    override val name = "compile-probe"
    override val title = "手机锻造探针"
    override val body = "在设备上编译一个完整插件并装进 mods"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        Thread {
            val log = StringBuilder()
            val t0 = System.currentTimeMillis()
            try {
                val work = File("/sdcard/EV/compileprobe").apply { mkdirs() }
                val libDir = File(work, "libs").apply { mkdirs() }

                // 1. 解出编译依赖
                val cpList = mutableListOf<File>()
                for (assetName in listOf("core.jar", "plugin-api.jar", "kotlin-stdlib.jar", "android-stub.jar", "annotations.jar")) {
                    val target = File(libDir, assetName)
                    if (!target.exists()) {
                        val input = javaClass.classLoader?.getResourceAsStream(assetName)
                        if (input != null) {
                            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                        }
                    }
                    if (target.exists()) cpList.add(target)
                }
                log.append("LIBS=").append(cpList.size).append('\n')

                // 2. 写出要编译的源码
                val src = File(work, "Forged.kt")
                src.writeText(FORGED_SOURCE)

                // 3. 编译
                val out = File(work, "classes").apply { mkdirs() }
                val errBuf = ByteArrayOutputStream()
                val args = mutableListOf(
                    "-no-stdlib", "-no-jdk", "-jvm-target", "17",
                    "-d", out.absolutePath,
                    "-classpath", cpList.joinToString(File.pathSeparator) { it.absolutePath },
                    src.absolutePath
                )
                val code = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
                    PrintStream(errBuf, true), *args.toTypedArray()
                )
                log.append("COMPILE_EXIT=").append(code).append('\n')
                val classFiles = out.walkTopDown().filter { it.extension == "class" }.toList()
                log.append("CLASSES=").append(classFiles.size).append('\n')
                val errText = errBuf.toString()
                if (errText.isNotBlank()) {
                    // 完整 stderr 落盘，日志里只留关键行
                    runCatching { File(work, "compiler-stderr.txt").writeText(errText) }
                    log.append("COMPILER_OUT=").append(errText.take(600)).append('\n')
                    val key = errText.lines().filter {
                        it.contains("Caused by") || it.contains("Exception") ||
                        it.contains("Error") || it.contains("Unsupported")
                    }.take(12).joinToString("\n")
                    log.append("ROOT_CAUSE=").append(key).append('\n')
                }

                if (classFiles.isNotEmpty()) {
                    // 4. d8 转 dex
                    val dexOut = File(work, "dex").apply { mkdirs() }
                    val builder = com.android.tools.r8.D8Command.builder()
                    for (cf in classFiles) {
                        builder.addClassProgramData(cf.readBytes(), com.android.tools.r8.origin.Origin.unknown())
                    }
                    val stub = File(libDir, "android-stub.jar")
                    if (stub.exists()) builder.addLibraryFiles(stub.toPath())
                    builder.setMinApiLevel(33)
                    builder.setOutput(dexOut.toPath(), com.android.tools.r8.OutputMode.DexIndexed)
                    com.android.tools.r8.D8.run(builder.build())
                    val dexFiles = dexOut.listFiles()?.filter { it.extension == "dex" } ?: emptyList()
                    log.append("DEX_COUNT=").append(dexFiles.size).append('\n')

                    // 5. 打包成插件 jar + json，直接装进 mods
                    if (dexFiles.isNotEmpty()) {
                        val jarFile = File("/sdcard/EV/mods/taotie-forged.jar")
                        ZipOutputStream(jarFile.outputStream().buffered()).use { zos ->
                            for (d in dexFiles.sortedBy { it.name }) {
                                zos.putNextEntry(ZipEntry(d.name))
                                d.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                        val json = File("/sdcard/EV/mods/taotie-forged.json")
                        json.writeText(
                            "{\"id\":\"taotie.forged\",\"version\":\"1.0.0\"," +
                            "\"entryClass\":\"taotie.forged.ForgedPlugin\",\"apiVersion\":1,\"dependencies\":[]}"
                        )
                        log.append("PLUGIN_JAR=").append(jarFile.absolutePath)
                            .append(':').append(jarFile.length()).append('\n')
                        log.append("PLUGIN_JSON=").append(json.absolutePath).append('\n')
                        log.append("NEXT=重启 EV 加载 taotie-forged\n")
                    }
                }
                log.append("TOTAL_MS=").append(System.currentTimeMillis() - t0).append('\n')
            } catch (t: Throwable) {
                log.append("FAILED=").append(t.javaClass.name).append(':').append(t.message).append('\n')
                log.append("TOTAL_MS=").append(System.currentTimeMillis() - t0).append('\n')
                log.append(t.stackTraceToString().take(1800))
            }
            runCatching { File("/sdcard/EV/compileprobe.txt").writeText(log.toString()) }
            System.out.println("FORGE_DONE")
        }.start()
    }

    companion object {
        private val FORGED_SOURCE = """
package taotie.forged

import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface

class ForgedPlugin : Plugin<Unit>, AgentSurface {
    override val name = "taotie-forged"
    override val title = "饕餮锻造的插件"
    override val body = "这个插件是手机自己编译出来的，没有电脑参与"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        System.out.println("FORGED_PLUGIN_ALIVE")
        runCatching {
            java.io.File("/sdcard/EV/forged-alive.txt")
                .writeText("FORGED_PLUGIN_ALIVE at " + System.currentTimeMillis())
        }
    }
}
        """.trimIndent()
    }
}
