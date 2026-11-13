package org.cordis.api

import org.cordis.ServiceKey

/** 媒体库的三类内容。要哪一类由调用方说清，宿主不去猜"最近的媒体"该是图还是歌。 */
enum class MediaKind { IMAGE, VIDEO, AUDIO }

/**
 * 媒体库里的一项。
 *
 * [uri] 是可以直接交给图片加载器或发送流程的 `content://` 地址——
 * 有了它，插件不必知道文件系统路径（Android 10 之后那条路本来也不通了）。
 */
data class MediaItem(
    val id: Long,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** 入库时间，毫秒。 */
    val addedAt: Long,
    val uri: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
)

/**
 * 读取媒体库（相册 / 视频 / 音频）。**只读**。
 *
 * 声明 `MEDIA` 才拿得到绑定；未授权时每次调用抛 `SecurityException`。
 *
 * 不带写入与删除：`MediaStore` 的删除在 Android 11+ 要走 `createDeleteRequest`，
 * 用户还得再确认一次，那是一条独立的交互流程，不该塞进"读相册"的接口里。
 */
interface MediaService {

    /** 最近入库的 [limit] 项。 */
    suspend fun recent(kind: MediaKind = MediaKind.IMAGE, limit: Int = 20): List<MediaItem>

    /** 文件名模糊匹配 [query]；[kind] 为 null 时三类都查。 */
    suspend fun search(query: String, kind: MediaKind? = null, limit: Int = 20): List<MediaItem>

    companion object {
        val Key = ServiceKey<MediaService>("host/media")
    }
}
