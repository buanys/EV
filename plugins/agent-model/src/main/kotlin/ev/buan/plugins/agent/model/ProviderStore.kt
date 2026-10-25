package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelProvider
import org.cordis.api.PluginStorage

/**
 * The "where providers live" seam: a file-backed store on the device, an in-memory list in tests.
 *
 * The store is read through on every access rather than cached: the provider page writes the same
 * document from the UI, and a stale cache would keep completions pointed at a deleted provider.
 */
internal interface ProviderRepository {
    fun load(): List<ModelProvider>

    fun save(providers: List<ModelProvider>)
}

/**
 * [ProviderRepository] over this plugin's [PluginStorage] area.
 *
 * `config/providers.json` is the multi-provider document. When it does not exist yet, the legacy
 * single-provider `config/model.json` (if any) is migrated once and persisted, so an install that
 * predates the provider page keeps working without the user retyping the base URL and key. The
 * legacy file is left untouched: deleting it would make the migration unrecoverable.
 */
internal class ProviderStore(
    private val storage: PluginStorage,
    private val catalog: List<ModelProvider> = ProviderCatalog.builtins,
) : ProviderRepository {

    override fun load(): List<ModelProvider> {
        val raw = read(PATH)
        if (raw != null) {
            // A present but unreadable document degrades to the catalog; it never re-triggers the
            // legacy migration, which would resurrect a configuration the user just replaced.
            val saved = decode(raw).orEmpty()
            return ProviderDocuments.merge(saved, catalog)
        }
        val legacy = read(LEGACY_PATH)?.let { bytes ->
            runCatching { bytes.decodeToString() }.getOrNull()?.let(ModelConfigCodec::decode)
        }
        val migrated = legacy?.let(ProviderDocuments::migrateLegacy).orEmpty()
        if (migrated.isNotEmpty()) {
            // Best effort: the in-memory migration still works when the write fails, and the next
            // launch simply migrates again.
            runCatching { save(ProviderDocuments.storable(migrated, catalog)) }
        }
        return ProviderDocuments.merge(migrated, catalog)
    }

    override fun save(providers: List<ModelProvider>) {
        storage.atomicWrite(PATH, ProviderDocuments.encode(providers).encodeToByteArray())
    }

    private fun read(path: String): ByteArray? = runCatching { storage.read(path) }.getOrNull()

    private fun decode(raw: ByteArray): List<ModelProvider>? = runCatching { raw.decodeToString() }
        .getOrNull()
        ?.let(ProviderDocuments::decode)

    companion object {
        const val PATH: String = "config/providers.json"

        /** Pre-multi-provider location; read once for migration. */
        const val LEGACY_PATH: String = "config/model.json"
    }
}
