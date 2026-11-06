package ev.buan

import org.cordis.api.HostPermission

/**
 * 运维：信任、授权、启用与权限。
 *
 * 这一层的规矩是 ADR-0002 的第一条不变量——**只改存储，不碰运行期状态**。
 * 改完存储之后要"现在生效"，靠向装配层发信号（[resync] / [reload]），
 * 而不是自己伸手去改 `active`、`failures` 那些 map。
 *
 * 这么分的实际收益：启用/停用走的是和启动时**同一条**同步路径。
 * 以前"启动时算一遍、点开关时另算一遍"，两条路径的差别迟早会被用户撞到
 * （典型症状：停用后插件还在跑，或者启用后依赖它的插件还停在旧状态）。
 */
internal class HostOps(
    private val approvals: UnsignedApprovalStore,
    private val capabilityTrust: CapabilityTrustStore,
    private val writeEnabled: (id: String, enabled: Boolean) -> Unit,
    private val writePermission: (id: String, permission: HostPermission, granted: Boolean) -> Unit,
    private val resync: suspend () -> HostState,
    private val reload: suspend (id: String) -> Unit,
    private val onApproved: (id: String, sha256: String) -> Unit,
    private val snapshot: () -> HostState,
) {

    fun approveUnsigned(id: String, sha256: String): HostState {
        approvals.approve(id, sha256)
        onApproved(id, sha256)
        return snapshot()
    }

    fun revokeUnsignedApproval(id: String): HostState {
        approvals.revoke(id)
        // 撤销信任时一并忘掉这张能力面记录：否则下次批准会继承上一版的「信任后续版本」，
        // 用户以为自己在撤销，实际上只撤销了字节。
        capabilityTrust.forget(id)
        return snapshot()
    }

    /**
     * 打开/关闭某个未签名插件的「信任后续版本」（ADR-0008）。
     *
     * 打开它等于接受"同一能力面下作者可以推新代码而不再问我"。
     */
    fun setTrustUpdates(id: String, trust: Boolean): HostState {
        capabilityTrust.setTrustsUpdates(id, trust)
        return snapshot()
    }

    /**
     * 启用或停用一个插件：写偏好，然后整树重扫。
     *
     * 不在这里 `activate` / `deactivate`：重扫本身就是"谁能跑、按什么顺序"的唯一答案，
     * 顺手把已启用但没加载、已停用但还在跑的两种状态一起修正。
     */
    suspend fun setEnabled(id: String, enabled: Boolean): HostState {
        writeEnabled(id, enabled)
        return resync()
    }

    /**
     * 授予或撤销一项权限：写存储，然后只重载这一个插件。
     *
     * 为什么不是重扫：权限变化影响的是**这个插件自己**能不能跑、跑到什么程度，
     * 不改变依赖拓扑。重载它一个人足够，也让界面上的开关反馈更快。
     */
    suspend fun setPermission(
        id: String,
        permission: HostPermission,
        granted: Boolean,
    ): HostState {
        writePermission(id, permission, granted)
        reload(id)
        return snapshot()
    }
}
