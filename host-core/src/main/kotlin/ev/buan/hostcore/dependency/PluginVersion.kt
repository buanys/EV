package ev.buan.hostcore.dependency

/**
 * 语义化版本，只认 `major.minor.patch` 三段，缺的段按 0。
 *
 * 为什么自己写而不是引一个库：宿主只用它比较"声明的最低版本"和"实际装着的版本"，
 * 不需要 pre-release、build metadata、范围表达式这些。少一个依赖，内核冻结那条线也更省事。
 *
 * 解析**故意严格**：`1.x`、`latest`、空串都返回 null，由调用方把"版本写错"当成声明错误，
 * 而不是悄悄按 0.0.0 处理——那会让一个拼错的版本号变成"永远满足"。
 */
data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<PluginVersion> {

    override fun compareTo(other: PluginVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * 解析 `1`、`1.2`、`1.2.3`；负数和超过三段的输入视为无效。
         *
         * 容忍缺段是有意的：`2.0` 比 `2.0.0` 更常写，也没歧义。
         */
        fun parse(text: String): PluginVersion? {
            val parts = text.trim().split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { part ->
                part.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            }
            return PluginVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}

/**
 * 一条依赖：一个插件 id，加上可选的**最低版本**。
 *
 * 为什么是最低版本而不是精确匹配：依赖方真正关心的是"对方的 API 至少到这一代"。
 * 精确匹配会让每次依赖方升级都逼着提供方同步改版本，而实际只要能跑就不该拦。
 * "替换"那一条也因此变得可判定——新实现只要版本 ≥ 要求，就可以接替。
 */
data class PluginDependency(
    val id: String,
    val minimumVersion: PluginVersion? = null,
) {
    /** 写回元数据的形状：`id` 或 `id@1.2.0`。 */
    override fun toString(): String =
        if (minimumVersion == null) id else "$id@$minimumVersion"

    companion object {
        /**
         * 解析一条声明。id 与版本用 `@` 分隔，`@` 取**最后一个**——
         * 插件 id 里不会出现 `@`，但这样写能让错误输入退化得更可预测。
         *
         * 版本段存在但解析不出来时返回 null：那是一条写坏的声明，不是"没有版本要求"。
         */
        fun parse(text: String): PluginDependency? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val at = trimmed.lastIndexOf('@')
            if (at < 0) return PluginDependency(trimmed)
            val id = trimmed.substring(0, at).trim()
            val version = trimmed.substring(at + 1).trim()
            if (id.isEmpty() || version.isEmpty()) return null
            return PluginDependency(id, PluginVersion.parse(version) ?: return null)
        }
    }
}

/**
 * 把"声明的依赖"和"这一轮已经接受的插件"对上，回答还缺谁、谁的版本不够。
 *
 * 与 [DependencyResolver] 分工明确：那个只管**顺序**，这个只管**满不满足**。
 * 顺序错了是装不上的问题，版本不够是装上了也用不了的问题——两者的错误信息不该混在一起。
 */
object DependencyCheck {

    /** 一条不满足的依赖。 */
    data class Failure(
        val dependency: PluginDependency,
        /** 实际装着的版本；插件根本没被接受时为 null。 */
        val actualVersion: String?,
        /** true 表示版本不够，false 表示根本没有这个插件。 */
        val versionMismatch: Boolean,
    ) {
        fun message(forPlugin: String): String =
            if (versionMismatch) {
                "$forPlugin 需要 ${dependency.id} ≥ ${dependency.minimumVersion}，实际是 ${actualVersion ?: "未安装"}"
            } else {
                "$forPlugin 依赖 ${dependency.id}，它没有被接受"
            }
    }

    /**
     * [declared] 是某个插件声明的依赖，[accepted] 是这一轮已接受插件的 id → 版本字符串。
     *
     * 顺序按声明顺序返回，调用方拿到第一条就能给出可读消息；全部满足时返回空列表。
     */
    fun check(
        declared: Collection<PluginDependency>,
        accepted: Map<String, String>,
    ): List<Failure> = declared.mapNotNull { dependency ->
        val actual = accepted[dependency.id]
        val required = dependency.minimumVersion
        when {
            actual == null -> Failure(dependency, null, versionMismatch = false)
            required == null -> null
            else -> {
                // 对方版本读不出来（比如 1.0.0-beta）就当作不满足：宁可拒绝一次，
                // 也不要让一个解析不了的版本号静默通过版本门。
                val actualVersion = PluginVersion.parse(actual)
                if (actualVersion != null && actualVersion >= required) null
                else Failure(dependency, actual, versionMismatch = true)
            }
        }
    }
}
