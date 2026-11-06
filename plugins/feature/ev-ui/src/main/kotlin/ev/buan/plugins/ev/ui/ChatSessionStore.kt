package ev.buan.plugins.ev.ui

import android.util.Log
import org.cordis.api.PluginStorage

/**
 * The chat history file, kept in this plugin's own [PluginStorage] area.
 *
 * Reads happen once per chat surface; writes replace the whole document with a snapshot of the
 * in-memory list (see [ChatSessionDocuments] for the shape). [PluginStorage.atomicWrite] is used so a
 * crash mid-write leaves the previous history intact rather than a truncated file.
 *
 * Neither direction throws. A missing or unreadable document loads as an empty history, and a failed
 * write - quota, revoked grant, damaged file system - is logged and dropped: losing a chat edit is
 * annoying, crashing the host over it is worse. Disabling the plugin keeps the file; uninstalling
 * removes it with the rest of the plugin's area, so this code has nothing to clean up.
 */
internal class ChatSessionStore(
    private val storage: PluginStorage,
    /** Injected only by tests: on device the plugin logs through [Log]. */
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    },
) {
    /** The stored conversations, or an empty list when there are none or they cannot be read. */
    fun load(): List<ChatSession> {
        val bytes = try {
            storage.read(ChatSessionDocuments.PATH)
        } catch (error: Exception) {
            log("读取 ${ChatSessionDocuments.PATH} 失败，按空会话启动", error)
            return emptyList()
        } ?: return emptyList()
        return ChatSessionDocuments.decode(bytes) ?: run {
            log("${ChatSessionDocuments.PATH} 无法解析，按空会话启动", null)
            emptyList()
        }
    }

    /** Replaces the document with [sessions]; a failure is logged and loses only this snapshot. */
    fun save(sessions: List<ChatSession>) {
        val bytes = try {
            ChatSessionDocuments.encode(sessions)
        } catch (error: Exception) {
            log("会话序列化失败，本次不保存", error)
            return
        }
        try {
            storage.atomicWrite(ChatSessionDocuments.PATH, bytes)
        } catch (error: Exception) {
            log("写入 ${ChatSessionDocuments.PATH} 失败，本次不保存", error)
        }
    }

    private companion object {
        const val TAG = "ev-ui"
    }
}
