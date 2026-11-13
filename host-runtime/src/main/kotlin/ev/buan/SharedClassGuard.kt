package ev.buan

import dalvik.system.DexFile
import org.cordis.api.HostPackages
import java.io.File

/**
 * 加载前的一致性闸门：容器的 dex 里不该出现宿主已经提供的包。
 *
 * 为什么必须查字节而不是比 Class 对象：Android 的 DexClassLoader 是 parent-first，
 * 插件里多打一份 `ev.buan.ui` 不会抛错，它会静默拿到**宿主**那份。插件作者以为用的是
 * 自己打的版本，实际不是；等到某个 `is` / `===` 判断莫名其妙失败时，现场离原因已经很远。
 *
 * PF4J 里对应的机制叫 checkDifferentClassLoaders：把同一个类分别用两个 loader 加载再比。
 * 在 Android 上更直接——看一眼 dex 里到底有没有这些类名就够了，而且不必真的把插件装进来。
 *
 * 只读、只看类名，不加载、不初始化任何插件代码。
 */
internal object SharedClassGuard {

    /** 一处冲突：容器里的 [className] 落在宿主共享前缀 [prefix] 下。 */
    data class Conflict(val className: String, val prefix: String)

    /**
     * 扫一遍容器的 dex，返回所有落在共享前缀下的类。
     *
     * 读不出来（不是合法 dex、只有 resources、DexFile 打不开）时返回空：
     * 这一层的职责是"发现了就拦"，不是"读不出来就拒绝"——读不出来的容器会在
     * 后面的 loader 里以它自己的方式失败，那里的报错更准确。
     */
    fun scan(file: File, shared: Set<String> = HostPackages.shared): List<Conflict> {
        if (!file.isFile) return emptyList()
        val conflicts = mutableListOf<Conflict>()
        runCatching {
            // DexFile 在 Android 上没有实现 Closeable，只能手动关。
            val dex = DexFile(file)
            try {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    // 数组类型会以 "[" 开头，没有包语义。
                    if (name.startsWith("[")) continue
                    val prefix = shared.firstOrNull { name.startsWith(it) } ?: continue
                    conflicts += Conflict(name, prefix)
                }
            } finally {
                runCatching { dex.close() }
            }
        }
        return conflicts
    }

    /** 冲突 → 一句能放在卡片上的说明。 */
    fun message(conflicts: List<Conflict>): String {
        val first = conflicts.first()
        val more = if (conflicts.size > 1) " 等 ${conflicts.size} 个" else ""
        return "容器里打包了宿主已提供的类：${first.className}$more（${first.prefix}）"
    }
}
