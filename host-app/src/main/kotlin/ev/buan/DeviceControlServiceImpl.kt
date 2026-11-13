package ev.buan

import android.app.UiModeManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.AudioStream
import org.cordis.api.DeviceControlService
import org.cordis.api.DeviceEnvironment
import org.cordis.api.DeviceSwitch
import org.cordis.api.DisplayState
import org.cordis.api.DndMode
import org.cordis.api.HostPermission
import org.cordis.api.MediaAction
import org.cordis.api.PermissionService
import org.cordis.api.RingMode
import org.cordis.api.SoundState
import org.cordis.api.SwitchState

/**
 * 设备控制的实现：**读用公开 API，写一律走 root**。
 *
 * 为什么写不硬啃 API：`WifiManager.setWifiEnabled` 在 API 29+ 对第三方应用不再生效、
 * 飞行模式要 `WRITE_SECURE_SETTINGS`、移动数据要特权、深色模式要 `UiModeManager` 的系统权限。
 * 逐个试这些 API 只会得到"返回 true 但什么也没变"——比失败更难查。`svc` / `cmd` / `settings`
 * 是系统自己用的入口，root 下行为与设置界面一致，所以写统一走它们，写完再**读回来**。
 */
internal class DeviceControlServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : DeviceControlService {

    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)

    private fun require() {
        if (permission != null && !permission.isGranted(HostPermission.DEVICE_CONTROL)) {
            throw SecurityException("缺少权限 DEVICE_CONTROL（在插件管理页授予后重试）")
        }
    }

    // ---------------------------------------------------------------- 开关

    override suspend fun state(target: DeviceSwitch): SwitchState = withContext(Dispatchers.IO) {
        require()
        val enabled = readSwitch(target)
        SwitchState(target, enabled, if (enabled == null) "这台设备读不到 ${target.name} 的状态" else null)
    }

    override suspend fun setState(target: DeviceSwitch, enabled: Boolean): SwitchState =
        withContext(Dispatchers.IO) {
            require()
            val command = writeCommand(target, enabled)
                ?: throw IllegalStateException("${target.name} 在这台设备上没有可用的写入通道")
            val result = shell(command)
            if (!result) throw IllegalStateException("修改 ${target.name} 失败：$command 没有成功返回")
            // 读回来再报：`svc` 偶尔返回 0 但开关没动（被策略拦下），只回"成功"会骗人。
            val after = readSwitch(target)
            SwitchState(target, after, if (after == null) "命令已执行，但读不到结果" else null)
        }

    private fun readSwitch(target: DeviceSwitch): Boolean? = runCatching {
        when (target) {
            DeviceSwitch.WIFI -> Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.WIFI_ON,
                0,
            ) == 1

            DeviceSwitch.BLUETOOTH -> Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BLUETOOTH_ON,
                0,
            ) == 1

            DeviceSwitch.MOBILE_DATA -> Settings.Global.getInt(
                context.contentResolver,
                "mobile_data",
                0,
            ) == 1

            DeviceSwitch.AIRPLANE -> Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1

            DeviceSwitch.LOCATION -> context.getSystemService(android.location.LocationManager::class.java)
                ?.isLocationEnabled

            DeviceSwitch.NFC -> android.nfc.NfcAdapter.getDefaultAdapter(context)?.isEnabled

            DeviceSwitch.BATTERY_SAVER -> power?.isPowerSaveMode

            DeviceSwitch.AUTO_ROTATE -> Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1

            DeviceSwitch.DARK_MODE -> context.getSystemService(UiModeManager::class.java)
                ?.nightMode == UiModeManager.MODE_NIGHT_YES
        }
    }.getOrNull()

    /** 每个开关对应的 root 命令；null 表示这条 ROM 上没有对应入口。 */
    private fun writeCommand(target: DeviceSwitch, enabled: Boolean): String? {
        val on = if (enabled) "enable" else "disable"
        return when (target) {
            DeviceSwitch.WIFI -> "svc wifi $on"
            DeviceSwitch.BLUETOOTH -> "svc bluetooth $on"
            DeviceSwitch.MOBILE_DATA -> "svc data $on"
            DeviceSwitch.AIRPLANE -> "cmd connectivity airplane-mode $on"
            DeviceSwitch.LOCATION -> if (Build.VERSION.SDK_INT >= 30) {
                "cmd location set-location-enabled $enabled"
            } else {
                "settings put secure location_mode ${if (enabled) 3 else 0}"
            }

            DeviceSwitch.NFC -> "svc nfc $on"
            DeviceSwitch.BATTERY_SAVER -> "settings put global low_power ${if (enabled) 1 else 0}"
            DeviceSwitch.AUTO_ROTATE ->
                "settings put system accelerometer_rotation ${if (enabled) 1 else 0}"

            DeviceSwitch.DARK_MODE -> "cmd uimode night ${if (enabled) "yes" else "no"}"
        }
    }

    // ---------------------------------------------------------------- 显示

    override suspend fun display(): DisplayState = withContext(Dispatchers.IO) {
        require()
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        DisplayState(
            brightnessPercent = if (raw < 0) -1 else (raw * 100 / 255).coerceIn(0, 100),
            autoBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                0,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            screenTimeoutSeconds = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                0,
            ) / 1000,
            autoRotate = readSwitch(DeviceSwitch.AUTO_ROTATE),
        )
    }

    override suspend fun setBrightness(percent: Int) = withContext(Dispatchers.IO) {
        require()
        val clamped = percent.coerceIn(0, 100)
        // 先关自动亮度：不关的话系统下一次采样就把我们写的值覆盖回去，用户看到"调了没用"。
        shell("settings put system screen_brightness_mode 0")
        if (!shell("settings put system screen_brightness ${clamped * 255 / 100}")) {
            throw IllegalStateException("修改亮度失败")
        }
    }

    override suspend fun setScreenTimeout(seconds: Int) = withContext(Dispatchers.IO) {
        require()
        val ms = seconds.coerceIn(15, 1800) * 1000
        if (!shell("settings put system screen_off_timeout $ms")) {
            throw IllegalStateException("修改息屏时间失败")
        }
    }

    // ---------------------------------------------------------------- 声音

    override suspend fun sound(): SoundState = withContext(Dispatchers.IO) {
        require()
        val volumes = AudioStream.entries.associateWith { stream ->
            val max = audio?.getStreamMaxVolume(stream.toSystem()) ?: 0
            val current = audio?.getStreamVolume(stream.toSystem()) ?: 0
            if (max <= 0) 0 else current * 100 / max
        }
        SoundState(volumes, ringMode(), dndMode())
    }

    override suspend fun setVolume(stream: AudioStream, percent: Int) = withContext(Dispatchers.IO) {
        require()
        val manager = audio ?: throw IllegalStateException("这台设备没有 AudioManager")
        val systemStream = stream.toSystem()
        val max = manager.getStreamMaxVolume(systemStream)
        val target = (percent.coerceIn(0, 100) * max / 100)
        manager.setStreamVolume(systemStream, target, 0)
        // 写完读回：勿扰或耳机策略可能把音量钳住。
        if (manager.getStreamVolume(systemStream) != target) {
            throw IllegalStateException(
                "${stream.name} 没有被改到位（当前 ${manager.getStreamVolume(systemStream)}/$max）",
            )
        }
    }

    override suspend fun setRingMode(mode: RingMode) = withContext(Dispatchers.IO) {
        require()
        val manager = audio ?: throw IllegalStateException("这台设备没有 AudioManager")
        manager.ringerMode = mode.toSystem()
        if (manager.ringerMode != mode.toSystem()) {
            // 勿扰开着时 ringerMode 会被策略接管：这不是失败，是系统在按用户的勿扰设置行事。
            throw IllegalStateException("铃声模式没有生效（当前勿扰 ${dndMode().name}）")
        }
    }

    override suspend fun setDoNotDisturb(mode: DndMode) = withContext(Dispatchers.IO) {
        require()
        // zen_mode 是系统自己存的那一份；root 写它比 setInterruptionFilter 稳（后者要授权且会被界面改回）。
        if (!shell("settings put global zen_mode ${mode.zen}")) {
            throw IllegalStateException("修改勿扰模式失败")
        }
    }

    override suspend fun flashlight(): Boolean = withContext(Dispatchers.IO) {
        require()
        val manager = camera() ?: return@withContext false
        val id = torchCameraId(manager) ?: return@withContext false
        // 系统没有公开的"现在亮着吗"：记我们最近一次设置的值（手电筒只有一个，够用）。
        torchState[manager] == true
    }

    override suspend fun setFlashlight(enabled: Boolean) = withContext(Dispatchers.IO) {
        require()
        val manager = camera() ?: throw IllegalStateException("这台设备没有闪光灯")
        val id = torchCameraId(manager) ?: throw IllegalStateException("这台设备没有可用闪光灯")
        manager.setTorchMode(id, enabled)
        torchState[manager] = enabled
    }

    /** 找一个带闪光灯的摄像头；读不到特性时退回第一个。 */
    private fun torchCameraId(manager: CameraManager): String? = manager.cameraIdList.firstOrNull { id ->
        runCatching {
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    } ?: manager.cameraIdList.firstOrNull()

    private var torchState = java.util.WeakHashMap<CameraManager, Boolean>()

    private fun camera(): CameraManager? = runCatching {
        context.getSystemService(CameraManager::class.java)
    }.getOrNull()

    override suspend fun media(action: MediaAction) = withContext(Dispatchers.IO) {
        require()
        val manager = audio ?: throw IllegalStateException("这台设备没有 AudioManager")
        manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, action.keyCode()))
        manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, action.keyCode()))
    }

    override suspend fun environment(): DeviceEnvironment = withContext(Dispatchers.IO) {
        require()
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val devices = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        DeviceEnvironment(
            locked = keyguard?.isKeyguardLocked == true,
            secureLock = keyguard?.isKeyguardSecure == true,
            headphones = devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            },
            externalDisplay = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.displays?.count { it.displayId != android.view.Display.DEFAULT_DISPLAY } ?: 0 > 0,
            doNotDisturb = dndMode(),
        )
    }

    // ---------------------------------------------------------------- 内部

    private fun ringMode(): RingMode = when (audio?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingMode.VIBRATE
        else -> RingMode.NORMAL
    }

    private fun dndMode(): DndMode = runCatching {
        val filter = NotificationManagerCompat.from(context).currentInterruptionFilter
        when (filter) {
            NotificationManagerCompat.INTERRUPTION_FILTER_NONE -> DndMode.SILENT
            NotificationManagerCompat.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY
            NotificationManagerCompat.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS
            else -> {
                // 上一条拿不到时退回 zen_mode：两者对不上时以设置里那一份为准。
                val zen = Settings.Global.getInt(context.contentResolver, "zen_mode", 0)
                DndMode.ofZen(zen)
            }
        }
    }.getOrDefault(DndMode.OFF)

    private fun RingMode.toSystem(): Int = when (this) {
        RingMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingMode.SILENT -> AudioManager.RINGER_MODE_SILENT
    }

    private fun AudioStream.toSystem(): Int = when (this) {
        AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
    }

    private fun MediaAction.keyCode(): Int = when (this) {
        MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        MediaAction.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
    }

    /** 一条 `su -c` 命令；只用于 ASCII 命令（`svc` / `settings` / `cmd`），不做编码转换。 */
    private fun shell(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            android.util.Log.w("EV", "device-control: exit=$exit cmd=$command out=${output.take(200)}")
        }
        exit == 0
    }.getOrDefault(false)
}
