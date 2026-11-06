package ev.buan.plugins.agent.browse

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * 网页里的 alert / confirm。
 *
 * 不实现这些回调时 WebView 会把请求一直挂着：页面以为对话框还没被回答，
 * 之后的脚本都不执行——用户看到的是"这个站点了没反应"。
 */
internal fun showDialog(
    context: Context,
    message: String,
    positiveLabel: String,
    negativeLabel: String? = null,
    onPositive: () -> Unit,
    onDismiss: () -> Unit,
) {
    var answered = false
    fun answer(positive: Boolean) {
        if (answered) return
        answered = true
        if (positive) onPositive() else onDismiss()
    }
    AlertDialog.Builder(context)
        .setMessage(message)
        .setPositiveButton(positiveLabel) { _, _ -> answer(true) }
        .apply {
            if (negativeLabel != null) setNegativeButton(negativeLabel) { _, _ -> answer(false) }
        }
        .setOnCancelListener { answer(false) }
        .show()
}

/** `prompt()`：带一个输入框。取消和关闭都算"没输入过"。 */
internal fun showPrompt(
    context: Context,
    message: String,
    defaultValue: String,
    onResult: (String?) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val inset = (16 * density).toInt()
    val input = EditText(context).apply {
        setText(defaultValue)
        setSelection(text.length)
        setSingleLine()
    }
    val container = FrameLayout(context).apply {
        addView(
            input,
            FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(inset, inset / 2, inset, 0)
            },
        )
    }
    var answered = false
    fun answer(value: String?) {
        if (answered) return
        answered = true
        onResult(value)
    }
    AlertDialog.Builder(context)
        .setMessage(message)
        .setView(container)
        .setPositiveButton("确定") { _, _ -> answer(input.text?.toString().orEmpty()) }
        .setNegativeButton("取消") { _, _ -> answer(null) }
        .setOnCancelListener { answer(null) }
        .show()
}

/** 长按网页里的链接：复制 / 分享。 */
internal fun showLinkMenu(context: Context, url: String, title: String) {
    val items = arrayOf("复制链接", "分享")
    AlertDialog.Builder(context)
        .setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("链接", url))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }

                1 -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title\n$url")
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(send, null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        .setOnCancelListener { }
        .show()
}
