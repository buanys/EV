package ev.buan.plugins.agent.device

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.cordis.api.AudioStream
import org.cordis.api.DeviceControlService
import org.cordis.api.DeviceSwitch
import org.cordis.api.DndMode
import org.cordis.api.MediaAction
import org.cordis.api.RingMode
import org.json.JSONObject

/**
 * 设备开关与执行环境：亮度、音量、勿扰、手电筒、媒体键、锁屏状态。
 *
 * 为什么跟着 `agent-device` 而不是自己一个插件：这些工具与"列应用、开关应用"共用一个授权面
 * （都是"动这台设备"），也总是一起启用。一个功能一个插件，插件数会随工具数线性涨——宿主
 * 每个插件都要一次扫描、一个 ClassLoader、一颗 Fiber。
 *
 * 读数与写入的分界写在描述里：模型不该在没把握时先改再看，所以凡是有 `get_*` 的，
 * `set_*` 的描述都会写"先读一次"。
 */
internal object ControlTools {

    private const val MAX_JSON = 4000

    fun all(control: DeviceControlService): List<Tool> = listOf(
        tool(GET_DEVICE_STATE) { args -> getState(control, args) },
        tool(SET_DEVICE_STATE) { args -> setState(control, args) },
        tool(GET_DISPLAY_STATE) { display(control) },
        tool(SET_BRIGHTNESS) { args -> setBrightness(control, args) },
        tool(SET_SCREEN_TIMEOUT) { args -> setScreenTimeout(control, args) },
        tool(GET_SOUND_STATE) { sound(control) },
        tool(SET_VOLUME) { args -> setVolume(control, args) },
        tool(SET_SOUND_MODE) { args -> setSoundMode(control, args) },
        tool(SET_DND) { args -> setDnd(control, args) },
        tool(GET_FLASHLIGHT) { flashlight(control) },
        tool(SET_FLASHLIGHT) { args -> setFlashlight(control, args) },
        tool(MEDIA_CONTROL) { args -> media(control, args) },
        tool(GET_ENVIRONMENT) { environment(control) },
    )

    // ---------------------------------------------------------------- 开关

    private suspend fun getState(control: DeviceControlService, args: JSONObject): String {
        val targets = args.targets()
        // 什么都没给 = 读全部；给了但认不出来 = 参数错（不能悄悄当成"读全部"，模型会以为那个名字存在）。
        val requested = args.has("target") || args.has("targets")
        if (targets.isEmpty() && requested) {
            return invalid("target 只能是 ${names().joinToString("/")}")
        }
        val effective = targets.ifEmpty { DeviceSwitch.entries }
        val out = JSONObject()
        effective.forEach { target ->
            val state = control.state(target)
            out.put(
                target.name,
                JSONObject()
                    .put("enabled", state.enabled ?: JSONObject.NULL)
                    .put("detail", state.detail ?: JSONObject.NULL),
            )
        }
        return out.toString()
    }

    private suspend fun setState(control: DeviceControlService, args: JSONObject): String {
        val raw = args.optString("target").trim().uppercase()
        val target = names().firstOrNull { it == raw } ?: return invalid("target 只能是 ${names().joinToString("/")}")
        if (!args.has("enabled")) return invalid("enabled 必填（true/false）")
        val after = control.setState(DeviceSwitch.valueOf(target), args.getBoolean("enabled"))
        return JSONObject()
            .put("target", target)
            .put("enabled", after.enabled ?: JSONObject.NULL)
            .put("detail", after.detail ?: JSONObject.NULL)
            .toString()
    }

    // ---------------------------------------------------------------- 显示 / 声音

    private suspend fun display(control: DeviceControlService): String {
        val state = control.display()
        return JSONObject()
            .put("brightness_percent", state.brightnessPercent)
            .put("auto_brightness", state.autoBrightness)
            .put("screen_timeout_seconds", state.screenTimeoutSeconds)
            .put("auto_rotate", state.autoRotate ?: JSONObject.NULL)
            .toString()
    }

    private suspend fun setBrightness(control: DeviceControlService, args: JSONObject): String {
        if (!args.has("percent")) return invalid("percent 必填（0-100）")
        control.setBrightness(args.getInt("percent"))
        return display(control)
    }

