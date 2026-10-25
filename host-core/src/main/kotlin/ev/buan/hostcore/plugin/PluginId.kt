package ev.buan.hostcore.plugin

/**
 * Rules for a plugin id, shared by everything that turns an id into a file name.
 *
 * An id arrives from plugin metadata (jar/dex sidecar or the APK package name) and is used as a
 * loader module id, a private file name and a management-page key. The rule mirrors the kernel
 * loader's `ID_PATTERN` without importing the kernel: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`.
 *
 * Because the first character must be alphanumeric, `.`, `..` and hidden names are impossible, and
 * because `/`, `\`, `:`, whitespace and control characters are outside the set, a valid id can
 * never add a path segment. That is what makes [fileName] safe for a file inside the private root.
 */
object PluginId {

    /** Maximum id length (128), matching the kernel's loader pattern. */
    const val MAX_LENGTH: Int = 128

    private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    /** Returns [id] unchanged, or throws [IllegalArgumentException] with the reason. */
    fun requireValid(id: String): String {
        require(isValid(id)) {
            "非法插件 id「$id」：只允许 [A-Za-z0-9][A-Za-z0-9._-]{0,127}"
        }
        return id
    }

    /** File name of the private copy (`<id>.apk`); never contains a path separator. */
    fun fileName(id: String): String = "${requireValid(id)}.apk"

    /** Hidden staging name used while copying a container into place. */
    fun stagingFileName(id: String): String = ".${requireValid(id)}.installing"
}
