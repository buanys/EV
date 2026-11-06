package ev.buan.plugins.forge

import java.io.File

/**
 * 锻造产物在磁盘上的位置。
 *
 * 编译器和工具面是两个插件，传递产物却只能靠磁盘——编译器把结果写进工作目录，
 * 安装工具再从同一个位置读回来。这条约定必须只有一个来源：
 * 两边各拼一次路径，早晚会在"多一个斜杠""id 大小写"这种地方对不上。
 *
 * 纯 Kotlin，不碰 Android，所以能直接在 JVM 单测里跑。
 */
internal object ForgeArtifacts {

    /** 与 forge-compiler 的工作根保持一致（那边的 `ForgeCompilerPlugin.WORK_ROOT`）。 */
    const val WORK_ROOT = "/sdcard/EV/forge"

    fun directory(root: File, pluginId: String): File = File(root, pluginId)

    fun jar(root: File, pluginId: String): File = File(directory(root, pluginId), "$pluginId.jar")

    fun sidecar(root: File, pluginId: String): File = File(directory(root, pluginId), "$pluginId.json")

    /**
     * 安装前的前置检查：id 合法、jar 与 sidecar 都在。
     *
     * 返回 null 表示可以直接装；否则返回一句能直接给模型看的原因。
     * 这里**不做**内容校验——那是宿主的事，工具重复一遍只会得到两份不一致的真相。
     */
    fun problem(root: File, pluginId: String): String? {
        if (pluginId.isBlank()) return "缺少 plugin_id"
        if (!pluginId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            return "非法 plugin_id「$pluginId」：只允许字母数字与 ._-，且不能以符号开头"
        }
        val jar = jar(root, pluginId)
        val sidecar = sidecar(root, pluginId)
        if (!jar.isFile) return "找不到产物 ${jar.absolutePath}：先用 forge_build 编译 $pluginId"
        if (!sidecar.isFile) return "找不到 sidecar ${sidecar.absolutePath}：同一次 forge_build 会生成它"
        if (jar.length() <= 0L) return "产物是空文件：${jar.absolutePath}"
        return null
    }
}
