package ev.buan.hostcore.validation

/**
 * 宿主 API 的一个版本点：`MAJOR.MINOR.PATCH`。
 *
 * 只做比较，不做"兼容性推断"——兼容性由 [HostApiRange] 显式写出来，而不是从版本号猜。
 * 这是刻意的：插件作者能一眼看出自己的容器在哪一环被拒。
 */
data class HostApiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<HostApiVersion> {

    override fun compareTo(other: HostApiVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** 严格三段数字。`1.0`、`v1.0.0`、`1.0.0-rc1` 一律不接受。 */
        fun parse(text: String): HostApiVersion? {
            val parts = text.trim().split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }
            return HostApiVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

/**
 * 插件在 manifest 里声明的宿主 API 兼容范围，例如 `>=1.0.0 <2.0.0`。
 *
 * ## 支持的语法
 *
 * 以空格分隔的**比较符合取**（全部满足才算命中）：
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `>=1.0.0` | 大于等于 |
 * | `>1.0.0` | 大于 |
 * | `<=1.0.0` | 小于等于 |
 * | `<1.0.0` | 小于 |
 * | `=1.0.0` | 精确等于 |
 * | `1.0.0` | 与 `=1.0.0` 同等 |
 *
 * ## 故意不支持的写法
 *
 * `^1.2.3`、`~1.2`、`1.x`、`*`、`||` 都不支持，**解析失败即拒绝**，不静默降级成
 * "看起来通过"。理由：一个假装理解的解析器比一个明确拒绝的解析器危险得多——插件作者
 * 会以为自己声明的范围生效了。
 *
 * 所以 manifest 里写了 `^1.0.0` 的容器会被拒，报 [HostApiRange.Result.Invalid] 并说明原因，
 * 作者改成 `>=1.0.0 <2.0.0` 即可。
 */
sealed interface HostApiRange {

    /** 宿主 API [version] 是否落在本范围内。 */
    fun contains(version: HostApiVersion): Boolean

    /** 原文，用于报错回显。 */
    val text: String

    data class ComparatorSet(
        override val text: String,
        private val comparators: List<Comparator>,
    ) : HostApiRange {

        override fun contains(version: HostApiVersion): Boolean =
            comparators.all { it.test(version) }

        data class Comparator(val operator: Operator, val version: HostApiVersion) {
            fun test(candidate: HostApiVersion): Boolean = when (operator) {
                Operator.GTE -> candidate >= version
                Operator.GT -> candidate > version
                Operator.LTE -> candidate <= version
                Operator.LT -> candidate < version
                Operator.EQ -> candidate == version
            }
        }

        enum class Operator(val symbol: String) {
            GTE(">="),
            GT(">"),
            LTE("<="),
            LT("<"),
            EQ("="),
        }
    }

    sealed interface Result {
        data class Ok(val range: HostApiRange) : Result

        /** 声明无法解析。`reason` 面向插件作者，要能直接照着改。 */
        data class Invalid(val text: String, val reason: String) : Result
    }

    companion object {
        fun parse(text: String): Result {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return Result.Invalid(text, "hostApi 为空；要放行请删掉这个字段，不要写空串")
            }
            val comparators = mutableListOf<ComparatorSet.Comparator>()
            for (token in trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val parsed = parseComparator(token) ?: return Result.Invalid(
                    text,
                    "无法解析 \"$token\"：只支持 >=、>、<=、<、= 加三段版本号，或不带符号的精确版本；" +
                        "不支持 ^、~、x、*、||（见 docs/sdk/versioning.md）",
                )
                comparators += parsed
            }
            if (comparators.isEmpty()) {
                return Result.Invalid(text, "hostApi 里没有任何比较符")
            }
            return Result.Ok(ComparatorSet(text = trimmed, comparators = comparators))
        }

        private fun parseComparator(token: String): ComparatorSet.Comparator? {
            val operator = ComparatorSet.Operator.entries
                .sortedByDescending { it.symbol.length }
                .firstOrNull { token.startsWith(it.symbol) }
            val versionText = if (operator == null) token else token.removePrefix(operator.symbol)
            val version = HostApiVersion.parse(versionText) ?: return null
            return ComparatorSet.Comparator(operator ?: ComparatorSet.Operator.EQ, version)
        }
    }
}
