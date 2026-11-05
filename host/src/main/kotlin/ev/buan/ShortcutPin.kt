package ev.buan

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon

/**
 * 把插件声明的入口钉到桌面。
 *
 * ## 为什么是 `requestPinShortcut`
 *
 * 这是 Android 唯一允许应用"请求"添加快捷方式的接口，而且**必须由用户确认**——
 * 系统会弹一个对话框。不能静默创建，这是有意的：否则任何应用都能往桌面塞图标。
 *
 * ## 为什么带 NEW_DOCUMENT
 *
 * 快捷方式打开的页面要像一个独立应用：自己的任务栈、独立出现在最近任务里、
 * 按返回直接回桌面而不是回到聊天页。`NEW_DOCUMENT + MULTIPLE_TASK` 正是这个语义。
 *
 * 从宿主内部打开插件页走的是普通 Intent，不带这些 flag，所以返回仍然回宿主。
 */
object ShortcutPin {

    /** @return true 表示系统接受了这次请求（用户仍可能在自己的对话框里取消）。 */
    fun pin(context: Context, pluginId: String, shortcut: ShortcutDeclaration): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        val intent = Intent(context, PluginPageActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra("ev.plugin.id", pluginId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            )

        val info = ShortcutInfo.Builder(context, shortcutId(pluginId, shortcut))
            .setShortLabel(shortcut.label)
            .setLongLabel(shortcut.label)
            .setIcon(letterIcon(shortcut.label))
            .setIntent(intent)
            .build()

        return runCatching { manager.requestPinShortcut(info, null) }.getOrDefault(false)
    }

    /** 系统 id：同一个插件可以有多个入口，用声明 id 区分。 */
    private fun shortcutId(pluginId: String, shortcut: ShortcutDeclaration): String =
        "$pluginId:${shortcut.id}"

    /**
     * 用首字母生成图标。
     *
     * 插件是 dex，没有自己的 drawable；所有快捷方式共用 EV 的图标会让桌面上一排一模一样的
     * 东西，比没有更糟。首字母至少能让人认出是哪一个。
     */
    private fun letterIcon(label: String): Icon {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C1C1E") }
        val radius = size * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, background)

        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EDEDED")
            textSize = size * 0.44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val text = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val baseline = size / 2f + letter.textSize / 3f
        canvas.drawText(text, size / 2f, baseline, letter)

        return Icon.createWithBitmap(bitmap)
    }
}
