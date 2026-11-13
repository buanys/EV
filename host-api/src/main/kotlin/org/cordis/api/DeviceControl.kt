package org.cordis.api

import org.cordis.ServiceKey

/**
 * 设备控制：开关、显示、声音、勿扰、手电筒、媒体键。
 *
 * 为什么在宿主：这些开关一半挂在 `Context` 上（`AudioManager`、`NotificationManager`、
 * `KeyguardManager`），另一半在第三方应用里**根本改不动**（`WifiManager.setWifiEnabled`
 * 在 API 29+ 被限权、飞行模式要 `WRITE_SECURE_SETTINGS`、移动数据要特权）。所以统一走
 * `svc` / `settings` 的 root 通道，读仍然用公开 API。
 *
 * 与 [DeviceContextService] 的分工：那个回答"这台设备上有什么、我现在在哪"，只读；
 * 这个回答"把某个开关拨到某个位置"，每个方法都可能改变用户看得见的状态，因此单独一个契约、
 * 单独一个 `DEVICE_CONTROL` 声明位。
 */
interface DeviceControlService {

    /** 读一个开关的当前状态。 */
    suspend fun state(target: DeviceSwitch): SwitchState

    /** 拨一个开关；返回**改完之后**的真实状态（不是"命令发出去了"）。 */
    suspend fun setState(target: DeviceSwitch, enabled: Boolean): SwitchState

    suspend fun display(): DisplayState

    /** 0–100 的亮度百分比；同时关掉自动亮度，否则系统会立刻覆盖回去。 */
    suspend fun setBrightness(percent: Int)

    suspend fun setScreenTimeout(seconds: Int)

    suspend fun sound(): SoundState

    suspend fun setVolume(stream: AudioStream, percent: Int)

    suspend fun setRingMode(mode: RingMode)

    suspend fun setDoNotDisturb(mode: DndMode)

    /** 手电筒是否亮着（可能被相机占用）。 */
    suspend fun flashlight(): Boolean

    suspend fun setFlashlight(enabled: Boolean)

    /** 媒体键：等价于插一副耳机按一下，走当前媒体会话。 */
    suspend fun media(action: MediaAction)

    /** 锁屏、勿扰、音频输出、外接显示这一组"环境"读数。 */
    suspend fun environment(): DeviceEnvironment

    companion object {
        val Key = ServiceKey<DeviceControlService>("host/device-control")
    }
}

/** 可以被拨动的开关。名字即 wire 名，插件与模型都用它。 */
enum class DeviceSwitch {
    WIFI,
    BLUETOOTH,
    MOBILE_DATA,
    AIRPLANE,
    LOCATION,
    NFC,
    BATTERY_SAVER,
    AUTO_ROTATE,
    DARK_MODE,
}

/**
 * 一次开关读数。
 *
 * [enabled] 为 null 表示**这台设备读不出来**（例如 ROM 没有这个开关），[detail] 说明原因。
 * 不把"读不到"当成 false：那会让模型以为灯是关的，然后去开一个不存在的开关。
 */
data class SwitchState(
    val target: DeviceSwitch,
    val enabled: Boolean?,
    val detail: String? = null,
)

data class DisplayState(
    /** 0–100 的百分比；读不到时 -1。 */
    val brightnessPercent: Int,
    val autoBrightness: Boolean,
    val screenTimeoutSeconds: Int,
    /** 自动旋转是否开启；有些 ROM 读不到就是 null。 */
    val autoRotate: Boolean? = null,
)

enum class AudioStream {
    MEDIA,
    ALARM,
    RING,
    NOTIFICATION,
    VOICE_CALL,
    SYSTEM,
}

data class SoundState(
    /** 每一路音量，百分比。 */
    val volumes: Map<AudioStream, Int>,
    val ringMode: RingMode,
    val doNotDisturb: DndMode,
)

enum class RingMode {
    NORMAL,
    VIBRATE,
    SILENT,
}

/** 系统的 `zen_mode`：0/1/2/3 分别对应关、优先、仅闹钟、静默。 */
enum class DndMode(val zen: Int) {
    OFF(0),
    PRIORITY(1),
    ALARMS(2),
    SILENT(3),
    ;

    companion object {
        fun ofZen(value: Int): DndMode = entries.firstOrNull { it.zen == value } ?: OFF
    }
}

enum class MediaAction {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    STOP,
}

/** 一组环境读数：判断"用户现在能不能被打扰"用得上。 */
data class DeviceEnvironment(
    val locked: Boolean,
    /** 锁屏有密码/图案，而不是滑动解锁。 */
    val secureLock: Boolean,
    /** 有没有有线耳机或蓝牙音频设备接着。 */
    val headphones: Boolean,
    /** 有没有外接显示器。 */
    val externalDisplay: Boolean,
    val doNotDisturb: DndMode,
)
