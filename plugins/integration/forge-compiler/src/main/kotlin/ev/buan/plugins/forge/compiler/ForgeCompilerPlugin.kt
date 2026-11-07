package ev.buan.plugins.forge.compiler

import java.io.File
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 锻造工具链：把编译器与 d8 作为**能力**发布出去，本身不向模型暴露任何工具。
 *
 * 为什么单独一个插件（ADR-0004 D2）：载荷约 26 MB / 11 dex，用户不锻造时不该为它付类加载成本。
 * 停用本插件后 `forge` 仍然可以装（`forge_build` 会如实回答"工具链未启用"），
 * 这正是 SERVICE_DIRECTORY 的"查询而非依赖"语义。
 *
 * 编译器类只能从**本插件容器的 dex** 里加载——这是宿主的类加载器边界决定的；
 * 载荷里的 jar 是给编译器当 `-classpath` 的文件，两者不是一回事。
 */
class ForgeCompilerPlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.forge.compiler",
        name = "插件编译器",
        version = "1.0.1",
        description = "Kotlin 源码到 dex 的编译链，供插件工坊调用。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("forge/compiler", org.cordis.api.PluginCapability.Kind.SERVICE, "编译源码", "编译并返回 dex 与依赖信息。"),
        ),
    )

    override val name = "forge-compiler"
    override val title = "锻造工具链"
    override val body = "设备端 Kotlin 编译器与 d8（重载荷，按需启用）"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val loader = javaClass.classLoader ?: error("forge-compiler: 没有可用的类加载器")
        val compiler = KotlinForgeCompiler(loader, File(WORK_ROOT))

        directory.publish(ForgeCompiler.Key, compiler)
        // 停用即撤回，避免消费者拿着一个其载荷已经被释放的编译器继续调用。
        // 身份检查与 agent-memory 同款：不让过期的 disposer 撤掉重新激活后的新实例。
        effect.collect {
            if (directory.lookup(ForgeCompiler.Key) === compiler) directory.retract(ForgeCompiler.Key)
        }
    }

    private companion object {
        /**
         * 工作目录。
         *
         * v1 用共享存储：编译器的 `-classpath` 与 d8 的 `--lib` 都要磁盘路径，而插件拿不到
         * 宿主的 `Context`，`PluginStorage` 又只给字节。探针已实测 `/sdcard/EV/` 可写。
         * 后续（ADR-0004 待办 5）改成宿主提供一个"工作目录"契约，把这里挪回私有目录。
         */
        const val WORK_ROOT = "/sdcard/EV/forge"
    }
}
