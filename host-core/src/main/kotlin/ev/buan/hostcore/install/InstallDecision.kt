package ev.buan.hostcore.install

import ev.buan.hostcore.plugin.PluginId

/** 一次安装的结论。与 `org.cordis.api.PluginInstallStatus` 一一对应，宿主在边界上映射。 */
enum class InstallStatus { INSTALLED, NEEDS_APPROVAL, REJECTED }

data class InstallOutcome(
    val status: InstallStatus,
    val pluginId: String,
    val sha256: String? = null,
    val code: String? = null,
    val message: String? = null,
    val active: Boolean = false,
) {
    companion object {
        fun rejected(pluginId: String, code: String, message: String): InstallOutcome =
            InstallOutcome(InstallStatus.REJECTED, pluginId, code = code, message = message)
    }
}

/**
 * 把「宿主扫描结果」翻译成「这次安装成没成」。
 *
 * 抽出来的原因：这段判断是安装流程里唯一一处**会出错又不易复现**的逻辑
 * （拒绝码藏在扫描结果里、待批准和依赖缺失长得一模一样），而它不碰 Android，
 * 所以在 `:host-core` 里能直接测。宿主那一侧只剩下搬字节和调扫描。
 */
object InstallDecision {

    /** sidecar 缺字段或形状不对时用的拒绝码，与容器的校验拒绝码区分开。 */
    const val CODE_METADATA = "METADATA"

    /** 扫描既没接受也没拒绝——通常意味着这一轮扫描压根没看见这个文件。 */
    const val CODE_UNSEEN = "UNSEEN"

    /**
     * @param declaredId sidecar 里读到的 id；JSON 解析失败时为 null
     * @param entryClass sidecar 里的入口类
     * @param acceptedIds 本轮扫描接受的插件 id
     * @param pending 待批准的容器：id → 等待确认的 sha256
     * @param errors 本轮扫描的拒绝项：拒绝码 → 消息
     * @param active 该 id 此刻是否已经在跑
     */
    fun decide(
        declaredId: String?,
        entryClass: String?,
        acceptedIds: Set<String>,
        pending: Map<String, String>,
        errors: List<Pair<String, String>>,
        active: Boolean,
    ): InstallOutcome {
        if (declaredId.isNullOrBlank()) {
            return InstallOutcome.rejected(
                pluginId = "",
                code = CODE_METADATA,
                message = "sidecar 解析失败：缺少 id（需要 {\"id\":\"...\",\"entryClass\":\"...\"} 形状的 JSON）",
            )
        }
        if (!PluginId.isValid(declaredId)) {
            return InstallOutcome.rejected(
                pluginId = declaredId,
                code = CODE_METADATA,
                message = "非法插件 id「$declaredId」：只允许 [A-Za-z0-9][A-Za-z0-9._-]{0,127}",
            )
        }
        if (entryClass.isNullOrBlank()) {
            return InstallOutcome.rejected(
                pluginId = declaredId,
                code = CODE_METADATA,
                message = "sidecar 缺少 entryClass：宿主不知道要加载哪个类",
            )
        }

        // 拒绝优先：一个既能被拒又在待批准列表里的容器，事实是"被拒了"。
        val error = errors.firstOrNull { it.second.contains(declaredId) }
        if (error != null) {
            return InstallOutcome(InstallStatus.REJECTED, declaredId, code = error.first, message = error.second)
        }

        pending[declaredId]?.let { sha ->
            // 待批准也要如实告诉调用方哪一份哈希：批准的是字节，不是 id。
            return InstallOutcome(InstallStatus.NEEDS_APPROVAL, declaredId, sha256 = sha)
        }

        if (declaredId in acceptedIds) {
            return InstallOutcome(InstallStatus.INSTALLED, declaredId, active = active)
        }

        return InstallOutcome.rejected(
            pluginId = declaredId,
            code = CODE_UNSEEN,
            message = "宿主扫描没有接受也没有拒绝这个容器：确认文件已写入 EV/mods 且 sidecar 与 jar 同名",
        )
    }
}
