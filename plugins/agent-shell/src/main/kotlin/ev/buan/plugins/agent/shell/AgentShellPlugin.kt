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
class AgentShellPlugin : Plugin<Unit>, AgentSurface {
    override val name = "agent-shell"
    override val title = "agent Shell"
    override val body = "run_shell：经 su 执行 root 命令。高危工具，每次执行都需人工审批；授权记录见插件管理页。"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = directory.lookup(ToolRegistry.Key)
        if (registry == null) {
            // The sidecar dependency should make this unreachable; a hand-built container without
            // it degrades to "no tools" instead of crashing the host.
            return
        }
        registry.register(PLUGIN_ID, ShellTools.all(SuShellRunner()))
        // Identity is unnecessary here (unregister is owner-scoped): even a stale disposer can
        // only remove this plugin's own group, which is exactly what disposal means.
        effect.collect {
            registry.unregister(PLUGIN_ID)
        }
    }

    private companion object {
        const val PLUGIN_ID = "ev.buan.plugins.agent.shell"
    }
}
