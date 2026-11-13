package ev.buan

/**
 * 扫描期的一个问题。**结构化，不是拼好的字符串**。
 *
 * 为什么不能直接抛字符串：字符串没法分类，界面只能原样罗列，用户看到的是一屏
 * 异常类名和绝对路径。分类之后，界面才能决定"哪些该给按钮、哪些该折叠、哪些
 * 只该进详情"。
 *
 * [title] 是给人看的：说清哪里不对、能怎么办，不出现异常类名和绝对路径。
 * [detail] 保留原始信息（异常消息、文件路径），只在用户展开时出现。
 */
data class PluginIssue(
    val kind: PluginIssueKind,
    /** 归属插件；null 表示这是全局问题（目录本身、权限门）。 */
    val pluginId: String? = null,
    val title: String,
    val detail: String? = null,
)

enum class PluginIssueKind {
    /** 宿主缺了某个系统权限（例如「所有文件访问」），用户能去开。 */
    Permission,

    /** 投放目录不存在或无法创建。 */
    Directory,

    /** 容器元数据缺失或非法（sidecar 坏了、缺 entryClass）。 */
    Metadata,

    /** 宿主 API 版本不兼容。 */
    Incompatible,

    /** 依赖缺失或解析不出来。 */
    Dependency,

    /** 已接受的容器在加载/激活阶段失败。 */
    Load,

    /** 其他 IO 故障。 */
    Io,
}
