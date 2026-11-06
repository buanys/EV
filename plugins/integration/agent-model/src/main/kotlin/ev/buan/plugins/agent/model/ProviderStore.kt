package ev.buan.plugins.agent.model

import ev.buan.plugins.agent.api.ModelProvider
import org.cordis.api.CredentialService
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
    /**
     * 密钥由宿主保管（规则：凭据归宿主）。为 null 时是"老宿主没有这个契约"的降级路径，
     * 那时 key 只能留在 json 里——这条分支存在是为了兼容，不是为了默认。
     */
    private val credentials: CredentialService? = null,
) : ProviderRepository {

    override fun load(): List<ModelProvider> {
        val raw = read(PATH)
        if (raw != null) {
            // A present but unreadable document degrades to the catalog; it never re-triggers the
            // legacy migration, which would resurrect a configuration the user just replaced.
            val saved = decode(raw).orEmpty()
            val hydrated = hydrate(saved)
            // 旧文件里带明文 key：搬进宿主凭据后重写一次文档，让磁盘上不再有密钥。
            if (credentials != null && saved.any { it.apiKey.isNotBlank() }) {
                runCatching { save(hydrated) }
            }
            return ProviderDocuments.merge(hydrated, catalog)
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
        // 先落凭据、再落文档：反过来会在两步之间留下"文档说没有 key、凭据里也没有"的窗口。
        credentials?.let { secrets ->
            providers.forEach { provider ->
                val key = provider.apiKey.trim()
                runCatching {
                    if (key.isEmpty()) secrets.erase(credentialName(provider.id)) else secrets.write(credentialName(provider.id), key)
                }
            }
        }
        storage.atomicWrite(PATH, ProviderDocuments.encode(providers).encodeToByteArray())
    }

    /** 把宿主保管的密钥补回运行期的 provider 上。取不到就是没有 key，不当成错误。 */
    private fun hydrate(providers: List<ModelProvider>): List<ModelProvider> {
        val secrets = credentials ?: return providers
        return providers.map { provider ->
            if (provider.apiKey.isNotBlank()) {
                provider
            } else {
                provider.copy(apiKey = runCatching { secrets.read(credentialName(provider.id)) }.getOrNull().orEmpty())
            }
        }
    }

    private fun credentialName(providerId: String): String = "provider:$providerId"

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
