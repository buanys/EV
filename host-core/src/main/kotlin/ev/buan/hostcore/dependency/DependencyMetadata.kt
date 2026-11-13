package ev.buan.hostcore.dependency

/**
 * The two metadata shapes a plugin uses to declare the plugins it links against.
 *
 * Dependencies are ids, not files: the loader links by id (`android-plugin:<id>`), so the metadata
 * only has to carry the ids in declaration order. Like [ev.buan.hostcore.permission.PermissionMetadataParser]
 * this stays a pure string layer - reading an APK manifest or a sidecar file is Android work, and
 * the graph itself is [DependencyResolver]'s job.
 */
object DependencyMetadataParser {

    /**
     * APK `<meta-data>` key. The value is a JSON array *string*, e.g.
     * `["ev.buan.plugins.agent.api"]`; a plain comma separated list is not accepted.
     */
    const val APK_METADATA_KEY: String = "cordis.dependencies"

    /** jar/dex sidecar key: a JSON array, e.g. `"dependencies": ["ev.buan.plugins.agent.api"]`. */
    const val JAR_SIDECAR_KEY: String = "dependencies"

    /**
     * Normalises a declaration: blank entries are dropped, the rest are trimmed, order is kept and
     * duplicates collapse. A missing declaration is "depends on nothing", never a wildcard.
     */
    fun normalize(declared: List<String>?): List<String> =
        declared.orEmpty().asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
}
