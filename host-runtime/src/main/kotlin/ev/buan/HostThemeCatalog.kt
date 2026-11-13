package ev.buan

import ev.buan.ui.EvThemeSpec
import ev.buan.ui.EvThemeRegistry
import org.cordis.api.ServiceDirectory
import org.cordis.api.ThemeCatalogService
import org.cordis.api.ThemeService

/**
 * Merges what theme plugins offer over the compiled-in fallback.
 *
 * The lookup happens per call rather than once at start: a theme plugin can be enabled, disabled or
 * replaced while the app runs, and the catalog has to answer with the truth of this moment. A
 * throwing or empty plugin is treated as absent - an app that cannot read its themes must still be
 * able to draw itself.
 */
internal class HostThemeCatalog(private val directory: ServiceDirectory) : ThemeCatalogService {

    override fun available(): List<EvThemeSpec> {
        val fromPlugin = runCatching { directory.lookup(ThemeService.Key)?.themes() }
            .getOrNull()
            .orEmpty()
            .filter { it.id.isNotBlank() }
        // Plugin themes win on id: a theme plugin may deliberately replace the built-in default's
        // look while keeping its id, which is how "my version of the default theme" is expressed.
        val pluginIds = fromPlugin.mapTo(HashSet()) { it.id }
        val fallback = EvThemeRegistry.all().filter { it.id !in pluginIds }
        return fromPlugin + fallback
    }

    override fun resolve(id: String): EvThemeSpec =
        available().firstOrNull { it.id == id } ?: EvThemeSpec.Default
}