    private suspend fun setScreenTimeout(control: DeviceControlService, args: JSONObject): String {
        if (!args.has("seconds")) return invalid("seconds 必填（15-1800）")
        control.setScreenTimeout(args.getInt("seconds"))
        return display(control)
    }

    private suspend fun sound(control: DeviceControlService): String {
        val state = control.sound()
        val volumes = JSONObject()
        state.volumes.forEach { (stream, percent) -> volumes.put(stream.name, percent) }
        return JSONObject()
            .put("volumes", volumes)
            .put("ring_mode", state.ringMode.name)
            .put("do_not_disturb", state.doNotDisturb.name)
            .toString()
    }

    private suspend fun setVolume(control: DeviceControlService, args: JSONObject): String {
        val raw = args.optString("stream").trim().uppercase()
        val stream = AudioStream.entries.firstOrNull { it.name == raw }
            ?: return invalid("stream 只能是 ${AudioStream.entries.joinToString("/")}")
        if (!args.has("percent")) return invalid("percent 必填（0-100）")
        control.setVolume(stream, args.getInt("percent"))
        return sound(control)
    }

    private suspend fun setSoundMode(control: DeviceControlService, args: JSONObject): String {
        val raw = args.optString("mode").trim().uppercase()
        val mode = RingMode.entries.firstOrNull { it.name == raw }
            ?: return invalid("mode 只能是 NORMAL/VIBRATE/SILENT")
        control.setRingMode(mode)
        return sound(control)
    }

    private suspend fun setDnd(control: DeviceControlService, args: JSONObject): String {
        val raw = args.optString("mode").trim().uppercase()
        val mode = DndMode.entries.firstOrNull { it.name == raw }
            ?: return invalid("mode 只能是 OFF/PRIORITY/ALARMS/SILENT")
        control.setDoNotDisturb(mode)
        return sound(control)
    }

    // ---------------------------------------------------------------- 手电筒 / 媒体 / 环境

    private suspend fun flashlight(control: DeviceControlService): String =
        JSONObject().put("on", control.flashlight()).toString()

    private suspend fun setFlashlight(control: DeviceControlService, args: JSONObject): String {
        if (!args.has("enabled")) return invalid("enabled 必填（true/false）")
        control.setFlashlight(args.getBoolean("enabled"))
        return flashlight(control)
    }

    private suspend fun media(control: DeviceControlService, args: JSONObject): String {
        val raw = args.optString("action").trim().uppercase()
        val action = MediaAction.entries.firstOrNull { it.name == raw }
            ?: return invalid("action 只能是 ${MediaAction.entries.joinToString("/")}")
        control.media(action)
        return JSONObject().put("sent", action.name).toString()
    }

    private suspend fun environment(control: DeviceControlService): String {
        val env = control.environment()
        return JSONObject()
            .put("locked", env.locked)
            .put("secure_lock", env.secureLock)
            .put("headphones", env.headphones)
            .put("external_display", env.externalDisplay)
            .put("do_not_disturb", env.doNotDisturb.name)
            .toString()
    }

    // ---------------------------------------------------------------- 工具外壳

    private fun tool(spec: ToolSpec, block: suspend (JSONObject) -> String): Tool = object : Tool {
        override val spec = spec
        override suspend fun invoke(argumentsJson: String): String = try {
            block(JSONObject(argumentsJson.ifBlank { "{}" })).take(MAX_JSON)
        } catch (error: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", if (error is SecurityException) "PERMISSION_DENIED" else "FAILED")
                .put("message", error.message ?: error.javaClass.simpleName)
                .toString()
        }
    }

    private fun names(): List<String> = DeviceSwitch.entries.map { it.name }

