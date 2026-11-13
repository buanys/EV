package ev.buan.hostcore.personal

/**
 * 多条定位候选里挑一条的规则。
 *
 * 抽成纯函数，是因为这条规则最反直觉：**新的不一定更好**。
 * 室内刚拿到的 network 定位可能是 2000 米精度，而五分钟前的 GPS 定位是 8 米。
 * 按"新的赢"会让调用方得到一个看起来新、实际没用的坐标。
 *
 * 规则：**精度是硬门槛，新鲜度是门槛内的偏好。**
 *
 * 1. 有满足精度门槛的候选 → 取其中**最新**的那条
 * 2. 一条都不满足精度 → 取**精度最好**的那条（一条 8 米的旧定位，
 *    在"我在哪"这个问题上仍比 2000 米的新定位有用）
 *
 * 泛型而不是绑定具体类型：`host-core` 是纯 JVM 模块，不认识 Android 的 `Location`，
 * 也不该为了一个挑选取就依赖 `host-api`。
 */
object LocationPicker {

    fun <T : Any> pick(
        candidates: List<T>,
        ageMs: (T) -> Long,
        accuracyMeters: (T) -> Float,
        maxAccuracyMeters: Float,
    ): T? {
        if (candidates.isEmpty()) return null
        val accurate = candidates.filter { accuracyMeters(it) <= maxAccuracyMeters }
        if (accurate.isNotEmpty()) {
            // 精度达标就看谁更新；ageMs 越小越新。
            return accurate.minByOrNull { ageMs(it) }
        }
        // 都不达标：要相对最能用的那条，精度优先，同样精度时取更新的。
        return candidates.minWithOrNull(
            compareBy<T> { accuracyMeters(it) }.thenBy { ageMs(it) },
        )
    }

    /**
     * 这条是否同时满足两道门槛。
     *
     * 服务层用它决定"要不要花电去请求一次"：已有的就够用，就别碰无线电。
     */
    fun <T : Any> satisfies(
        candidate: T,
        ageMs: (T) -> Long,
        accuracyMeters: (T) -> Float,
        maxAgeMs: Long,
        maxAccuracyMeters: Float,
    ): Boolean = ageMs(candidate) <= maxAgeMs && accuracyMeters(candidate) <= maxAccuracyMeters
}
