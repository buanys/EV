package ev.buan.hostcore.personal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 合并规则：**精度是硬门槛，新鲜度是门槛内的偏好**。
 *
 * 这一组测试守的是那个最容易写错的判断——"新的不一定更好"。室内刚拿到的
 * network 定位可能是 2000 米，五分钟前的 GPS 是 8 米；按新取胜会给出一个
 * 看起来新、实际没用的坐标。
 */
class LocationPickerTest {

    /** 测试替身：只关心年龄与精度。 */
    private data class Fix(val name: String, val ageMs: Long, val accuracyMeters: Float)

    private fun pick(
        candidates: List<Fix>,
        maxAccuracyMeters: Float = 100f,
    ): Fix? = LocationPicker.pick(candidates, { it.ageMs }, { it.accuracyMeters }, maxAccuracyMeters)

    @Test
    fun `达标候选里取最新的`() {
        val old = Fix("old", ageMs = 50_000, accuracyMeters = 10f)
        val recent = Fix("recent", ageMs = 5_000, accuracyMeters = 40f)
        assertEquals(recent, pick(listOf(old, recent)))
    }

    @Test
    fun `新但精度不达标，输给旧而准的`() {
        val freshIndoor = Fix("fresh-network", ageMs = 1_000, accuracyMeters = 2_000f)
        val oldGps = Fix("old-gps", ageMs = 300_000, accuracyMeters = 8f)
        // 8 米达标 → 走"达标里取最新"，只有 old-gps 达标，所以是它。
        assertEquals(oldGps, pick(listOf(freshIndoor, oldGps)))
    }

    @Test
    fun `全都不达标时取精度最好的`() {
        val coarse = Fix("coarse", ageMs = 1_000, accuracyMeters = 2_000f)
        val mediocre = Fix("mediocre", ageMs = 900_000, accuracyMeters = 300f)
        assertEquals(mediocre, pick(listOf(coarse, mediocre), maxAccuracyMeters = 100f))
    }

    @Test
    fun `都不达标且精度相同，取更新的`() {
        val older = Fix("older", ageMs = 60_000, accuracyMeters = 500f)
        val newer = Fix("newer", ageMs = 1_000, accuracyMeters = 500f)
        assertEquals(newer, pick(listOf(older, newer), maxAccuracyMeters = 100f))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(pick(emptyList()))
    }

    @Test
    fun `单条候选直接返回`() {
        val only = Fix("only", ageMs = 1_000, accuracyMeters = 5f)
        assertEquals(only, pick(listOf(only)))
    }

    @Test
    fun `门槛恰好相等算达标`() {
        val exact = Fix("exact", ageMs = 120_000, accuracyMeters = 100f)
        assertTrue(LocationPicker.satisfies(exact, { it.ageMs }, { it.accuracyMeters }, 120_000, 100f))
    }

    @Test
    fun `过期一秒就不算达标`() {
        val stale = Fix("stale", ageMs = 120_001, accuracyMeters = 10f)
        assertFalse(LocationPicker.satisfies(stale, { it.ageMs }, { it.accuracyMeters }, 120_000, 100f))
    }

    @Test
    fun `精度差一米就不算达标`() {
        val blurry = Fix("blurry", ageMs = 1_000, accuracyMeters = 101f)
        assertFalse(LocationPicker.satisfies(blurry, { it.ageMs }, { it.accuracyMeters }, 120_000, 100f))
    }

    @Test
    fun `模拟定位不参与胜负判断，规则只看年龄与精度`() {
        // isMock 由调用方在结果上如实透出，而不是在这里被过滤掉——
        // 过滤掉会让调用方以为"这里没有定位"，而真相是"有，但是假的"。
        val mock = Fix("mock", ageMs = 1_000, accuracyMeters = 5f)
        val real = Fix("real", ageMs = 90_000, accuracyMeters = 20f)
        assertEquals(mock, pick(listOf(mock, real)))
    }
}
