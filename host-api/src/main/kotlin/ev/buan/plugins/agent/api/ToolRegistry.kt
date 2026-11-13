package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * The set of tools available to an agent, published by a tools plugin.
 *
 * Lookup is optional by design: an agent without a registry still answers, only without tools.
 *
 * The registry is also a bus: capability plugins (agent-shell, device-context, ...) do not publish
 * their own registry - the directory holds exactly one - they [register] their tools into the
 * published one under their own plugin id, and [unregister] when they deactivate. Consumers only
 * ever read through [specs] / [lookup], so a tool set changing between turns is a normal event,
 * not a broken dependency.
 */
interface ToolRegistry {
    /** Every tool currently offered, in a stable order, for the model request. */
    fun specs(): List<ToolSpec>

    /** The tool named [name], or null when no plugin offers it. */
    fun lookup(name: String): Tool?

    /**
     * Adds [tools] under [owner] (convention: the contributing plugin's id), replacing that owner's
     * previous set, so a re-activated plugin simply registers again.
     *
     * Tool names must be unique across owners: a clash with another owner's tool throws
     * [IllegalArgumentException], because a name the model cannot resolve to exactly one tool is a
     * publishing bug, not a runtime condition to degrade around.
     */
    fun register(owner: String, tools: List<Tool>)

    /** Drops everything [owner] registered. An unknown owner is a no-op. */
    fun unregister(owner: String)

    companion object {
        val Key = ServiceKey<ToolRegistry>("agent/tool-registry")
    }
}
