package org.cordis.api

import org.cordis.ServiceKey

/**
 * 一次定位的结果。
 *
 * **两个时间戳，别只用一个。**
 *
 * - [capturedAtEpochMs] 是墙钟：说给人听（"三分钟前"）用它最直观
 * - [ageMs] 由单调时钟算出来：用户改时区、系统对过 NTP 都不会让它变成负数
 *
 * 只信墙钟的差，会在时钟被校正的那一刻得出荒唐的年龄。
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度半径，米。68% 概率真值落在此半径内。 */
    val accuracyMeters: Float,
    val capturedAtEpochMs: Long,
    /** 拿到这条结果时它已经过期多久。 */
    val ageMs: Long,
    /** gps / network / passive */
    val provider: String,
    /** 模拟定位。为 true 时这条坐标什么都不能证明。 */
    val isMock: Boolean = false,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
)

/**
 * 定位。
 *
 * **只有一个方法**，因为"读缓存"和"主动定位"不是两个功能，而是同一条轴的两端：
 * 愿不愿意花电。拆成 `lastKnown()` + `current()` 会把选择甩给调用方，
 * 而调用方在调用前根本不知道缓存有多旧——只能先问一次、看一眼、再决定，
 * 多一次往返、多一个选错的可能。所以 [timeoutMs] 才是那个开关：`0` 表示只读缓存。
 *
 * 返回语义：
 *
 * - 拿不到任何东西 → null
 * - 拿得到 → 永远带真实的 [LocationFix.ageMs] 与 [LocationFix.accuracyMeters]，
 *   **宿主不隐瞒**。一条过期 47 分钟的坐标仍然有用（"你最后已知位置在国贸"），
 *   但调用方必须看得见它过期了。
 *
 * 合并规则见 `host-core` 的 `LocationPicker`：精度是硬门槛，新鲜度是门槛内的偏好。
 */
interface LocationService {

    /**
     * 要一个位置。
     *
     * @param maxAgeMs 多旧以内可以直接用缓存。[timeoutMs] 为 0 时，超过它的缓存也算数，
     *   只是 [LocationFix.ageMs] 会如实反映。
     * @param maxAccuracyMeters 精度门槛，米。
     * @param timeoutMs 主动定位最多等多久；`0` 表示只读缓存、不唤醒无线电。
     */
    suspend fun locate(
        maxAgeMs: Long = 120_000,
        maxAccuracyMeters: Float = 100f,
        timeoutMs: Long = 8_000,
    ): LocationFix?

    companion object {
        val Key = ServiceKey<LocationService>("host/location")
    }
}
