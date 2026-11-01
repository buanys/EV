package ev.buan.plugins.forge

import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.forge.compiler.ForgeCompiler
import java.io.File
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.PluginInstallService
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 锻造能力：让 agent 给自己编插件，并把它装上去。
 *
 * 本插件是**薄的那一半**（ADR-0004 D1）：只提供工具面与前置检查。
 *  - 编译在 `forge-compiler` 里，通过 [ForgeCompiler] 现查现用；
 *  - 安装在宿主里，通过 [PluginInstallService] 现查现用。
 *
 * 两者都可能是"这一秒不在"的状态（插件被停用、宿主版本旧），所以都是可选查询：
 * 拿不到就如实回一句"未启用/不支持"，而不是让整颗插件起不来。
 */
class AgentForgePlugin : Plugin<Unit>, AgentSurface , org.cordis.api.ManifestProvider {
    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.forge",
        name = "插件工坊",
        version = "0.1.0",
        description = "在设备上编译并安装插件：源码到 dex 到容器，全在本机完成。",
        capabilities = listOf(
            org.cordis.api.PluginCapability("forge", org.cordis.api.PluginCapability.Kind.TOOL, "编译插件", "把一份源码编译成可安装的容器。"),
        ),
    )

    override val name = "forge"
    override val title = "锻造"
    override val body = "让 agent 在手机上编译并安装自己的插件（编译器在 forge-compiler 里，按需启用）"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key) ?: return

        registry.register(
            PLUGIN_ID,
            ForgeTools.all(
                root = File(ForgeArtifacts.WORK_ROOT),
                compilerLookup = { directory.lookup(ForgeCompiler.Key) },
                installLookup = { ctx.get(PluginInstallService.Key) },
            ),
        )
        effect.collect { registry.unregister(PLUGIN_ID) }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.forge"
    }
}
