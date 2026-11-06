package ev.buan.plugins.forge.compiler

import org.cordis.ServiceKey

/**
 * 一次锻造请求：一份 Kotlin 源码 → 一个可安装的插件容器。
 *
 * 参数只覆盖"做一个能力插件"这件事的最小面：id、入口类、源码。版本、权限、依赖声明由调用方
 * 显式给出，默认值只是省事，不是猜测——装进 mods 的 sidecar 会照抄这些字段。
 */
data class ForgeRequest(
    /** 插件的容器 id，例如 `taotie.forged`；同时决定 sidecar 的 `id`。 */
    val pluginId: String,
    /** 实现 `org.cordis.Plugin` 的全限定类名。 */
    val entryClass: String,
    /** 源文件名，例如 `Forged.kt`；只为诊断里能对上号。 */
    val fileName: String,
    /** 源码全文。 */
    val source: String,
    val version: String = "1.0.0",
    /** 声明的宿主能力，写进 sidecar 的 `permissions`。 */
    val permissions: List<String> = emptyList(),
    /** 依赖的插件 id，写进 sidecar 的 `dependencies`，决定加载顺序。 */
    val dependencies: List<String> = emptyList(),
)

/** 锻造在哪一步失败。诊断要能指回阶段，模型才知道是改源码还是改声明。 */
enum class ForgeStage {
    /** 载荷（编译器/d8）还没就绪或解不出来。 */
    PAYLOAD,

    /** K2 编译失败：源码有问题。 */
    COMPILE,

    /** 字节码转 dex 失败：通常是 classpath 缺东西。 */
    DEX,

    /** 打包或落盘失败。 */
    PACKAGE,
}

sealed interface ForgeResult {
    /** 产物已落盘；[jarPath] 可交给宿主的安装通道。 */
    data class Ok(
        val pluginId: String,
        val jarPath: String,
        val jarBytes: Long,
        val sidecarJson: String,
        val dexCount: Int,
        val elapsedMs: Long,
    ) : ForgeResult

    /**
     * 失败必须带完整诊断。
     *
     * 编译器会把真实原因藏在 `Caused by` 里，外层只剩一句 `Exception while generating code`
     * （docs/research/on-device-compiler.md §4.8）。截断诊断 = 让模型没法自己修。
     */
    data class Failed(
        val stage: ForgeStage,
        val diagnostics: String,
        val elapsedMs: Long,
    ) : ForgeResult
}

/**
 * 设备端编译器，由 `forge-compiler` 插件发布。
 *
 * 为什么是一个服务而不是几条工具：工具面属于 `forge`，编译器属于这里。把重载荷（约 26 MB 的
 * 编译器 dex）关在一个可单独停用的插件里，用户不用锻造能力时就不必为它付类加载成本。
 */
interface ForgeCompiler {
    suspend fun compile(request: ForgeRequest): ForgeResult

    companion object {
        val Key = ServiceKey<ForgeCompiler>("forge/compiler")
    }
}
