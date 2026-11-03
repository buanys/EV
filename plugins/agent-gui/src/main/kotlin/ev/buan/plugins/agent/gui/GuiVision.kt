package ev.buan.plugins.agent.gui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolResult
import ev.buan.plugins.agent.api.ToolSpec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Looking at the screen, and looking at pictures.
 *
 * `screen_dump` reads the accessibility tree, which is text a model can reason about but is empty
 * on anything canvas-drawn: games, maps, photo editors, custom-rendered chat bubbles. A screenshot
 * plus a multimodal model covers exactly those. The two steps are separate tools on purpose -
 * capturing is a device action (privacy, needs approval), reading is just encoding - so the model
 * can decide to look, and a user can see that it looked.
 *
 * The image never becomes a file the model reads by path: [GuiReadImageTool] decodes, downscales and
 * base64-encodes it into [ToolResult.images], and the brain carries it into the next request.
 */
internal object GuiVision {

    /** Long edge after downscale. Enough for UI text, cheap enough for a per-turn image. */
    private const val MAX_EDGE = 1280

    private const val JPEG_QUALITY = 85

    /** Source cap before decoding: a 100 MB RAW would OOM the host. */
    private const val MAX_SOURCE_BYTES = 12L * 1024 * 1024

    /** 共享存储根：截屏落在这里，`screencap` 由 root 执行之后再放开读权限。 */
    private fun root(): File = android.os.Environment.getExternalStorageDirectory() ?: File("/sdcard")

    fun all(): List<Tool> = listOf(GuiCaptureTool(root()), GuiReadImageTool())

    /**
     * `screencap` through root, because MediaProjection needs a consent dialog the agent cannot
     * answer mid-turn. No approval gate: the screenshot is how the agent sees a canvas-drawn
     * screen, and the HUD already tells the user it is working. See GuiTools for why this group
     * is unguarded.
     */
    private class GuiCaptureTool(private val root: File) : Tool {
        override val spec = ToolSpec(
            name = "screen_capture",
            description = "截取当前屏幕保存为图片，返回文件路径。要看到画面内容，截完必须再调用 " +
                "read_image 读取这个路径；只看文字用 screen_dump 更省。需要 root。",
            parametersJson = """
                {"type":"object","properties":{},"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String {
            val directory = File(root, "EV/shots").apply { mkdirs() }
            val file = File(directory, "screen-${System.currentTimeMillis()}.png")
            val quoted = "'${file.absolutePath}'"
            val capture = runCommand("screencap -p $quoted")
            if (!file.isFile || file.length() == 0L) {
                return JSONObject()
                    .put("error", "截屏失败：${capture.ifBlank { "screencap 没有产出文件" }}")
                    .toString()
            }
            // screencap runs as root; without this the app cannot open its own screenshot.
            runCommand("chmod 644 $quoted")
            return JSONObject()
                .put("path", file.absolutePath)
                .put("bytes", file.length())
                .toString()
        }

        private fun runCommand(command: String): String = runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        }.getOrElse { error -> error.message ?: error::class.simpleName.orEmpty() }
    }

    /**
     * Reads a picture and hands it to the model.
     *
     * Downscaling is not an optimisation, it is the difference between one image per turn and a
     * context window eaten by a 4 MB PNG. JPEG at 1280 is legible for UI text and costs ~150 KB.
     */
    private class GuiReadImageTool : Tool {
        override val spec = ToolSpec(
            name = "read_image",
            description = "读取一张图片并把画面直接交给模型查看。用于刚截的屏（screen_capture 的路径）" +
                "或用户指定的图片。大图会缩到长边 $MAX_EDGE 的 JPEG。",
            parametersJson = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"图片的绝对路径，例如 /sdcard/EV/shots/screen-123.png"}
                },"required":["path"],"additionalProperties":false}
            """.trimIndent(),
        )

        override suspend fun invoke(argumentsJson: String): String = read(argumentsJson).text

        override suspend fun invokeResult(argumentsJson: String): ToolResult = read(argumentsJson)

        private fun read(argumentsJson: String): ToolResult {
            val path = runCatching {
                JSONObject(argumentsJson.ifBlank { "{}" }).optString("path", "").trim()
            }.getOrDefault("")
            if (path.isEmpty()) return ToolResult("缺少 path 参数")
            val file = File(path)
            if (!file.isFile) return ToolResult("文件不存在：$path")
            if (file.length() > MAX_SOURCE_BYTES) {
                return ToolResult("图片过大：${file.length()} 字节（上限 $MAX_SOURCE_BYTES）")
            }

            val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                ?: return ToolResult("不是可解码的图片：$path")
            val scaled = scaleToLongEdge(decoded, MAX_EDGE)
            val bytes = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()

            val image = AgentImage(
                mimeType = "image/jpeg",
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                name = file.name,
            )
            return ToolResult(
                text = JSONObject()
                    .put("path", path)
                    .put("mime", "image/jpeg")
                    .put("bytes", bytes.size)
                    .put("image", true)
                    .toString(),
                images = listOf(image),
            )
        }

        private fun scaleToLongEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            if (longEdge <= maxEdge) return bitmap
            val ratio = maxEdge.toFloat() / longEdge
            val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
}
