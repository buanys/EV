package ev.buan.plugins.eta.ui

import android.util.Log
import org.cordis.api.PluginStorage

/**
 * The skills library file, kept in this plugin's own [PluginStorage] area.
 *
 * Mirrors [CharacterStore] and [ChatSessionStore]: one whole-document snapshot per write through
 * [PluginStorage.atomicWrite], no exceptions in either direction. A missing or unreadable
 * `skills.json` loads as an empty library (the panel then shows its "no skills yet" state), and a
 * failed write is logged and dropped - a skill is worth keeping if possible, never worth crashing the
 * host for.
 */
internal class SkillStore(
    private val storage: PluginStorage,
    /** Injected only by tests: on device the plugin logs through [Log]. */
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    },
) {
    /** The stored skills in file order, or an empty library when there are none to read. */
    fun load(): List<EtaSkill> {
        val bytes = try {
            storage.read(SkillDocuments.PATH)
        } catch (error: Exception) {
            log("读取 ${SkillDocuments.PATH} 失败，按空 Skills 库启动", error)
            return emptyList()
        } ?: return emptyList()
        return SkillDocuments.decode(bytes) ?: run {
            log("${SkillDocuments.PATH} 无法解析，按空 Skills 库启动", null)
            emptyList()
        }
    }

    /** Replaces the document with [skills]; a failure is logged and loses only this snapshot. */
    fun save(skills: List<EtaSkill>) {
        val bytes = try {
            SkillDocuments.encode(skills)
        } catch (error: Exception) {
            log("Skills 序列化失败，本次不保存", error)
            return
        }
        try {
            storage.atomicWrite(SkillDocuments.PATH, bytes)
        } catch (error: Exception) {
            log("写入 ${SkillDocuments.PATH} 失败，本次不保存", error)
        }
    }

    private companion object {
        const val TAG = "eta-ui"
    }
}
