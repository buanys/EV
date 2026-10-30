package ev.buan.plugins.forge.compiler

import java.io.File

/**
 * 载荷的运行时形态。
 *
 * 编译期 classpath 用的 jar 必须**在磁盘上**——`K2JVMCompiler` 的 `-classpath` 收的是路径，
 * 不是字节。所以容器里的资源要在第一次锻造时解到工作目录，之后复用。
 *
 * d8 的 `--lib`（`android-stub.jar`）也在其中：ART 上没有 `java.*` 的 class 文件，
 * dex 化时需要一个能把标准库符号解析出来的替身。
 */
internal class ForgePayload(
    private val loader: ClassLoader,
    private val work: File,
) {

    /** 解出全部 classpath jar，返回磁盘路径。缺任何一个都说明容器不完整，直接失败。 */
    fun classpathJars(): List<File> = LIB_ENTRIES.map { entry ->
        val target = File(File(work, "lib"), entry.substringAfterLast('/'))
        if (!target.isFile || target.length() == 0L) {
            val input = loader.getResourceAsStream(entry)
                ?: throw IllegalStateException("载荷缺少 $entry：forge-compiler 容器不完整，请重新打包")
            target.parentFile?.mkdirs()
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
        }
        target
    }

    /** d8 的库文件，让 dex 化时能解析 `java.*` 符号。 */
    fun androidStub(): File = classpathJars().first { it.name == ANDROID_STUB }

    private companion object {
        const val ANDROID_STUB = "android-stub.jar"

        /**
         * 白名单，顺序无关但集合固定：多给一个 jar 就是多一条 agent 能用到的东西。
         * 这里决定了锻造出的插件能引用什么——契约（core/plugin-api）、标准库、注解。
         * 第三方依赖不在 v1 能力边界内，见 docs/adr/0004-on-device-forge.md D5。
         */
        val LIB_ENTRIES = listOf(
            "lib/core.jar",
            "lib/plugin-api.jar",
            "lib/kotlin-stdlib.jar",
            "lib/android-stub.jar",
            "lib/annotations.jar",
        )
    }
}
