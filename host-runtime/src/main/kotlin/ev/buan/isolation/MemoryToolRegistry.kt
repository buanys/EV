package ev.buan.isolation

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec

/**
 * 只记不发的工具表：给子进程用。
 *
 * 宿主那份 `ToolRegistry` 由 `agent-tools` 插件提供，它要发布到服务目录、要接审批门、
 * 要按 owner 撤下——那些都是**宿主进程的事**。子进程只需要一个地方接住插件注册的工具，
 * 再把 `specs()` 交回给 binder，所以这里只有一张表。
 *
 * 唯一继承来的规则是名字唯一：同名工具证明插件自己没想清楚，报错比静默覆盖好。
 */
internal class MemoryToolRegistry : ToolRegistry {

    private val byOwner = LinkedHashMap<String, List<Tool>>()

    override fun specs(): List<ToolSpec> = byOwner.values.flatten().map { it.spec }

    override fun lookup(name: String): Tool? =
        byOwner.values.asSequence().flatten().firstOrNull { it.spec.name == name }

    override fun register(owner: String, tools: List<Tool>) {
        val taken = tools.map { it.spec.name }.toSet().intersect(specs().map { it.name }.toSet())
        require(taken.isEmpty()) { "工具名重复：${taken.joinToString(", ")}" }
        byOwner[owner] = tools.toList()
    }

    override fun unregister(owner: String) {
        byOwner.remove(owner)
    }
}
