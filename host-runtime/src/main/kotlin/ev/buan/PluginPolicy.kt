package ev.buan

/**
 * 容器**不许定义**的包：平台、Kotlin 运行时、内核，以及宿主已经提供的每一个包。
 *
 * 这份名单刻意独立于 `HostPackages`：一个包可以不再共享（`org.json` 就是这样，
 * 内置副本删掉之后），但它永远不能变成插件可以自己定义的——那会造出第二个同名类，
 * 谁赢取决于类加载顺序。
 *
 * 与类加载器的共享前缀镜像，所以复制了其中任一个的容器会被直接拒绝，
 * 而不是加载出两套类身份。
 */
internal object PluginPolicy {

    private val PLATFORM_PREFIXES = listOf(
        "java", "javax", "android", "dalvik",
        "kotlin", "kotlinx.coroutines",
        "org.cordis",
        "org.json", "org.apache.http", "org.xmlpull", "org.w3c.dom", "org.xml.sax",
        "junit",
    )

    fun reservedPrefixes(): List<String> =
        PLATFORM_PREFIXES + org.cordis.api.HostPackages.shared + "ev.buan.ui"
}

/**
 * 一条容器决策一行，打在 logcat 的 `EV` 标签上。
 *
 * 安装在启动时发生，那时还没有界面，事后唯一能观测到决策的地方就是日志——
 * 只看卡片状态分不出"被拒绝"和"从没见过"。
 */
internal fun tracePlugin(message: String) = android.util.Log.i("EV", "plugin $message")
