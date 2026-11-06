package ev.buan

import android.content.Context as AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.PluginInstallRequest
import org.cordis.api.PluginInstallResult
import org.cordis.api.PluginInstallService
import org.cordis.api.PluginInstallStatus
import org.json.JSONObject
import ev.buan.hostcore.install.InstallDecision
import ev.buan.hostcore.install.InstallOutcome
import ev.buan.hostcore.install.InstallStatus
import java.io.File

/**
 * 安装契约的宿主实现：搬字节 → 触发一次扫描 → 把结论翻译成契约形状。
 *
 * 这里刻意不做判断。装得成不成由 [InstallDecision] 决定（那部分在 `:host-core` 里可测），
 * 校验由既有的 `PluginValidator` 决定。这个类只负责两件宿主独占的事——写进 `EV/mods`、
 * 以及把这一轮扫描的结果原样交出去。
 *
 * 从 `HostComposition` 搬出来是因为它**唯一实现一个契约**：契约就是它的边界。
 * 留在宿主类里时，读代码的人得先翻过一个 900 行的文件才知道 `PluginInstallService`
 * 到底做了什么；搬出来之后这一句是自足的。
 *
 * 它不回写宿主状态：扫描结果的落账（`syncErrors` / `pendingUnsigned` / `installed`）
 * 由 [rescan] 的主人做，这里只读结论。这条规矩是从一个坑里长出来的——见 [rescan] 的说明。
 */
internal class HostInstallService(
    private val context: AndroidContext,
    private val installer: PluginInstaller,
    private val approvals: UnsignedApprovalStore,
    /**
     * 重新扫一遍 `EV/mods` 并把这轮结果落进宿主状态，返回结论。
     *
     * 为什么不是 `resync()`：那个会顺手停掉"这一轮没扫到"的插件。而安装的调用方本身
     * 就是一颗正在 `apply()` 的插件——一旦它在这轮里被判为不可用，宿主就 dispose 掉它的
     * Fiber，调用方的协程当场被取消，安装断在半路（实测 `JobCancellationException`）。
     * 安装语义不需要动别人，就别动别人。
     */
    private val rescan: suspend () -> PluginScanSummary,
    /** 只激活刚装上的那一颗，不碰其它插件。 */
    private val activateOnly: suspend (String) -> Unit,
    private val isEnabled: (String) -> Boolean,
    private val inSafeMode: () -> Boolean,
    /** 此刻活着的插件 id。激活之后才查，所以是个函数而不是快照。 */
    private val activeIds: () -> Set<String>,
    /** 卸载走宿主既有的那条路（停用 + 删文件 + 清理存储），这里只负责判断"认不认识它"。 */
    private val uninstallKnown: suspend (String) -> Boolean,
) : PluginInstallService {

    override suspend fun install(request: PluginInstallRequest): PluginInstallResult = withContext(Dispatchers.IO) {
        val sidecar = runCatching { JSONObject(request.sidecarJson) }.getOrNull()
        val declaredId = sidecar?.optString("id")?.takeIf { it.isNotBlank() }
        val entryClass = sidecar?.optString("entryClass")?.takeIf { it.isNotBlank() }
        val version = sidecar?.optString("version")?.takeIf { it.isNotBlank() }

        // 形状不对就先拒绝，不碰磁盘：一个 id 不合法的容器写进 mods 只会在下一轮扫描里变成噪音。
        val early = InstallDecision.decide(
            declaredId = declaredId,
            entryClass = entryClass,
            acceptedIds = emptySet(),
            pending = emptyMap(),
            errors = emptyList(),
            active = false,
        )
        if (early.status == InstallStatus.REJECTED && early.code == InstallDecision.CODE_METADATA) {
            return@withContext early.toContract(version = version)
        }
        val id = declaredId!!

        // 落盘：宿主的外部 mods 目录，jar 与 sidecar 同名，扫描才找得到这一对。
        val staging = File(context.cacheDir, "install-staging").apply { mkdirs() }
        val jarFile = File(staging, "$id.jar")
        val jsonFile = File(staging, "$id.json")
        runCatching {
            jarFile.writeBytes(request.jar)
            jsonFile.writeText(request.sidecarJson)
        }.onFailure { error ->
            return@withContext InstallOutcome(
                status = InstallStatus.REJECTED,
                pluginId = id,
                code = "WRITE_FAILED",
                message = "写入暂存区失败：${error.message ?: error::class.simpleName}",
            ).toContract(version = version)
        }
        val importedJar = installer.importExternal(jarFile, "$id.jar", overwrite = true)
        val importedJson = installer.importExternal(jsonFile, "$id.json", overwrite = true)
        runCatching { jarFile.delete(); jsonFile.delete() }
        if (importedJar == null || importedJson == null) {
            return@withContext InstallOutcome(
                status = InstallStatus.REJECTED,
                pluginId = id,
                code = "MODS_UNWRITABLE",
                message = "无法写入 EV/mods：给宿主共享存储权限后再试",
            ).toContract(version = version)
        }

        // 调用方只应在用户对**这一份字节**点头之后传 true；哈希在这里现算，不信调用方给的。
        val sha = androidModuleHash(importedJar)
        if (request.approveUnsigned) approvals.approve(id, sha)

        val summary = rescan()
        if (summary.installedIds.contains(id) && isEnabled(id) && !inSafeMode()) activateOnly(id)

        val outcome = InstallDecision.decide(
            declaredId = id,
            entryClass = entryClass,
            acceptedIds = summary.installedIds,
            pending = summary.pending.associate { it.id to it.sha256 },
            errors = summary.errors.map { error -> error.substringBefore(':', missingDelimiterValue = "") to error },
            active = activeIds().contains(id),
        )
        outcome.toContract(version = version)
    }

    override suspend fun uninstall(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        // 宿主只知道它扫到的和等待批准的那些；别的 id 直接拒绝，不去猜它是不是拼错了。
        uninstallKnown(pluginId)
    }
}

/** 一轮扫描的结论，只带安装契约需要的那几项。 */
internal class PluginScanSummary(
    val installedIds: Set<String>,
    val pending: List<PendingUnsigned>,
    val errors: List<String>,
)

/** host-core 的结论 → 契约形状。两边 enum 刻意分开，映射只发生在这一个地方。 */
internal fun InstallOutcome.toContract(version: String?): PluginInstallResult = PluginInstallResult(
    status = when (status) {
        InstallStatus.INSTALLED -> PluginInstallStatus.INSTALLED
        InstallStatus.NEEDS_APPROVAL -> PluginInstallStatus.NEEDS_APPROVAL
        InstallStatus.REJECTED -> PluginInstallStatus.REJECTED
    },
    pluginId = pluginId,
    version = version,
    sha256 = sha256,
    code = code,
    message = message,
    active = active,
)
