package ev.buan

import org.cordis.api.PluginCatalogService
import org.cordis.api.PluginManifest

/**
 * The manifests plugins declared during activation, in the order they loaded.
 *
 * A declaration is advisory: the host checks the id matches the container that produced it and
 * ignores anything that does not, so one plugin cannot describe another. Nothing here is trusted for
 * behaviour - the services and tools a plugin publishes are still discovered by resolving them.
 *
 * The source is named `declared` on purpose: calling it `manifests` made [manifests] resolve to
 * itself and the first tap on the detail page blew the stack.
 */
internal class HostPluginCatalog(
    private val declared: () -> Collection<PluginManifest>,
) : PluginCatalogService {

    override fun manifests(): List<PluginManifest> = declared().sortedBy { it.name }

    override fun manifestOf(id: String): PluginManifest? = declared().firstOrNull { it.id == id }
}
