package ev.buan.plugins.agent.browse

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap

/**
 * 下载：交给系统的 DownloadManager。
 *
 * 用 DownloadManager 而不是自己读流，是因为它负责断点、通知和"下载完打开"，
 * 而这些用一次 WebView 会话的 App 自己重写一遍没有意义。
 * 拿不到文件名时退回到 URL 的最后一段。
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
