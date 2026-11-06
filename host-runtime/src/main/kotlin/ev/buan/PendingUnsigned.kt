package ev.buan

/** An unsigned container the user has not approved (or whose bytes changed). */
data class PendingUnsigned(
    val id: String,
    val version: String,
    val sha256: String,
    val fileName: String,
    /**
     * 为什么还要人确认（由能力面判定给出的一句话）。
     *
     * null 表示没有额外原因，只是"没批准过"。有值时插件页会把这句直接显示出来——
     * 「第一次见到」「未开启信任后续版本」「权限或依赖有变化」是三种完全不同的处境，
     * 用户看不到区别就只能盲签。
     */
    val reason: String? = null,
)
