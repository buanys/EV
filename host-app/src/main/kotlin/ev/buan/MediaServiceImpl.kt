package ev.buan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.HostPermission
import org.cordis.api.MediaItem
import org.cordis.api.MediaKind
import org.cordis.api.MediaService
import org.cordis.api.PermissionService

/**
 * `MediaStore` 的实现：宿主侧的媒体库读取。
 *
 * 三件必须做对的事：
 *
 *  1. **按类目查不同的集合**。`Images` / `Video` / `Audio` 是不同的表，
 *     投影也不同（音频没有宽高，视频有时长）。用同一套投影会抛列不存在。
 *  2. **给 `content://` 而不是文件路径**。Android 10 之后 `_data` 已经不可靠，
 *     拿到 uri 才能交给图片加载器或分享流程。
 *  3. **`DATE_ADDED` 是秒**。直接当毫秒用会把时间算到 1970 年，模型据此会答"这是很久以前的图"。
 *
 * 只读是硬边界：删除在 Android 11+ 要走 `createDeleteRequest` 并再确认一次，
 * 那是独立的交互流程，不塞进"读相册"的接口里。
 */
internal class MediaServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : MediaService {

    private val resolver get() = context.contentResolver

    private fun require() {
        if (permission != null && !permission.isGranted(HostPermission.MEDIA)) {
            throw SecurityException("缺少权限 MEDIA（在插件管理页授予后重试）")
        }
    }

    override suspend fun recent(kind: MediaKind, limit: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            require()
            query(kind, selection = null, args = null, limit = limit)
        }

    override suspend fun search(query: String, kind: MediaKind?, limit: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            require()
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%${query.trim()}%")
            val kinds = if (kind == null) MediaKind.entries else listOf(kind)
            kinds
                .flatMap { query(it, selection, args, limit) }
                .sortedByDescending { it.addedAt }
                .take(limit.coerceIn(1, 200))
        }

    private fun query(
        kind: MediaKind,
        selection: String?,
        args: Array<String>?,
        limit: Int,
    ): List<MediaItem> {
        val collection = collectionOf(kind)
        val projection = projectionOf(kind)
        val capped = limit.coerceIn(1, 200)

        return resolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idAt = 0
            val nameAt = 1
            val mimeAt = 2
            val sizeAt = 3
            val addedAt = 4
            val widthAt = if (kind != MediaKind.AUDIO) 5 else -1
            val heightAt = if (kind != MediaKind.AUDIO) 6 else -1
            val durationAt = when (kind) {
                MediaKind.VIDEO -> 7
                MediaKind.AUDIO -> 5
                MediaKind.IMAGE -> -1
            }

            buildList {
                while (cursor.moveToNext() && size < capped) {
                    val id = cursor.getLong(idAt)
                    add(
                        MediaItem(
                            id = id,
                            name = cursor.getString(nameAt).orEmpty(),
                            mimeType = cursor.getString(mimeAt).orEmpty(),
                            sizeBytes = cursor.getLong(sizeAt),
                            // DATE_ADDED 是秒；换算成毫秒，否则时间会落在 1970 年。
                            addedAt = cursor.getLong(addedAt) * 1000L,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            width = widthAt.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                            height = heightAt.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                            durationMs = durationAt.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun collectionOf(kind: MediaKind): Uri = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private fun projectionOf(kind: MediaKind): Array<String> {
        val common = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        return when (kind) {
            MediaKind.IMAGE -> common + arrayOf(
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
            )
            MediaKind.VIDEO -> common + arrayOf(
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DURATION,
            )
            MediaKind.AUDIO -> common + arrayOf(MediaStore.MediaColumns.DURATION)
        }
    }
}
