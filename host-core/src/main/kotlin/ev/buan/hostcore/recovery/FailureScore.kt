package ev.buan.hostcore.recovery

/**
 * 一个插件的失败计数：崩溃、启动超时、激活抛异常各记一次。
 *
 * 为什么需要它：现在一次激活失败只写一条 `failures` 消息，下次启动还会再试一遍。
 * 一个必然失败的插件于是每次启动都要付一次 90 秒超时的代价，而且用户看到的是
 * "又失败了"，不是"它已经被停用，因为失败太多次"。
 *
 * 三条规则，都是刻意的：
 *
 *  1. **不衰减到零，只减一**：偶发失败（网络抖动、系统忙）不该让插件永远背分，
 *     但"成功一次就清零"会让一个每两次成功一次就崩的插件永远运行下去。
 *  2. **阈值到达即阻止**：不是"再试一次看看吧"，而是明确停用并要求人介入。
 *  3. **解除必须显式**：只有用户重置才能回到零。自动恢复等于没有这道门。
 *
 * 纯逻辑，不认识 Android 也不认识存储——调用方负责把分数落到磁盘上。
 */
data class FailureScore(val count: Int = 0) {

    /** 再失败一次。 */
    fun afterFailure(): FailureScore = FailureScore((count + 1).coerceAtMost(CEILING))

    /** 成功激活一次：减一分，但不低于零。 */
    fun afterSuccess(): FailureScore = FailureScore((count - 1).coerceAtLeast(0))

    /** 到了阈值：宿主不再尝试激活它，直到用户重置。 */
    val blocked: Boolean get() = count >= LIMIT

    companion object {
        /**
         * 三次。一次可能是偶发，两次可疑，三次就是坏的——再往下试只是在浪费每次启动的
         * 90 秒超时预算，而用户真正需要的是一条"它不工作"的明确结论。
         */
        const val LIMIT = 3

        /** 分数上限：到了 [LIMIT] 就不再涨，免得界面显示"失败 47 次"这种没有意义的数字。 */
        const val CEILING = 10
    }
}

/**
 * 失败计数表的读写接口；实现放在宿主侧（SharedPreferences）。
 *
 * 用接口是因为判断（[FailureScore]）要被单测覆盖，而存储需要 Android。
 */
interface FailureScoreStore {
    fun score(pluginId: String): FailureScore

    fun save(pluginId: String, score: FailureScore)

    fun reset(pluginId: String)
}
