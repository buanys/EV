package ev.buan.plugins.agent.api

import org.cordis.ServiceKey

/**
 * 工具执行前的一道闸：只能**拒绝或弃权**，不能改参数、不能重排顺序。
 *
 * 这是从 DSH 的 monotonic guards 借来的唯一一条规矩：允许"加一道否决"，
 * 不允许"把别人的判断挤掉"。理由很实际——如果后者先注册的 guard 能被前者绕过，
 * 那安全策略的强度就取决于插件安装顺序，而安装顺序是用户随手点出来的。
 *
 * 与 [ApprovalService] 的区别：
 *  - [ApprovalService] 问**人**，慢，一次一个决策；
 *  - [ToolGuard] 问**代码**，快，用来做"这类命令一律不许""这个路径不在工作区"
 *    这种不需要人类介入的硬规则。
 *
 * 典型实现：路径白名单、危险参数模式（`rm -rf /`）、配额、租户隔离。
 * 都不该写进 brain——brain 只负责按顺序问一遍。
 */
fun interface ToolGuard {
    /**
     * 检查一次工具调用。
     *
     * @return null 表示放行；非 null 是**拒绝理由**，会原样作为工具结果交回模型，
     *   让模型知道被谁拦下、为什么，而不是收到一个语焉不详的失败。
     *
     * 抛异常视为拒绝：一个坏掉的 guard 不能变成放行。
     */
    suspend fun beforeTool(call: AgentToolCall): String?
}

/**
 * 挂在 brain 上的 guard 注册表。
 *
 * 由 brain 发布；其它插件 `lookup` 后 [register]，并在卸载时调用返回的撤销函数。
 * 撤销函数幂等——重复调用不会把别人的 guard 一起摘掉。
 *
 * 顺序即注册顺序，`vet` 从头问到第一条拒绝为止。注册表不排序、不合并、
 * 不提供"覆盖"，因为一条能覆盖另一条的规则等于没有规则。
 */
interface ToolGuardRegistry {
    /**
     * 注册一条 guard，返回撤销函数。
     *
     * 撤销函数只影响这一次注册：[register] 两次得到两个独立的句柄，
     * 调其中一个不会连带撤销另一个。
     */
    fun register(guard: ToolGuard): () -> Unit

    /**
     * 依次询问全部 guard，返回第一条拒绝理由；全部弃权时返回 null。
     *
     * 某个 guard 抛异常时按"拒绝"处理，理由里带上异常类型，方便定位是哪个插件坏了。
     */
    suspend fun vet(call: AgentToolCall): String?

    companion object {
        val Key = ServiceKey<ToolGuardRegistry>("agent/tool-guards")
    }
}
