package ev.buan

/**
 * 运维：信任与授权。
 *
 * 这一层只做一件事——改存储，然后把新的快照交回去。它**不**碰运行期状态
 * （`installed`、`active` 那些 map），因为"改了存储"和"现在就要生效"是两件事：
 * 前者是用户的决定，后者是装配的职责（见 ADR-0002 不变量 1）。
 *
 * 目前装进来的是三个纯存储操作。启用/停用、权限、卸载、清存储仍在 HostRuntime 里，
 * 因为它们顺手改了运行期 map——那些要按"改存储 → 发信号 → 重扫"重写，属于下一刀。
 */
internal class HostOps(
    private val approvals: UnsignedApprovalStore,
    private val capabilityTrust: CapabilityTrustStore,
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
        // 撤销信任时一并忘掉这张能力面记录：否则下次批准会继承上一版的"信任后续版本"，
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
}
