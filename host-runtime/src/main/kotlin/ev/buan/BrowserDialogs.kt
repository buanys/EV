package ev.buan

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * 引擎自己的三块"网页要人回答"的界面：JS 对话框、长按链接菜单、下载。
 *
 * 为什么它们不归插件界面：这三件事都不需要 Compose，也不该因为界面没打开而失效——
 * agent 在一个后台窗口里点了一个 `confirm()`，那一页必须被回答，否则它之后的脚本全停。
 * 界面能给的只有"选文件、要权限、全屏"，那三件在 [org.cordis.api.BrowserUi]。
 */

/**
 * 网页里的 alert / confirm。
 *
 * 不实现这些回调时 WebView 会把请求一直挂着：页面以为对话框还没被回答，
 * 之后的脚本都不执行——用户/agent 看到的是"这个站点了没反应"。
 */
internal fun showWebDialog(
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
    runCatching {
        AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> answer(true) }
            .apply {
                if (negativeLabel != null) setNegativeButton(negativeLabel) { _, _ -> answer(false) }
            }
            .setOnCancelListener { answer(false) }
            .show()
    }.onFailure { answer(false) }
}

/** `prompt()`：带一个输入框。取消和关闭都算"没输入过"。 */
internal fun showWebPrompt(
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
    runCatching {
        AlertDialog.Builder(context)
            .setMessage(message)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> answer(input.text?.toString().orEmpty()) }
            .setNegativeButton("取消") { _, _ -> answer(null) }
            .setOnCancelListener { answer(null) }
            .show()
    }.onFailure { answer(null) }
}

/** 长按网页里的链接：复制 / 分享。 */
internal fun showLinkMenu(context: Context, url: String, title: String) {
    val items = arrayOf("复制链接", "分享")
    runCatching {
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
            .show()
    }
}

/**
 * 下载：交给系统的 DownloadManager。
 *
 * 用 DownloadManager 而不是自己读流，是因为它负责断点、通知和"下载完打开"，
 * 而这些用一次 WebView 会话的 App 自己重写一遍没有意义。
 */
internal fun enqueueDownload(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
) {
    val request = runCatching {
        DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileNameFor(url, contentDisposition, mimeType),
            )
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        }
    }.getOrNull() ?: return
    runCatching {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}

/**
 * `attachment; filename="x.pdf"` → `x.pdf`；否则取 URL 最后一段。
 *
 * 两处都拿不到扩展名时按 MIME 补一个：`application/pdf` 存成 `download` 的话，
 * 下载完成后系统不知道用什么打开，用户只看到一个没有图标的文件。
 */
internal fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
    val fromHeader = contentDisposition
        ?.substringAfter("filename=", "")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotBlank() }
    if (fromHeader != null) return fromHeader

    val fromUrl = url.substringAfterLast('/')
        .substringBefore('?')
        .takeIf { it.isNotBlank() && !it.contains(':') }
    if (fromUrl != null && '.' in fromUrl.substringAfterLast('/')) return fromUrl

    val extension = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
    val base = fromUrl ?: "download"
    return if (extension != null && !base.endsWith(".$extension")) "$base.$extension" else base
}
