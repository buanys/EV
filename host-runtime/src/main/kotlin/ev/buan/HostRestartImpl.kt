package ev.buan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context as AndroidContext
import android.content.Intent
import android.os.Process
import org.cordis.api.HostRestart

/**
 * 重启进程：排一个 300ms 后拉起 MainActivity 的闹钟，然后杀掉自己。
 *
 * 为什么不是直接 `killProcess`：杀掉之后没有人负责把 app 拉回来，用户看到的是"闪退"。
 * 用 AlarmManager 排程是 Android 上不需要额外权限的标准做法，先排程再自杀，
 * 下一个进程拿到的就是磁盘上的新字节。
 *
 * 这是给**替换已加载容器**用的：类加载器攥着旧 dex，就地换文件不会换掉已经在跑的类。
 * 首次安装不需要它——[org.cordis.api.PluginInstallService.install] 在当前进程里就能激活。
 */
internal class HostRestartImpl(
    private val context: AndroidContext,
    /** 重启后的落点由 app 层决定；运行时只负责排程与自杀。 */
    private val restartIntent: (AndroidContext) -> Intent,
) : HostRestart {

    override fun request(reason: String): Boolean = runCatching {
        val intent = restartIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val relaunch = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_DELAY_MS, relaunch)
        // 杀掉之前不再返回值给调用方——这个进程马上就不存在了。
        // 排程失败会走到下面的 getOrDefault(false)，调用方据此退化成"提示用户手动重启"。
        Process.killProcess(Process.myPid())
        true
    }.getOrDefault(false)

    private companion object {
        /** 足够让 AlarmManager 收下这次排程，又短到用户感觉不到。 */
        const val RELAUNCH_DELAY_MS = 300L
    }
}
