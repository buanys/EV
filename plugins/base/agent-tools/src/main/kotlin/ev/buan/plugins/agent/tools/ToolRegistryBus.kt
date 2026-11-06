package ev.buan.plugins.agent.tools

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The tool bus published as the single [ToolRegistry] in the host's service directory.
 *
 * Owners register and unregister in whole groups, keyed by plugin id; the read side flattens the
 * groups in first-registration order, so the model sees a stable declaration list every turn.
 * Read snapshots are cheap copies under a lock: a tool set changing mid-turn can never tear the
 * list a consumer is iterating.
 *
 * A re-registering owner replaces its own group in place (order is kept); a name clashing with a
 * *different* owner is rejected, because an ambiguous tool name would silently route the model's
 * call to whichever group happened to come first.
 */
internal class ToolRegistryBus : ToolRegistry {
    private val lock = ReentrantLock()

    /** Owner id -> its tools, in first-registration order of owners. */
    private val groups = LinkedHashMap<String, List<Tool>>()

    override fun specs(): List<ToolSpec> = lock.withLock {
        groups.values.flatten().map { it.spec }
    }

    override fun lookup(name: String): Tool? = lock.withLock {
        groups.values.flatten().firstOrNull { it.spec.name == name }
    }

    override fun register(owner: String, tools: List<Tool>) {
        require(owner.isNotBlank()) { "owner 不能为空" }
        lock.withLock {
            val names = tools.map { it.spec.name }
            require(names.size == names.toSet().size) { "同一 owner 内工具名必须唯一：$names" }
            val taken = groups.filterKeys { it != owner }
                .values.flatten()
                .mapTo(HashSet()) { it.spec.name }
            val clash = names.firstOrNull { it in taken }
            require(clash == null) { "工具名已被其他插件占用：$clash" }
            groups[owner] = tools.toList()
        }
    }

    override fun unregister(owner: String) {
        lock.withLock { groups.remove(owner) }
    }
}
