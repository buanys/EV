package ev.buan.isolation

import android.os.Parcel
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.ToolResult
import ev.buan.plugins.agent.api.ToolSpec

/**
 * 宿主进程 ↔ 插件子进程之间那一层 wire。
 *
 * 手写 `Binder.transact` 而不是 AIDL：整个协议只有三个动作、参数全是字符串和数字，
 * AIDL 会带来一个 codegen 步骤和一份 Java 接口，换来的只是同样的 writeString/readString。
 *
 * **不做 JSON 编解码**。`ToolSpec.parametersJson` 本身就是一段 JSON 文本，把它塞进另一层
 * JSON 只会制造转义问题；Parcel 直接写字段，双方读到的就是原来的字符串。
 */
internal object PluginHostProtocol {

    /** 客户端用来定位服务实现的描述符，仅用于 `enforceInterface` 的一致性检查。 */
    const val DESCRIPTOR = "ev.buan.plugin-host"

    const val LOAD = 1
    const val INVOKE = 2
    const val UNLOAD = 3

    /** 回复的第一个 int：0 成功，1 失败（失败时 payload 是给人看的原因）。 */
    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    fun writeSpecs(destination: Parcel, specs: List<ToolSpec>) {
        destination.writeInt(specs.size)
        specs.forEach { spec ->
            destination.writeString(spec.name)
            destination.writeString(spec.description)
            destination.writeString(spec.parametersJson)
            destination.writeInt(if (spec.dangerous) 1 else 0)
        }
    }

    fun readSpecs(source: Parcel): List<ToolSpec> {
        val count = source.readInt()
        return List(count) {
            ToolSpec(
                name = source.readString().orEmpty(),
                description = source.readString().orEmpty(),
                parametersJson = source.readString().orEmpty(),
                dangerous = source.readInt() == 1,
            )
        }
    }

    fun writeResult(destination: Parcel, result: ToolResult) {
        destination.writeString(result.text)
        destination.writeInt(result.images.size)
        result.images.forEach { image ->
            destination.writeString(image.mimeType)
            destination.writeString(image.base64)
            destination.writeString(image.name.orEmpty())
        }
    }

    fun readResult(source: Parcel): ToolResult {
        val text = source.readString().orEmpty()
        val count = source.readInt()
        val images = List(count) {
            AgentImage(
                mimeType = source.readString().orEmpty(),
                base64 = source.readString().orEmpty(),
                name = source.readString()?.takeIf { it.isNotEmpty() },
            )
        }
        return ToolResult(text = text, images = images)
    }
}
