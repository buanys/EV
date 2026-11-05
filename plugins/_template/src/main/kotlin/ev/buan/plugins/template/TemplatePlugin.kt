package ev.buan.plugins.template

import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.ServiceKey
import org.cordis.api.AgentSurface
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 模板插件的服务契约。
 *
 * 在真实插件里，如果这个接口要被**别的插件**使用，它必须搬到 `:plugin-api`——
 * 插件之间不能互相编译依赖，只能靠共享契约 + `ServiceDirectory` 在运行时对接。
 * 只有本插件自己用的接口才可以留在这里。
 */
interface PingService {
    /** 回答一句固定的话，用来证明"服务真的发布出去了"。 */
    fun ping(): String

    companion object {
        val Key = ServiceKey<PingService>("template/ping")
    }
}

/**
 * 把实现抽成具名类，而不是在 [TemplatePlugin.apply] 里写匿名对象。
 *
 * 这样单测可以直接 `PongService().ping()`，不必先起一个宿主、一个 Context、
 * 一个 ServiceDirectory。插件壳越薄，能测的东西越多。
 */
internal class PongService : PingService {
    override fun ping(): String = "pong"
}

/**
 * 最小可运行的插件：拿到宿主的 [ServiceDirectory] → 发布一个服务 → 卸载时撤掉。
 *
 * ## 复制这个目录之后要改的四件事
 *
 * 1. `build.gradle.kts` 里的 `namespace` / `applicationId` / 产物名
 * 2. `src/main/AndroidManifest.xml` 里的 `cordis.entry`
 * 3. 本文件的包名、类名、[PluginManifest.id]
 * 4. `settings.gradle.kts` 里加一行 `include(":plugins:<你的id>")`
 *
 * 四处的 id 必须完全一致，少改一处就会出现"装上了但找不到入口"。
 *
 * ## 两个容易忘的纪律
 *
 * - **发布的东西必须在 `effect.collect` 里撤掉**，并用 `===` 确认撤的是自己那一个。
 *   插件被禁用、热重载、重新启用时都会走撤销路径；漏了就会留下幽灵服务。
 * - **不要在这里拿权限之外的能力**。sidecar 里没声明的权限，宿主不会给。
 */
class TemplatePlugin : Plugin<Unit>, AgentSurface, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = "ev.buan.plugins.template",
        name = "模板插件",
        version = "1.0.0",
        description = "复制这个目录来写新插件：发布一个服务，卸载时把它撤掉。",
        capabilities = listOf(
            PluginCapability(
                id = "template/ping",
                kind = PluginCapability.Kind.SERVICE,
                title = "示例服务",
                description = "回答一句 pong，用来验证发布与撤销的接线。",
            ),
        ),
    )

    override val name = "template"
    override val title = "模板插件"
    override val body = "一个最小可运行的插件：发布一个服务，卸载时把它撤掉。"

    /**
     * 声明"我可能用到什么"。
     *
     * 声明了才允许可选查询（`ctx.get`），没声明就用 `ctx.require` 硬依赖——
     * 后者在缺失时会让这个插件装不上，而不是运行到一半才空指针。
     */
    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val service = PongService()
        directory.publish(PingService.Key, service)
        // 复制后保留这行：装上新插件时，logcat 里能直接看到它真的跑起来了。
        android.util.Log.i("EV", "template: 已发布 template/ping")

        // 身份判断防止一个过期的撤销闭包把后来者发布的新实例撤掉。
        // 这不是防御性编程的洁癖：热重载会真的造出两个实例。
        effect.collect {
            if (directory.lookup(PingService.Key) === service) {
                directory.retract(PingService.Key)
            }
        }
    }
}