    private fun JSONObject.targets(): List<DeviceSwitch> {
        val explicit = optString("target").trim().uppercase().takeIf { it.isNotEmpty() }
        if (explicit != null) {
            return DeviceSwitch.entries.filter { it.name == explicit }
        }
        val array = optJSONArray("targets") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val name = array.optString(index).trim().uppercase()
                DeviceSwitch.entries.firstOrNull { it.name == name }?.let { add(it) }
            }
        }
    }

    private fun invalid(reason: String) =
        JSONObject().put("ok", false).put("error", "INVALID_ARGUMENT").put("message", reason).toString()

    // ---------------------------------------------------------------- 规格

    private val TARGET_ARG = """"target":{"type":"string","description":"${DeviceSwitch.entries.joinToString("/") { it.name }}"}}"""

    private val GET_DEVICE_STATE = ToolSpec(
        name = "device_switch_state",
        description = "读一个或全部开关的当前状态（WiFi、蓝牙、移动数据、飞行、定位、NFC、省电、自动旋转、深色）。" +
            "改了开关之后用它确认；读不到时 returned 为 null 并说明原因。",
        parametersJson = """{"type":"object","properties":{""" +
            """"target":{"type":"string","description":"单个开关名，省略则读全部"},""" +
            """"targets":{"type":"array","items":{"type":"string"},"description":"要读的一组开关"}},""" +
            """"additionalProperties":false}""",
    )

    private val SET_DEVICE_STATE = ToolSpec(
        name = "device_switch_set",
        description = "拨一个设备开关，改完读回真实状态。改之前先用 device_switch_state 看一眼——" +
            "拔掉 WiFi 会断掉正在进行的下载。",
        parametersJson = """{"type":"object","properties":{$TARGET_ARG,""" +
            """"enabled":{"type":"boolean"}},"required":["target","enabled"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val GET_DISPLAY_STATE = ToolSpec(
        name = "display_state",
        description = "读屏幕状态：亮度百分比、是否自动亮度、息屏时间、自动旋转。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val SET_BRIGHTNESS = ToolSpec(
        name = "set_brightness",
        description = "设置屏幕亮度百分比并把自动亮度关掉（不关的话系统会立刻覆盖回去）。",
        parametersJson = """{"type":"object","properties":{"percent":{"type":"integer","minimum":0,"maximum":100}},""" +
            """"required":["percent"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SET_SCREEN_TIMEOUT = ToolSpec(
        name = "set_screen_timeout",
        description = "设置无操作后多少秒息屏（15-1800）。",
        parametersJson = """{"type":"object","properties":{"seconds":{"type":"integer","minimum":15,"maximum":1800}},""" +
            """"required":["seconds"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val GET_SOUND_STATE = ToolSpec(
        name = "sound_state",
        description = "读每一路音量（媒体/闹钟/铃声/通知/通话/系统）、铃声模式与勿扰模式。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val SET_VOLUME = ToolSpec(
        name = "set_volume",
        description = "设置某一路音量的百分比，写完读回；勿扰策略可能钳住结果。",
        parametersJson = """{"type":"object","properties":{""" +
            """"stream":{"type":"string","description":"${AudioStream.entries.joinToString("/") { it.name }}"},""" +
            """"percent":{"type":"integer","minimum":0,"maximum":100}},""" +
            """"required":["stream","percent"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SET_SOUND_MODE = ToolSpec(
        name = "set_sound_mode",
        description = "设置铃声模式：NORMAL（响铃）/VIBRATE（振动）/SILENT（静音）。",
        parametersJson = """{"type":"object","properties":{"mode":{"type":"string","description":"NORMAL/VIBRATE/SILENT"}},""" +
            """"required":["mode"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val SET_DND = ToolSpec(
        name = "set_do_not_disturb",
        description = "设置勿扰模式：OFF/PRIORITY（仅优先联系人）/ALARMS（仅闹钟）/SILENT（全部静默）。",
        parametersJson = """{"type":"object","properties":{"mode":{"type":"string","description":"OFF/PRIORITY/ALARMS/SILENT"}},""" +
            """"required":["mode"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val GET_FLASHLIGHT = ToolSpec(
        name = "flashlight_state",
        description = "手电筒是否亮着。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    private val SET_FLASHLIGHT = ToolSpec(
        name = "set_flashlight",
        description = "打开或关闭手电筒。",
        parametersJson = """{"type":"object","properties":{"enabled":{"type":"boolean"}},""" +
            """"required":["enabled"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val MEDIA_CONTROL = ToolSpec(
        name = "media_control",
        description = "给当前媒体会话发一个媒体键：PLAY/PAUSE/PLAY_PAUSE/NEXT/PREVIOUS/STOP。",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","description":"PLAY/PAUSE/PLAY_PAUSE/NEXT/PREVIOUS/STOP"}},""" +
            """"required":["action"],"additionalProperties":false}""",
        dangerous = true,
    )

    private val GET_ENVIRONMENT = ToolSpec(
        name = "device_environment",
        description = "读设备环境：是否锁屏、锁屏是否有密码、是否接着耳机、是否外接显示器、勿扰模式。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )
}
