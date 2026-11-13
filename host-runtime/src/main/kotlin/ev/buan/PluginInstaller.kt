package ev.buan

import android.content.Context
import ev.buan.hostcore.dependency.DependencyResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.HostPermission
import org.cordis.api.PLUGIN_API_VERSION
import ev.buan.hostcore.trust.CapabilitySurface
import ev.buan.hostcore.trust.CapabilityTrust
import ev.buan.hostcore.trust.TrustOutcome
import ev.buan.hostcore.validation.DexClassScanner
import ev.buan.hostcore.validation.HostPolicy
import ev.buan.hostcore.validation.PluginCandidate
import ev.buan.hostcore.validation.PluginRejectionCode
import ev.buan.hostcore.validation.PluginValidator
import ev.buan.hostcore.validation.ValidationResult

/** One artifact that passed trust, version and hash checks and now lives in private storage. */
data class InstalledPlugin(
    val id: String,
    val version: String,
    val entryClass: String,
    val apiVersion: Int,
    val file: File,
    /** 它在外部投放目录里的文件名；回滚写回时要用它，而不是私有副本的名字。 */
    val sourceName: String = "",
    /**
     * 容器声明的能力项；空表示必须常驻。
     *
     * 扫描期就确定，装载期只读——所以宿主不装着它也能回答"这个查找有没有人接"。
     */
    val provides: Set<String> = emptySet(),
    val signed: Boolean,
    /** Plugin ids this container links against; fed into the module descriptor. */
    val dependencies: List<String> = emptyList(),
    /** Hash accepted during sync; load-time re-checks against this. */
    val sha256: String,
    /** Desktop entries this container declares in its sidecar; the host creates them on request. */
    val shortcuts: List<ShortcutDeclaration> = emptyList(),
    /**
     * Capabilities the container declared in its metadata. Carried past sync so activation can
     * re-check them against the grants currently stored by the host (T06).
     */
    val requestedPermissions: Set<HostPermission> = emptySet(),
    /** 声明了"在独立进程里跑"（sidecar 的 `isolation: separate-process`）。 */
    val separateProcess: Boolean = false,
    /** 来源与安装时判定的信任档位：激活门和卡片都读它们，没有第二个来源。 */
    val origin: ev.buan.hostcore.trust.PluginOrigin = ev.buan.hostcore.trust.PluginOrigin.EXTERNAL,
    val trust: ev.buan.hostcore.trust.TrustDecision,
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
     *
     * 唯一的例外是调试包：调用方按 ApplicationInfo.FLAG_DEBUGGABLE 决定是否传 true。
     * 因为信任绑在 jar 的 sha256 上，重构期间每重编一次插件就要重新确认一遍；
     * 正式包不会走到这条路径。
     */
    private val autoTrustUnsigned: Boolean = false,
    /**
     * ADR-0008 的能力面记录与 per-id opt-in。
     *
     * 默认状态下没有记录、opt-in 关闭，所以判定永远落到"需要确认"——
     * 这个参数存在与否不改变现有行为。
     */
    private val capabilityTrust: CapabilityTrustStore = CapabilityTrustStore(context),
) {

    /** 宿主的三条插件路径与它们之间的搬运，见 [PluginFiles]。 */
    private val files = PluginFiles(context)
    /**
     * Scan results for containers that have not changed since the last launch.
     *
     * Hashing a 27 MB container takes the better part of a second on this hardware and the dex
     * scan costs as much again, so re-proving the same bytes every start is what made boots grow
     * with the plugin count. See [PluginScanCache] for the identity rule.
     */
    private val scanCache = PluginScanCache(File(context.filesDir, "plugin-scan-cache.json"))
        .also { it.load() }

    /** 元数据只读：File 进去、数据出来，不碰安装器的任何状态。 */
    private val metadata = PluginMetadataReader(context)

    private val collector = PluginCollector(context, files, metadata)

    // 文件搬运的实现搬进了 PluginFiles；这四个仍是安装器的公开面（宿主与插件页在用）。

    fun hasAllFilesAccess(): Boolean = files.hasAllFilesAccess()

    fun removeExternal(id: String): List<String> = files.removeExternal(id)

    fun removePrivate(id: String): Boolean = files.removePrivate(id)

    fun importExternal(source: File, fileName: String, overwrite: Boolean): File? =
        files.importExternal(source, fileName, overwrite)

    fun rollback(id: String, jarName: String): List<String> = files.restorePrevious(id, jarName)
    fun hasRollback(id: String): Boolean = files.hasPrevious(id)

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        files.beginSync()

        val accepted = linkedMapOf<String, InstalledPlugin>()
        val hashes = mutableMapOf<String, String>()
        val pending = mutableListOf<PendingUnsigned>()

        // Collect first, accept later: a container may only be accepted after the plugins it links
        // against, so the accept order comes from the declared dependency graph - never from file
        // names or from which source (bundled or external) a container came from.
        val sources = mutableListOf<PluginSource>()
        collector.bundled(sources, errors)
        collector.external(sources, errors)
        acceptOrder(sources, errors).forEach { source ->
            // Per-container isolation: one jar that makes a gate throw used to abort the whole
            // accept loop, and the plugin simply vanished from every list with no message. A bad
            // container must cost exactly its own slot.
            runCatching {
                accept(source, accepted, hashes, pending, errors)
            }.onFailure { error ->
                errors += "${source.file.name}: ${error.javaClass.name}: ${error.message.orEmpty()}"
            }
        }

        files.prune(accepted.keys, accepted.values.associate { it.id to it.version }, errors)
        
        // Only containers scanned this pass stay in the cache: a deleted jar must not leave a
        // stale entry behind that a later file with the same path could accidentally match.
        scanCache.retain(sources.mapTo(HashSet()) { it.file.absolutePath })
        scanCache.save()

        files.endSync()
        SyncResult(accepted.values.toList(), errors, pending)
    }

    // ---------------------------------------------------------------- sources

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
        // 排序只认 id：版本段归装配检查，留在这里会让依赖边被当成未知 id 丢掉。
        val declared = byId.mapValues { (_, candidates) ->
            candidates.first().meta.dependencies
                .mapNotNull { ev.buan.hostcore.dependency.PluginDependency.parse(it)?.id }
        }
        val resolution = DependencyResolver.resolve(declared)
        resolution.cycleRings.forEach { ring ->
            errors += "${PluginRejectionCode.DEPENDENCY_CYCLE}: 循环依赖 $ring" +
                "（无法确定接受顺序，相关插件全部拒绝）"
        }
        // Visibility for the one path that used to be silent: an id that was collected but never
        // reaches the accept loop leaves no card, no pending row and no error - it just disappears.
        val ordered = resolution.order.toSet()
        (byId.keys - ordered).filterNot { it in resolution.cyclicIds }.forEach { id ->
            errors += "$id: 已扫描但未进入接受顺序（依赖解析没有给出位置，插件未安装）"
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
        source: PluginSource,
        accepted: MutableMap<String, InstalledPlugin>,
        hashes: MutableMap<String, String>,
        pending: MutableList<PendingUnsigned>,
        errors: MutableList<String>,
    ) {
        val file = source.file
        val meta = source.meta
        val origin = source.origin
        // Unknown declarations are a metadata rejection, not a runtime surprise: this host can
        // never gate a capability it does not understand, so fail closed before hashing/copying.
        if (!meta.permissions.isWellFormed) {
            errors += "${PluginRejectionCode.PERMISSION_DENIED}: 插件声明了宿主未知的权限 " +
                "${meta.permissions.unknown.joinToString(", ")}（请升级宿主或插件）"
            return
        }
        // A container that has not changed since the last launch keeps its scan result: hashing and
        // the dex scan are the two expensive steps here, and neither can differ for identical
        // bytes. A changed container re-runs both, so an edit is always re-validated.
        val size = file.length()
        val modified = file.lastModified()
        val cached = scanCache.get(file.absolutePath, size, modified)
        val hash = cached?.sha256 ?: androidModuleHash(file)
        val violations = cached?.violations
            ?: DexClassScanner.violations(file, PluginPolicy.reservedPrefixes()).map { it.className }
        if (cached == null) {
            scanCache.put(
                file.absolutePath,
                PluginScanCache.Entry(size, modified, hash, violations),
            )
        }
        val candidate = PluginCandidate(
            id = meta.id,
            version = meta.version,
            entryClass = meta.entryClass,
            apiVersion = meta.apiVersion,
            hostApiRange = meta.hostApi,
            sha256 = hash,
            origin = origin,
            signing = trust.signingState(file),
            platformClassViolations = violations,
            // 带版本交给校验器：`id@1.2.0` 由 PluginValidator.dependencyGate 检查。
            // 剥版本只发生在交给内核 descriptor 的那一处（见 HostActivation）。
            dependencies = meta.dependencies,
            // Host-core's validator only knows the wire names; HostPermission stays host-side.
            requestedPermissions = meta.permissions.declared,
        )
        val policy = HostPolicy(
            hostCertificateSha256 = trust.hostCertificateHashes,
            supportedApiVersion = PLUGIN_API_VERSION,
            hostApiVersion = supportedHostApi,
            approvedUnsignedHashes = approvals.approved(),
            installedHashes = hashes.toMap(),
            acceptedPluginVersions = accepted.mapValues { (_, plugin) -> plugin.version },
            // The user's grants feed the validator's permission gate. That gate runs last, so an
            // ungranted declaration does not hide a trust/content problem behind it.
            grantedPermissions = permissions.grants().mapValues { (_, granted) ->
                granted.mapTo(LinkedHashSet()) { it.name }
            },
        )

        // ADR-0008：未签名插件的「能力面」。依赖也算——它可以通过 lookup 高权限插件的
        // 服务间接扩权，所以不能只比权限。
        val surface = CapabilitySurface.of(meta.permissions.declared, meta.dependencies)

        var decision = PluginValidator.validate(candidate, policy)
        if (decision is ValidationResult.NeedsUnsignedApproval) {
            if (!autoTrustUnsigned) {
                val outcome = CapabilityTrust.decide(
                    previous = capabilityTrust.surface(meta.id),
                    current = surface,
                    optedIn = capabilityTrust.trustsUpdates(meta.id),
                )
                if (outcome !is TrustOutcome.Approve) {
                    val reason = CapabilityTrust.describe(outcome)
                    pending += PendingUnsigned(
                        id = meta.id,
                        version = meta.version,
                        sha256 = decision.sha256,
                        fileName = file.name,
                        reason = reason,
                    )
                    tracePlugin("pending ${meta.id} (${file.name}) · $reason")
                    return
                }
                tracePlugin("auto-approved ${meta.id} (${file.name}) · ${CapabilityTrust.describe(outcome)}")
            }
            approvals.approve(decision.pluginId, decision.sha256)
            capabilityTrust.record(meta.id, surface)
            decision = PluginValidator.validate(
                candidate,
                policy.copy(approvedUnsignedHashes = approvals.approved()),
            )
        }
        when (decision) {
            is ValidationResult.Accepted -> {
                // 人工批准过的未签名插件，下次启动因 sha256 匹配直接到这里——此时补记能力面，
                // 作为它下一次更新时的比对基准。已存在就不覆盖，避免把扩权写进基准。
                if (approvals.approved().containsKey(meta.id)) {
                    capabilityTrust.recordIfAbsent(meta.id, surface)
                }
                publish(file, meta, hash, candidate, accepted, hashes, reused = cached != null)
                tracePlugin("accepted ${meta.id} (${file.name})")
            }
            is ValidationResult.Rejected ->
                if (decision.rejection.code == PluginRejectionCode.PERMISSION_DENIED) {
                    // Install is not run. Publish so the management page can list the plugin and
                    // the user can grant the missing permissions; HostComposition.activate is the gate
                    // that decides whether it may actually load. The permission gate is the last
                    // one in the validator, so every trust/content/identity check already passed.
                    publish(file, meta, hash, candidate, accepted, hashes, reused = cached != null)
                    tracePlugin("accepted-with-denied-permissions ${meta.id} (${file.name})")
                } else {
                    errors += "${decision.rejection.code}: ${decision.rejection.message}"
                    // 只打 code 等于把人赶去读 validator：缺哪个依赖、那个依赖现在是什么状态，
                    // 这里其实全都知道。
                    tracePlugin(
                        "rejected ${meta.id} (${file.name}): ${decision.rejection.code}" +
                            " · ${decision.rejection.message}",
                    )
                    if (decision.rejection.code == PluginRejectionCode.DEPENDENCY_MISSING) {
                        candidate.dependencies.filter { it !in accepted.keys }.forEach { dep ->
                            val state = when {
                                pending.any { it.id == dep } ->
                                    "pending（未信任：在插件页确认它，或跑 scripts/plugin.sh deploy）"
                                else ->
                                    "本次没有被接受（不在 mods、被别的规则拒绝，或它自己也缺依赖）"
                            }
                            tracePlugin("  ↳ 依赖 $dep：$state")
                        }
                    }
                }

            is ValidationResult.NeedsUnsignedApproval ->
                pending += PendingUnsigned(meta.id, meta.version, decision.sha256, file.name)
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
        /** True when [source] matched its cached scan, so the private copy is known to be in step. */
        reused: Boolean = false,
    ) {
        val target = files.targetFor(meta.id)
        val installed = target.takeIf { it.isFile }?.let { existing ->
            // 缓存命中且长度相同：私有副本就是同一份，不必再整读一遍。
            if (reused && existing.length() == source.length()) hash else androidModuleHash(existing)
        }
        // 存档只留字节：外部 sidecar 已被新版本覆盖（见 restorePrevious）。
        if (installed != hash) files.publish(target, source, meta.id, hash)

        hashes[meta.id] = hash
        accepted[meta.id] = InstalledPlugin(
            origin = candidate.origin,
            // 重新读一次批准的存储，而不是复用 accept() 开头那份：未签名容器可能刚被批准。
            trust = ev.buan.hostcore.trust.TrustGate.decide(
                origin = candidate.origin,
                signing = candidate.signing,
                sha256 = hash,
                approvedSha256 = approvals.approved()[meta.id],
                hostCertificateSha256 = trust.hostCertificateHashes,
            ),
            id = meta.id,
            shortcuts = meta.shortcuts,
            version = meta.version,
            entryClass = meta.entryClass,
            apiVersion = meta.apiVersion,
            file = target,
            sourceName = source.name,
            signed = candidate.signing is ev.buan.hostcore.validation.SigningState.Signed,
            sha256 = hash,
            dependencies = meta.dependencies,
            separateProcess = meta.separateProcess,
            provides = meta.provides,
            requestedPermissions = meta.permissions.declared
                .mapNotNull(HostPermission::fromWireName)
                .toSet(),
        )
    }

}
