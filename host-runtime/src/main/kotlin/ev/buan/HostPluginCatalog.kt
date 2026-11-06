package ev.buan

import org.cordis.api.PluginCatalogService
import org.cordis.api.PluginManifest
import org.cordis.api.PluginSettings

/**
 * The manifests plugins declared during activation, in the order they loaded.
 *
 * A declaration is advisory: the host checks the id matches the container that produced it and
 * ignores anything that does not, so one plugin cannot describe another. Nothing here is trusted for
 * behaviour - the services and tools a plugin publishes are still discovered by resolving them.
 *
 * Configuration is the exception that has to be trusted: a value is written through the plugin that
 * declared the setting, because only it knows where the value lives and what it means.
 *
 * The sources are named `declared` and `settingsOf` on purpose: calling one `manifests` made
 * [manifests] resolve to itself and the first tap on the detail page blew the stack.
 */
internal class HostPluginCatalog(
    private val declared: () -> Collection<PluginManifest>,
    private val settingsOf: (String) -> PluginSettings?,
) : PluginCatalogService {

    override fun manifests(): List<PluginManifest> = declared().sortedBy { it.name }

    override fun manifestOf(id: String): PluginManifest? = declared().firstOrNull { it.id == id }

    override fun readSetting(id: String, key: String): String? =
        runCatching { settingsOf(id)?.read(key) }.getOrNull()

    override fun writeSetting(id: String, key: String, value: String) {
        // Swallowed on purpose: a plugin that rejects a value must not take the settings page with it.
        runCatching { settingsOf(id)?.write(key, value) }
    }
}
