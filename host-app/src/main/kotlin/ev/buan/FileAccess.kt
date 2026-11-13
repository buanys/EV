package ev.buan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * "所有文件访问"权限的两半：查询与跳转。
 *
 * 从被归档的 `HostFallbacks.kt` 里拆出来单独放：它不属于任何一屏的界面，
 * 而是宿主与系统之间的一个事实（EV 的插件放在 `/sdcard/EV/mods`，没有这个权限读不到）。
 */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

fun openAllFilesSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
