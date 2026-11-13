package ev.buan

import org.cordis.api.HostPermission
import org.cordis.api.MediaService

/**
 * 按声明提供媒体库读取，与其它 `provide*IfDeclared` 同一套规则。
 *
 * Android 13+ 把图片/视频/音频拆成三个运行时权限，但 `MEDIA` 合成一位：
 * "允许看相册"对用户是一件事，拆成三次弹窗只会让人一路点允许。
 */
internal suspend fun provideMediaIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    media: () -> MediaService,
): Boolean {
    if (HostPermission.MEDIA !in requested) return false
    pluginContext.provide(MediaService.Key, media()).awaitReady()
    return true
}
