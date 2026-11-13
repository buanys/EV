package ev.buan

import android.content.SharedPreferences
import ev.buan.hostcore.recovery.FailureScore
import ev.buan.hostcore.recovery.FailureScoreStore

/**
 * 失败计数存在宿主自己的 prefs 里（`ev.plugins`，与启用开关同一个文件）。
 *
 * 为什么必须持久化：这道门的全部意义是"别在下次启动再试一遍"。
 * 分数如果只活在内存里，进程一重启就归零，等于每次启动都重新给三次机会。
 *
 * 键名带前缀 `failures:`，与 `enabled:` 并列但互不干扰；插件被卸载时由
 * [remove] 一并清掉，免得 prefs 里留一堆再也对不上号的数字。
 */
internal class FailureScoreStoreImpl(
    private val preferences: SharedPreferences,
) : FailureScoreStore {

    override fun score(pluginId: String): FailureScore =
        FailureScore(preferences.getInt(key(pluginId), 0).coerceAtLeast(0))

    override fun save(pluginId: String, score: FailureScore) {
        if (score.count == 0) {
            preferences.edit().remove(key(pluginId)).apply()
        } else {
            preferences.edit().putInt(key(pluginId), score.count).apply()
        }
    }

    override fun reset(pluginId: String) {
        preferences.edit().remove(key(pluginId)).apply()
    }

    /** 插件已卸载：它的失败历史没有意义了。 */
    fun remove(pluginId: String) = reset(pluginId)

    private fun key(pluginId: String): String = "failures:$pluginId"
}
