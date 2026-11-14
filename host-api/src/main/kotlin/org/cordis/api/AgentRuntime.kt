package org.cordis.api

import org.cordis.ServiceKey

/**
 * 一轮 agent 运行时的运行态，供管理页面直接展示。
 *
 * 这些字段都是"现在能看见的事实"，不是统计：进程有没有活着、它是哪一个文件、
 * 这一轮允许多少次模型调用、系统提示是什么。管理页要能回答"它现在到底怎么跑"，
 * 而不是只给一个"已启用"。
 */
data class AgentRuntimeStatus(
    /** 进程存活。false 时其余进程字段为 null，配置字段仍然有效。 */
    val alive: Boolean,
    val pid: Long? = null,
    val startedAtMillis: Long? = null,
    /** 二进制实际路径（native library）。 */
    val binaryPath: String? = null,
    val binaryBytes: Long? = null,
    /** 协议版本，来自 hello。 */
    val protocolVersion: Int? = null,
    /** 一轮里最多几次模型调用。 */
    val maxTurns: Int = 16,
    /** 这一轮的系统提示；null 表示用内置的那段。 */
    val preamble: String? = null,
    /** 最近一次失败的原文，成功一轮后清空。 */
    val lastError: String? = null,
)

/**
 * 主循环插件发布的运行时管理面。
 *
 * 宿主的管理页只读写这个接口；进程怎么起、怎么停、参数怎么落到下一轮，
 * 都是主循环插件自己的事。这样页面不需要知道 IPC 协议，插件也不需要知道界面。
 */
interface AgentRuntime {
    /** 读当前状态，不会因为进程没起来就抛异常。 */
    fun status(): AgentRuntimeStatus

    /** 下一轮起生效的模型调用上限；小于 1 会被夹到 1。 */
    fun setMaxTurns(turns: Int)

    /** 下一轮起生效的系统提示；空白表示回到内置默认。 */
    fun setPreamble(text: String?)

    /** 杀掉当前进程；下一次对话会重新起一个。 */
    suspend fun restart()

    /** 停掉进程并保持停止，直到下一轮对话。 */
    suspend fun stop()

    companion object {
        val Key = ServiceKey<AgentRuntime>("agent/runtime-control")
    }
}
