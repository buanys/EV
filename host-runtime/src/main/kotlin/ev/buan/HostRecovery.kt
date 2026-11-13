package ev.buan

import ev.buan.hostcore.lifecycle.HostLifecycle

/**
 * 恢复：假设已经有东西坏了，只调用机制的接口。
 *
 * 这一层不实现"停止"和"加载"，它只做三件事：清崩溃标记、改安全模式、把启动状态标回已完成。
 * 真正让插件重新跑起来的是装配层（[applyLaunch]），安全模式本身也由装配层在扫描时读取
 * （见 ADR-0002：恢复只调用机制，不自己实现机制）。
 *
 * 为什么单独拿出来：安全模式是**下一次启动**的策略，不是运行期状态机的一部分。
 * 它住在宿主类里时，任何一次"顺手改 safeMode"都会绕过崩溃标记，两者迟早不一致。
 */
internal class HostRecovery(
    private val crash: CrashStore,
    private val lifecycle: HostLifecycle,
    private val setSafeMode: (SafeModeNotice?) -> Unit,
    private val applyLaunch: suspend () -> Unit,
    private val snapshot: () -> HostState,
) {

    /**
     * 离开安全模式：删崩溃标记、清安全模式、重跑一次装配。
     *
     * 只删 marker；`last.txt` 保留，崩溃仍然可查。如果同一个插件再崩一次，
     * marker 会回来，下一次启动重新进入安全模式——这是"下次启动恢复"，不是进程内监护。
     */
    suspend fun leaveSafeMode(): HostState {
        crash.clearPending()
        setSafeMode(null)
        applyLaunch()
        lifecycle.completeStart(safeMode = false)
        return snapshot()
    }
}
