package ev.buan

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for host unit tests.
 *
 * `PermissionStore` only needs the interface, so the store's encoding (wire names, one set per id,
 * unknown names dropped) is testable on the JVM without Robolectric or a device.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.mapTo(LinkedHashSet()) { it as String } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var cleared = false

        override fun putString(key: String, value: String?) = apply { staged[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
            staged[key] = values?.toSet()
        }

        override fun putInt(key: String, value: Int) = apply { staged[key] = value }

        override fun putLong(key: String, value: Long) = apply { staged[key] = value }

        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }

        override fun remove(key: String) = apply { removed += key }

        override fun clear() = apply { cleared = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (cleared) values.clear()
            removed.forEach(values::remove)
            values.putAll(staged)
        }
    }
}
