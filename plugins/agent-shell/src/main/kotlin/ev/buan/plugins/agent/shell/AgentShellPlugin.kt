package ev.buan.plugins.agent.shell

import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentSurface
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * The agent's hands: `run_shell`, executed through the device's `su`.
 *
 * This plugin owns no registry of its own - it mounts its tools onto the bus [ToolRegistry] that
 * agent-tools publishes, under its own plugin id, and unmounts them in the same step that disposes
 * it. The sidecar's dependency on agent.tools guarantees the bus activates first.
 *
 * Safety stack, in order: ROOT_SHELL is declaration + user grant + audit (the host cannot gate
 * `su` itself - see HostPermission); Magisk asks per-UID at the OS level; and every call is
 * [ev.buan.plugins.agent.api.ToolSpec.dangerous], so the brain suspends on the chat UI's approval
 * gate before any command runs. Root is never a default capability: with the UI disabled the
 * calls fail closed, and this plugin holds no approval logic of its own.
 */
class AgentShellPlugin : Plugin<Unit>, AgentSurface, org.cordis.api.ManifestProvider, org.cordis.api.PluginSettings {
    override val name = "agent-shell"
    override val title = "agent Shell"
    override val body = "run_shell：经 su 执行 root 命令。高危工具，每次执行都需人工审批；授权记录见插件管理页。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, org.cordis.api.PluginStorage.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key)
        if (registry == null) {
            // The sidecar dependency should make this unreachable; a hand-built container without
            // it degrades to "no tools" instead of crashing the host.
            return
        }
        storage = ctx.require(org.cordis.api.PluginStorage.Key)
        // The saved value is read once at activation; the tool asks for it per call, so a change
        // made in settings applies to the next command instead of the next process.
        defaultTimeout = runCatching {
            storage?.read(TIMEOUT_FILE)?.decodeToString()?.trim()?.toIntOrNull()
        }.getOrNull()?.coerceIn(1, ShellTools.MAX_TIMEOUT_SECONDS)
            ?: ShellTools.DEFAULT_TIMEOUT_SECONDS
        registry.register(PLUGIN_ID, ShellTools.all(ProbingShellRunner()) { defaultTimeout })
        // Identity is unnecessary here (unregister is owner-scoped): even a stale disposer can
        // only remove this plugin's own group, which is exactly what disposal means.
        effect.collect {
            registry.unregister(PLUGIN_ID)
        }
    }

    private var storage: org.cordis.api.PluginStorage? = null
    private var defaultTimeout: Int = ShellTools.DEFAULT_TIMEOUT_SECONDS

    override fun read(key: String): String? =
        when (key) {
            "timeout" -> defaultTimeout.toString()
            else -> null
        }

    override fun write(key: String, value: String) {
        if (key != "timeout") return
        val seconds = value.trim().toIntOrNull()?.coerceIn(1, ShellTools.MAX_TIMEOUT_SECONDS) ?: return
        defaultTimeout = seconds
        runCatching { storage?.atomicWrite(TIMEOUT_FILE, seconds.toString().encodeToByteArray()) }
    }

    private companion object {
        const val TIMEOUT_FILE = "settings/timeout.txt"
        const val PLUGIN_ID = "ev.buan.plugins.agent.shell"
    }

    /**
     * What this container gives the app, stated here rather than discovered by reading its code.
     */
    override val manifest: org.cordis.api.PluginManifest = org.cordis.api.PluginManifest(
        id = "ev.buan.plugins.agent.shell",
        name = "Shell",
        version = "0.1.0",
        description = "给模型一个 shell：run_shell 是危险工具，每次调用都要人工批准。",
        capabilities = listOf(
            org.cordis.api.PluginCapability(
                id = "run_shell",
                kind = org.cordis.api.PluginCapability.Kind.TOOL,
                title = "执行 shell 命令",
                description = "在设备上跑一条命令，返回 stdout / stderr / 退出码。默认走审批门。",
            ),
        ),
        permissions = listOf("android.permission.INTERNET"),
        settings = listOf(
            org.cordis.api.PluginSetting(
                key = "timeout",
                title = "默认超时",
                kind = org.cordis.api.PluginSetting.Kind.TEXT,
                description = "单条命令的最长运行时间，1 到 120 秒。",
                value = "30",
            ),
        ),
    )

}
