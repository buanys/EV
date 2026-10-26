package ev.buan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import ev.buan.hostcore.dependency.DependencyMetadataParser
import ev.buan.hostcore.dependency.DependencyResolver
import ev.buan.hostcore.permission.PermissionDeclaration
import ev.buan.hostcore.permission.PermissionMetadataParser
import ev.buan.hostcore.plugin.PluginId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.HostPermission
import org.cordis.api.PLUGIN_API_VERSION
import ev.buan.hostcore.validation.DexClassScanner
import ev.buan.hostcore.validation.HostPolicy
import ev.buan.hostcore.validation.PluginCandidate
import ev.buan.hostcore.validation.PluginRejectionCode
import ev.buan.hostcore.validation.PluginValidator
import ev.buan.hostcore.validation.ValidationResult
import org.json.JSONArray
import org.json.JSONObject

/** One artifact that passed trust, version and hash checks and now lives in private storage. */
data class InstalledPlugin(
    val id: String,
    val version: String,
    val entryClass: String,
    val apiVersion: Int,
    val file: File,
    val signed: Boolean,
    /** Plugin ids this container links against; fed into the module descriptor. */
    val dependencies: List<String> = emptyList(),
    /** Hash accepted during sync; load-time re-checks against this. */
    val sha256: String,
    /**
     * Capabilities the container declared in its metadata. Carried past sync so activation can
     * re-check them against the grants currently stored by the host (T06).
     */
    val requestedPermissions: Set<HostPermission> = emptySet(),
)

/** An unsigned container the user has not approved (or whose bytes changed). */
data class PendingUnsigned(
    val id: String,
    val version: String,
    val sha256: String,
    val fileName: String,
)

data class SyncResult(
    val plugins: List<InstalledPlugin>,
    val errors: List<String>,
    val pending: List<PendingUnsigned> = emptyList(),
)

/**
 * Seeds the bundled plugin and mirrors `<external storage>/EV/mods` into app-private storage.
 *
 * Every candidate must pass three gates before it is copied:
 * 1. trust - APK signed by the host certificate, jar/dex only while unsigned containers are allowed;
 * 2. API version - must equal the host contract, otherwise a plugin would fail later with an
 *    unrelated NoSuchMethodError instead of a clear rejection;
 * 3. identity - two sources claiming the same id must be byte-identical, otherwise it is a conflict.
 *
 * Sources are collected first and accepted afterwards in dependency order ([acceptOrder]), so a
 * plugin may link against a container whose file name sorts after its own - or that only exists in
 * another source directory.
 */
