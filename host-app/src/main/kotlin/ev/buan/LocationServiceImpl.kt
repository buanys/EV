package ev.buan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import ev.buan.hostcore.personal.LocationPicker
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.cordis.api.HostPermission
import org.cordis.api.LocationFix
import org.cordis.api.LocationService
import org.cordis.api.PermissionService

/**
 * `LocationManager` 的实现：一次调用拿到"最适合当前问题"的一个位置。
 *
 * 三件必须做对的事：
 *
 *  1. **先读缓存再决定要不要花电。** 缓存够用就立刻返回，绝不为了"更新一点"唤醒 GPS。
 *     这一步的判断交给 `LocationPicker.satisfies`，规则与合并同一处，不会各写一套。
 *  2. **主动定位必须真能取消。** 超时后 `CancellationSignal.cancel()`，否则请求挂在
 *     系统里持续耗电，而调用方以为它已经结束了。
 *  3. **权限降级如实反映。** 用户只给了模糊定位时不去请求 GPS（会抛），
 *     走 network，精度自然大——把大精度如实报出去，而不是假装失败或假装很准。
 *
 * 合并规则（精度是硬门槛、新鲜度是门槛内的偏好）在 `host-core` 的 `LocationPicker` 里，
 * 那边有单测覆盖"新的不一定更好"这一条。
 */
internal class LocationServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : LocationService {

    private val manager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun requirePermission() {
        if (permission != null && !permission.isGranted(HostPermission.LOCATION)) {
            throw SecurityException("缺少权限 LOCATION（在插件管理页授予后重试）")
        }
    }

    override suspend fun locate(
        maxAgeMs: Long,
        maxAccuracyMeters: Float,
        timeoutMs: Long,
    ): LocationFix? {
        requirePermission()
        val lm = manager ?: return null

        val cached = cachedFixes(lm)
        val best = LocationPicker.pick(cached, { it.ageMs }, { it.accuracyMeters }, maxAccuracyMeters)

        // 缓存已经够用：不碰无线电。这是这个服务存在的意义——
        // 大多数"我在哪"的问题，系统十几分钟前那个定位就答得上。
        if (best != null && LocationPicker.satisfies(
                best,
                { it.ageMs },
                { it.accuracyMeters },
                maxAgeMs,
                maxAccuracyMeters,
            )
        ) {
            return best
        }

        // timeoutMs = 0 表示"只读缓存"：即使缓存很旧也照样返回，让调用方自己看 ageMs。
        if (timeoutMs <= 0L) return best

        val fresh = requestOnce(lm, timeoutMs) ?: return best
        // 新拿到的未必更好：室内 network 可能是 2000 米，旧 GPS 是 8 米。
        return LocationPicker.pick(
            cached + fresh,
            { it.ageMs },
            { it.accuracyMeters },
            maxAccuracyMeters,
        ) ?: best
    }

    /** 各 provider 的系统缓存。拿不到的系统会抛，逐项吞掉——一项失败不该毁掉整次定位。 */
    private fun cachedFixes(lm: LocationManager): List<LocationFix> {
        val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        return providers.mapNotNull { provider ->
            @Suppress("MissingPermission")
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.map { toFix(it) }
    }

    private suspend fun requestOnce(lm: LocationManager, timeoutMs: Long): LocationFix? {
        val provider = chooseProvider(lm) ?: return null
        val location = withTimeoutOrNull(timeoutMs.coerceIn(1L, 30_000L)) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                // 协程被取消（超时是其中一种）时，必须把系统请求一起撤掉。
                continuation.invokeOnCancellation { runCatching { signal.cancel() } }
                try {
                    @Suppress("MissingPermission")
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { fix ->
                        if (continuation.isActive) continuation.resume(fix)
                    }
                } catch (error: Throwable) {
                    // 系统拒绝（权限被撤、provider 被关）时如实返回 null，由上层回落缓存。
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return location?.let { toFix(it) }
    }

    /**
     * 选 provider，并跟着权限降级。
     *
     * 只给了模糊定位时不能请求 `gps`——系统会直接抛。走 `network` 虽然不是最准，
     * 但那是用户实际授予的范围。
     */
    private fun chooseProvider(lm: LocationManager): String? {
        val enabled = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val preferred = if (fine) listOf("gps", "network", "passive") else listOf("network", "passive")
        return preferred.firstOrNull { it in enabled }
    }

    private fun toFix(location: Location): LocationFix {
        // 单调时钟优先：墙钟被对过时间之后，两次墙钟相减会得出负数或荒唐值。
        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
                .coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        }
        return LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            // 没有精度时给最大值，让它在合并里自然排在最后，而不是伪装成很准。
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            capturedAtEpochMs = location.time,
            ageMs = ageMs,
            provider = location.provider ?: "unknown",
            isMock = location.isMock,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
        )
    }
}
