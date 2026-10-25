package ev.buan

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import ev.buan.ui.DEFAULT_INTERFACE_SCALE
import ev.buan.ui.EvAccentColor
import ev.buan.ui.EvAppearance
import ev.buan.ui.EvPaletteStyle
import ev.buan.ui.EvThemeMode
import org.json.JSONObject

/**
 * Persisted appearance state, exposed as Compose state so a change recomposes the whole host -
 * including every plugin, because the theme is applied above them.
 */
class AppearanceStore(private val context: Context) {

    private val preferences = context.getSharedPreferences("ev.appearance", Context.MODE_PRIVATE)
    private val state = mutableStateOf(load())

    val appearance: State<EvAppearance> = state

    fun update(transform: (EvAppearance) -> EvAppearance) {
        val next = transform(state.value).normalized()
        if (next == state.value) return
        state.value = next
        save(next)
    }

    private fun load(): EvAppearance {
        val raw = preferences.getString(KEY, null) ?: return EvAppearance()
        return runCatching {
            val json = JSONObject(raw)
            EvAppearance(
                themeMode = json.optEnum("themeMode", EvThemeMode.SYSTEM),
                monetEnabled = json.optBoolean("monetEnabled", false),
                paletteStyle = json.optEnum("paletteStyle", EvPaletteStyle.TONAL_SPOT),
                accentColor = json.optEnum("accentColor", EvAccentColor.SYSTEM),
                pureBlackEnabled = json.optBoolean("pureBlackEnabled", false),
                blurEnabled = json.optBoolean("blurEnabled", true),
                interfaceScale = json.optDouble("interfaceScale", DEFAULT_INTERFACE_SCALE.toDouble()).toFloat(),
            )
        }.getOrDefault(EvAppearance())
    }

    private fun save(appearance: EvAppearance) {
        val json = JSONObject()
            .put("themeMode", appearance.themeMode.name)
            .put("monetEnabled", appearance.monetEnabled)
            .put("paletteStyle", appearance.paletteStyle.name)
            .put("accentColor", appearance.accentColor.name)
            .put("pureBlackEnabled", appearance.pureBlackEnabled)
            .put("blurEnabled", appearance.blurEnabled)
            .put("interfaceScale", appearance.interfaceScale.toDouble())
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T =
        optString(name, fallback.name).let { stored ->
            enumValues<T>().firstOrNull { it.name == stored } ?: fallback
        }

    private companion object {
        const val KEY = "state"
    }
}
