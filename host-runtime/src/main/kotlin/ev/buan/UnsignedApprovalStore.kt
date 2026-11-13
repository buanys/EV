package ev.buan

import android.content.Context

/**
 * Remembers which unsigned container the user approved, by exact sha256.
 *
 * Stored in app-private SharedPreferences, never next to the plugins: a file in EV/mods is
 * writable by anything with storage access, so approvals kept there would approve themselves.
 * Approval is bound to the payload, so re-signing is not what we trust - these bytes are.
 */
class UnsignedApprovalStore(context: Context) {

    private val preferences = context.getSharedPreferences("ev.unsigned", Context.MODE_PRIVATE)

    /** id -> approved sha256. */
    fun approved(): Map<String, String> =
        preferences.all.mapNotNull { (id, value) -> (value as? String)?.let { id to it } }.toMap()

    fun approve(id: String, sha256: String) {
        preferences.edit().putString(id, sha256).commit()
    }

    fun revoke(id: String) {
        preferences.edit().remove(id).commit()
    }
}
