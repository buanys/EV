package ev.buan.plugins.eta.ui

import android.util.Log
import org.cordis.api.PluginStorage

/**
 * The character library file, kept in this plugin's own [PluginStorage] area.
 *
 * Mirrors [ChatSessionStore]: one whole-document snapshot per write through
 * [PluginStorage.atomicWrite], no exceptions in either direction. A missing or unreadable
 * `characters.json` loads as an empty library (the panel then shows its "no characters yet" state),
 * and a failed write is logged and dropped - a character is worth keeping if possible, never worth
 * crashing the host for.
 */
internal class CharacterStore(
    private val storage: PluginStorage,
    /** Injected only by tests: on device the plugin logs through [Log]. */
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    },
) {
    /** The stored characters in file order, or an empty library when there are none to read. */
    fun load(): List<EtaCharacter> {
        val bytes = try {
            storage.read(CharacterDocuments.PATH)
        } catch (error: Exception) {
            log("读取 ${CharacterDocuments.PATH} 失败，按空角色库启动", error)
            return emptyList()
        } ?: return emptyList()
        return CharacterDocuments.decode(bytes) ?: run {
            log("${CharacterDocuments.PATH} 无法解析，按空角色库启动", null)
            emptyList()
        }
    }

    /** Replaces the document with [characters]; a failure is logged and loses only this snapshot. */
    fun save(characters: List<EtaCharacter>) {
        val bytes = try {
            CharacterDocuments.encode(characters)
        } catch (error: Exception) {
            log("角色序列化失败，本次不保存", error)
            return
        }
        try {
            storage.atomicWrite(CharacterDocuments.PATH, bytes)
        } catch (error: Exception) {
            log("写入 ${CharacterDocuments.PATH} 失败，本次不保存", error)
        }
    }

    private companion object {
        const val TAG = "eta-ui"
    }
}