class PluginInstaller(
    private val context: Context,
    private val trust: PluginTrust = PluginTrust(context),
    private val approvals: UnsignedApprovalStore = UnsignedApprovalStore(context),
    /** The user's grants; install records declarations, activation enforces them (T06). */
    private val permissions: PermissionStore = PermissionStore(context),
    /**
     * Host policy: unsigned containers are trusted on first sight.
     *
     * The hash is still recorded, so a byte swap after sync is refused at load time; what is gone
     * is the manual "信任" step. Flip to false to require explicit approval per container.
     */
    /**
     * 未签名插件是否无需确认即可加载。默认 **false**：插件列表页会把这些插件列为「待确认」
     * （[PendingUnsigned]），用户在那里逐个批准，批准记录按 id + sha256 保存。
     *
     * 曾经默认 true——侧载时方便，但它意味着"丢进目录即可执行"，一旦对外分发就是任意代码
     * 执行入口。改成 false 不会让人装不上插件：宿主启动后停在插件管理页，导入 + 确认即可。
     */
    private val autoTrustUnsigned: Boolean = false,
) {

    private val privateRoot: File = File(context.filesDir, "mods")
    private val codeCacheRoot: File = File(context.codeCacheDir, "cordis-plugins")

    private val externalRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "EV/mods")

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Deletes one container from the external mods directory: the jar and its sidecar json.
     *
     * Plugin *storage* is deliberately untouched - "卸载" removes the code, not the user's data,
     * and the management page offers "清理" separately for that. The private mirror is refreshed by
     * the next [sync], which no longer finds these files and drops them.
     *
     * Returns the names that were actually removed, so the caller can report a partial failure
     * instead of claiming success.
     */
    fun removeExternal(id: String): List<String> {
        val targets = listOf("$id.jar", "$id.json")
        val removed = mutableListOf<String>()
        for (name in targets) {
            val file = File(externalRoot, name)
            if (file.isFile && file.delete()) removed += name
        }
        return removed
    }

    /**
     * Copies a user-picked container into the external mods directory.
     *
     * The name is kept verbatim: sidecar lookup is by the jar's own file name, so renaming here
     * would silently detach a jar from its metadata. An existing file is replaced only when
     * [overwrite] is set, so importing twice is an explicit decision.
     */
    fun importExternal(source: File, fileName: String, overwrite: Boolean): File? {
        if (!externalRoot.isDirectory && !externalRoot.mkdirs()) return null
        val target = File(externalRoot, fileName)
        if (target.exists() && !overwrite) return null
        return runCatching {
            source.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        privateRoot.mkdirs()
        privateRoot.setWritable(true)

        val accepted = linkedMapOf<String, InstalledPlugin>()
        val hashes = mutableMapOf<String, String>()
        val pending = mutableListOf<PendingUnsigned>()

        // Collect first, accept later: a container may only be accepted after the plugins it links
        // against, so the accept order comes from the declared dependency graph - never from file
        // names or from which source (bundled or external) a container came from.
        val sources = mutableListOf<PluginSource>()
        collectBundled(sources, errors)
        collectExternal(sources, errors)
        acceptOrder(sources, errors).forEach { source ->
            accept(source.file, source.meta, accepted, hashes, pending, errors)
        }

        prunePrivate(accepted.keys, errors)
        pruneCodeCache(accepted.values.associate { it.id to it.version }, errors)

        privateRoot.setReadOnly()
        SyncResult(accepted.values.toList(), errors, pending)
    }

    // ---------------------------------------------------------------- sources

    /** One container that parsed cleanly and is waiting for its place in the accept order. */
    private data class PluginSource(val file: File, val meta: PluginMeta)

    private fun collectBundled(sources: MutableList<PluginSource>, errors: MutableList<String>) {
        val names = runCatching { context.assets.list("plugins/android")?.toList().orEmpty() }
            .getOrElse {
                errors += "读取内置插件失败: $it"
                return
            }
        // Assets have no meaningful order of their own; the listing is kept stable for reproducible
        // messages, but it no longer decides when a container is accepted.
        names.filter { it.endsWith(".apk") }.sorted().forEach { name ->
            runCatching {
                val staged = stageAsset(name)
                val meta = readApkMetadata(staged) ?: error("缺少 cordis.entry 元数据")
                // The id becomes a private file name and a loader module id; reject it before
                // anything is copied or written. The error stays per-asset, not a crash.
                PluginId.requireValid(meta.id)
                sources += PluginSource(staged, meta)
            }.onFailure { errors += "内置插件 $name: $it" }
        }
    }

    /**
     * Collects `<external storage>/EV/mods`, accepting nothing.
     *
     * The listing order is whatever the filesystem returns; two containers are only ordered against
     * each other when one of them declares the other as a dependency.
     */
    private fun collectExternal(sources: MutableList<PluginSource>, errors: MutableList<String>) {
        val external = externalRoot
        if (!hasAllFilesAccess()) {
            errors += "未授予「所有文件访问权限」，跳过扫描 ${external.path}"
            return
        }
        if (!external.isDirectory && !external.mkdirs()) {
            errors += "无法创建 ${external.path}"
            return
        }
        external.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.forEach { source ->
                runCatching {
                    val meta = readMetadata(source)
                        ?: error("缺少元数据（APK 需 cordis.entry/cordis.api，jar/dex 需同名 .json）")
                    // Fail closed before accept/publish: an id containing a path separator, `..`,
                    // a drive prefix or control characters must never become a file name.
                    PluginId.requireValid(meta.id)
                    sources += PluginSource(source, meta)
                }.onFailure { errors += "${source.name}: $it" }
            }
    }

    /**
     * Puts the collected containers in the order they may be accepted: dependencies first.
     *
     * The validator refuses a plugin whose dependencies were not accepted earlier in this same pass,
     * so this order is what makes the directory insensitive to file names. Containers in a
     * dependency cycle can never satisfy that gate: each cycle is reported once with
     * [PluginRejectionCode.DEPENDENCY_CYCLE] and left out of the plan - their private copies get
     * pruned, and a plugin that links against them is rejected with `DEPENDENCY_MISSING`.
     */
    private fun acceptOrder(
        sources: List<PluginSource>,
        errors: MutableList<String>,
    ): List<PluginSource> {
        if (sources.isEmpty()) return emptyList()
        val byId = sources.groupBy { it.meta.id }
        // Two containers claiming one id are separated by the identity gate, not here; the first
        // one discovered (bundled before external) supplies the id's declared dependencies.
        val declared = byId.mapValues { (_, candidates) -> candidates.first().meta.dependencies }
        val resolution = DependencyResolver.resolve(declared)
        resolution.cycleRings.forEach { ring ->
            errors += "${PluginRejectionCode.DEPENDENCY_CYCLE}: 循环依赖 $ring" +
                "（无法确定接受顺序，相关插件全部拒绝）"
        }
        return resolution.order.flatMap { id -> byId[id].orEmpty() }
    }

    // ---------------------------------------------------------------- gates

    /**
     * Runs every gate through [PluginValidator] and either publishes the container, records it as
     * pending approval, or reports a coded rejection. Adding a rule means editing the validator,
     * not this function.
     */
    private fun accept(
        source: File,
        meta: PluginMeta,
        accepted: MutableMap<String, InstalledPlugin>,
        hashes: MutableMap<String, String>,
        pending: MutableList<PendingUnsigned>,
        errors: MutableList<String>,
    ) {
        // Unknown declarations are a metadata rejection, not a runtime surprise: this host can
        // never gate a capability it does not understand, so fail closed before hashing/copying.
        if (!meta.permissions.isWellFormed) {
            errors += "${PluginRejectionCode.PERMISSION_DENIED}: 插件声明了宿主未知的权限 " +
                "${meta.permissions.unknown.joinToString(", ")}（请升级宿主或插件）"
            return
        }
        val hash = androidModuleHash(source)
        val violations = DexClassScanner.violations(source, reservedPrefixes()).map { it.className }
        val candidate = PluginCandidate(
            id = meta.id,
            version = meta.version,
            entryClass = meta.entryClass,
            apiVersion = meta.apiVersion,
            sha256 = hash,
            signing = trust.signingState(source),
            platformClassViolations = violations,
            dependencies = meta.dependencies,
            // Host-core's validator only knows the wire names; HostPermission stays host-side.
            requestedPermissions = meta.permissions.declared,
        )
        val policy = HostPolicy(
            hostCertificateSha256 = trust.hostCertificateHashes,
            supportedApiVersion = PLUGIN_API_VERSION,
            approvedUnsignedHashes = approvals.approved(),
            installedHashes = hashes.toMap(),
            acceptedPluginIds = accepted.keys.toSet(),
            // The user's grants feed the validator's permission gate. That gate runs last, so an
            // ungranted declaration does not hide a trust/content problem behind it.
            grantedPermissions = permissions.grants().mapValues { (_, granted) ->
                granted.mapTo(LinkedHashSet()) { it.name }
            },
        )

        var decision = PluginValidator.validate(candidate, policy)
        if (decision is ValidationResult.NeedsUnsignedApproval) {
            if (!autoTrustUnsigned) {
                pending += PendingUnsigned(meta.id, meta.version, decision.sha256, source.name)
                return
            }
            approvals.approve(decision.pluginId, decision.sha256)
            decision = PluginValidator.validate(
                candidate,
                policy.copy(approvedUnsignedHashes = approvals.approved()),
            )
        }
        when (decision) {
            ValidationResult.Accepted -> publish(source, meta, hash, candidate, accepted, hashes)
            is ValidationResult.Rejected ->
                if (decision.rejection.code == PluginRejectionCode.PERMISSION_DENIED) {
                    // Install is not run. Publish so the management page can list the plugin and
                    // the user can grant the missing permissions; HostRuntime.activate is the gate
                    // that decides whether it may actually load. The permission gate is the last
                    // one in the validator, so every trust/content/identity check already passed.
                    publish(source, meta, hash, candidate, accepted, hashes)
                } else {
                    errors += "${decision.rejection.code}: ${decision.rejection.message}"
                }

            is ValidationResult.NeedsUnsignedApproval ->
                pending += PendingUnsigned(meta.id, meta.version, decision.sha256, source.name)
        }
    }

    /** Copies the bytes into private storage, read-only, verifying the hash across the copy. */
    private fun publish(
        source: File,
        meta: PluginMeta,
        hash: String,
        candidate: PluginCandidate,
        accepted: MutableMap<String, InstalledPlugin>,
        hashes: MutableMap<String, String>,
    ) {
        val target = privateFileFor(meta.id)
        val installed = target.takeIf { it.isFile }?.let(::androidModuleHash)
        if (installed != hash) {
            val temporary = File(privateRoot, PluginId.stagingFileName(meta.id))
            deleteFile(temporary)
            source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
            check(androidModuleHash(temporary) == hash) { "复制后哈希不一致: ${meta.id}" }
            temporary.setReadOnly()
            deleteFile(target)
            check(temporary.renameTo(target)) { "无法发布 $target" }
        }

        hashes[meta.id] = hash
        accepted[meta.id] = InstalledPlugin(
            id = meta.id,
            version = meta.version,
            entryClass = meta.entryClass,
            apiVersion = meta.apiVersion,
            file = target,
            signed = candidate.signing is ev.buan.hostcore.validation.SigningState.Signed,
            sha256 = hash,
            dependencies = meta.dependencies,
            requestedPermissions = meta.permissions.declared
                .mapNotNull(HostPermission::fromWireName)
                .toSet(),
        )
    }

    // ---------------------------------------------------------------- maintenance

    /** Private copies whose source disappeared are removed; the loader never sees stale files. */
    private fun prunePrivate(expectedIds: Set<String>, errors: MutableList<String>) {
        privateRoot.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val stale = file.name.startsWith(".") || file.nameWithoutExtension !in expectedIds
            if (stale) runCatching { deleteFile(file) }
                .onFailure { errors += "清理 ${file.name} 失败: $it" }
        }
    }

    /** Dexopt output is per (id, version); anything else is dead weight. */
    private fun pruneCodeCache(current: Map<String, String>, errors: MutableList<String>) {
        codeCacheRoot.listFiles()?.forEach { idDir ->
            val currentVersion = current[idDir.name]
            if (currentVersion == null) {
                runCatching { idDir.deleteRecursively() }
                    .onFailure { errors += "清理缓存 ${idDir.name} 失败: $it" }
                return@forEach
            }
            idDir.listFiles()?.forEach { versionDir ->
                if (versionDir.name != currentVersion) {
                    runCatching { versionDir.deleteRecursively() }
                        .onFailure { errors += "清理缓存 ${idDir.name}/${versionDir.name} 失败: $it" }
                }
            }
        }
    }

    // ---------------------------------------------------------------- metadata

    private data class PluginMeta(
        val id: String,
        val version: String,
        val entryClass: String,
        val apiVersion: Int,
        val dependencies: List<String> = emptyList(),
        /** Capabilities declared in metadata, split into known/unknown names. */
        val permissions: PermissionDeclaration = PermissionDeclaration(),
    )

    private fun readMetadata(file: File): PluginMeta? =
        if (file.extension.lowercase() == "apk") readApkMetadata(file) else readSidecarMetadata(file)

    /**
     * Reads package name, version, entry class, API version, declared dependencies and declared
     * permissions from the APK manifest. The host never installs the archive, so everything has to
     * come from metadata.
     */
    private fun readApkMetadata(file: File): PluginMeta? {
        if (!file.isFile) return null
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            file.path,
            PackageManager.GET_META_DATA,
        ) ?: return null
        val meta = info.applicationInfo?.metaData ?: return null
        val entry = meta.getString(ENTRY_META) ?: return null
        return PluginMeta(
            id = info.packageName,
            version = info.versionName ?: "1.0.0",
            entryClass = entry,
            apiVersion = meta.getInt(API_META, 0),
            dependencies = apkDependencies(meta.getString(DependencyMetadataParser.APK_METADATA_KEY)),
            permissions = PermissionMetadataParser.parseApkMetadata(
                raw = meta.getString(PermissionMetadataParser.APK_METADATA_KEY),
                supported = HostPermission.wireNames,
            ),
        )
    }

    private fun readSidecarMetadata(file: File): PluginMeta? {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (!sidecar.isFile) return null
        val json = runCatching { JSONObject(sidecar.readText()) }.getOrNull() ?: return null
        return PluginMeta(
            id = json.getString("id"),
            version = json.optString("version", "1.0.0"),
            entryClass = json.getString("entryClass"),
            apiVersion = json.optInt("apiVersion", 0),
            dependencies = DependencyMetadataParser.normalize(
                json.stringList(DependencyMetadataParser.JAR_SIDECAR_KEY),
            ),
            permissions = PermissionMetadataParser.parseJarMetadata(
                declared = json.stringList(PermissionMetadataParser.JAR_SIDECAR_KEY),
                supported = HostPermission.wireNames,
            ),
        )
    }

    private fun JSONObject.stringList(key: String): List<String>? =
        optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf(String::isNotBlank)
            }
        }

    /**
     * Reads `cordis.dependencies` from an APK: a JSON array *string* because Android meta-data holds
     * strings, e.g. `["ev.buan.plugins.agent.api"]`.
     *
     * A malformed value is a metadata error rather than "no dependencies": a container whose links
     * cannot be parsed would otherwise be accepted without them ever being ordered or linked.
     */
    private fun apkDependencies(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse {
            throw IllegalArgumentException(
                "${DependencyMetadataParser.APK_METADATA_KEY} 必须是 JSON 数组字符串（实际：$raw）",
            )
        }
        val declared = (0 until array.length()).map { index ->
            array.opt(index) as? String ?: throw IllegalArgumentException(
                "${DependencyMetadataParser.APK_METADATA_KEY} 第 ${index + 1} 项不是插件 id 字符串",
            )
        }
        return DependencyMetadataParser.normalize(declared)
    }

    // ---------------------------------------------------------------- files

    private fun stageAsset(name: String): File {
        val staged = File(context.cacheDir, "seed/$name")
        staged.parentFile?.mkdirs()
        if (!staged.isFile) {
            context.assets.open("plugins/android/$name").use { input ->
                staged.outputStream().use(input::copyTo)
            }
        }
        return staged
    }

    /** Read-only files cannot be deleted directly, so reopen them first. */
    private fun deleteFile(file: File) {
        if (!file.exists()) return
        file.setWritable(true)
        check(file.delete()) { "无法删除 $file" }
    }

    /**
     * Packages a plugin must not define: the platform, the Kotlin runtime, the kernel and every
     * package the host already provides. Mirrors the class loader's shared prefixes so a container
     * that duplicates one of them is rejected instead of producing two class identities.
     */
    private fun reservedPrefixes(): List<String> =
        PLATFORM_PREFIXES + org.cordis.api.HostPackages.shared + "ev.buan.ui"

    private fun privateFileFor(id: String): File {
        // Defense in depth: collect validates the id, but the file-name helper is the last line
        // between an id and the private root, so it re-checks instead of trusting the caller.
        val name = PluginId.fileName(id)
        privateRoot.mkdirs()
        return File(privateRoot, name)
    }

    private companion object {
        // Packages the platform or the host runtime always owns. A plugin defining any of these
        // shadows a class the OS or the host already provides, so it is rejected outright.
        // This list is deliberately independent of HostPackages: a package stops being shared
        // (org.json, once the bundled copy was removed) but must never become plugin-definable.
        val PLATFORM_PREFIXES = listOf(
            "java", "javax", "android", "dalvik",
            "kotlin", "kotlinx.coroutines",
            "org.cordis",
            "org.json", "org.apache.http", "org.xmlpull", "org.w3c.dom", "org.xml.sax",
            "junit",
        )
        const val ENTRY_META = "cordis.entry"
        const val API_META = "cordis.api"
        val SUPPORTED_EXTENSIONS = setOf("apk", "jar", "dex")
    }
}
