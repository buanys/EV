package ev.buan.plugins.agent.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ev.buan.plugins.agent.api.ToolRegistry
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.AgentPage
import org.cordis.api.AgentSurface
import org.cordis.api.PluginStorage
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
class AgentShellPlugin :
    Plugin<Unit>,
    AgentSurface,
    AgentPage,
    org.cordis.api.ManifestProvider,
    org.cordis.api.PluginSettings {
    override val name = "agent-shell"
    override val title = "agent Shell"
    override val body = "run_shell：经 su 执行 root 命令。高危工具，每次执行都需人工审批；授权记录见插件管理页。"

    /**
     * Only the directory is injected. Storage is deliberately **not** declared: injecting a service
     * the sidecar does not request is a silent dead end - the fiber never leaves INACTIVE, apply()
     * never runs, and the plugin still counts as active. The timeout setting falls back to the
     * default when the binding is absent, which is exactly what happens without the grant.
     */
    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    /** Set during apply; the console is only reachable after activation. */
    private var registry: ToolRegistry? = null

    /**
     * The directory this plugin was activated under.
     *
     * The page looks the bus up per composition instead of trusting [registry]: the plugin can be
     * re-activated (the page is re-composed with the live instance), and a field written on the IO
     * dispatcher is not guaranteed to be visible on the main thread without a barrier.
     */
    @Volatile
    private var directory: ServiceDirectory? = null

    override val pageTitle: String = "终端"

    /** Opened from a menu, never the app root. */
    override val isHomeCandidate: Boolean = false

    @Composable
    override fun Page(modifier: Modifier, onBack: () -> Unit) {
        val bus = directory?.lookup(ToolRegistry.Key) ?: registry
        android.util.Log.i(
            "EV",
            "shell page: instance=${System.identityHashCode(this)} " +
                "directory=${directory != null} registry=${registry != null} " +
                "bus=${bus != null} runShell=${bus?.lookup("run_shell") != null}",
        )
        TerminalPage(registry = bus, onBack = onBack, modifier = modifier)
    }

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        android.util.Log.i("EV", "shell apply: entered instance=${System.identityHashCode(this)}")
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key)
        if (registry == null) {
            // The sidecar declares agent.tools as a dependency, so this is a host-ordering bug, not
            // a normal state. Returning quietly used to leave the card looking healthy while the
            // model simply never saw `run_shell` - fail loudly instead, so `failures` says why.
            error("工具总线未就绪：agent-tools 必须先激活（sidecar 依赖 ev.buan.plugins.agent.tools）")
        }
        storage = ctx.get(org.cordis.api.PluginStorage.Key)
        // The saved value is read once at activation; the tool asks for it per call, so a change
        // made in settings applies to the next command instead of the next process.
        defaultTimeout = runCatching {
            storage?.read(TIMEOUT_FILE)?.decodeToString()?.trim()?.toIntOrNull()
        }.getOrNull()?.coerceIn(1, ShellTools.MAX_TIMEOUT_SECONDS)
            ?: ShellTools.DEFAULT_TIMEOUT_SECONDS
        registry.register(PLUGIN_ID, ShellTools.all(ProbingShellRunner()) { defaultTimeout })
        this.registry = registry
        this.directory = directory
        android.util.Log.i(
            "EV",
            "shell apply: instance=${System.identityHashCode(this)} " +
                "registry=${this.registry != null} directory=${this.directory != null}",
        )
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
        version = "1.1.0",
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
