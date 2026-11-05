package ev.buan.hostcore.trust

/**
 * 一个插件向世界要什么：宿主权限 + 它依赖的其它插件 id。
 *
 * 为什么依赖也算：ADR-0003 已经写明权限不是沙箱。插件只要把高权限插件加进 `dependencies`，
 * 就能 lookup 它发布的服务，间接用上它的能力。所以「新增依赖」与「新增权限」等价。
 *
 * 为什么没有 capabilities：sidecar 里没有这个字段（只有 id / version / entryClass /
 * apiVersion / dependencies / permissions），要拿到它得解析 dex 里的 PluginManifest。
 * 这个代价现阶段不值——权限与依赖已经覆盖了扩权的两条实际路径。
 */
data class CapabilitySurface(
    val permissions: Set<String>,
    val dependencies: Set<String>,
) {
    companion object {
        fun of(permissions: Collection<String>, dependencies: Collection<String>): CapabilitySurface =
            CapabilitySurface(permissions.toSet(), dependencies.toSet())
    }
}

/**
 * 一个未签名插件的版本能不能自动继承上一次的人工批准（ADR-0008）。
 *
 * 它只在 [Approve] 时跳过人；其余情况一律回到 `pending`，与这个模型出现之前完全一致。
 */
sealed interface TrustOutcome {
    /** 能力面与上一次批准时一致，且用户为这个 id 开了「信任后续版本」。 */
    data object Approve : TrustOutcome

    /** 需要人重新确认。 */
    data class Ask(val reason: Reason) : TrustOutcome

    enum class Reason {
        /** 第一次见到这个 id：没有可比对的能力面。 */
        FIRST_SIGHT,

        /** 用户没有为这个 id 打开「信任后续版本」。 */
        NOT_OPTED_IN,

        /** 授权面变了：新增/删除权限或依赖。 */
        SURFACE_CHANGED,
    }
}

/** 纯判定函数：同样的输入永远同样的输出，不碰存储、不碰时间。 */
object CapabilityTrust {

    /**
     * @param previous 上次批准时记下的能力面；null 表示这个 id 没有被记录过。
     * @param current 这一次声明的能力面。
     * @param optedIn 用户是否为这个 id 打开了「信任后续版本」。默认应当是 false。
     */
    fun decide(
        previous: CapabilitySurface?,
        current: CapabilitySurface,
        optedIn: Boolean,
    ): TrustOutcome = when {
        previous == null -> TrustOutcome.Ask(TrustOutcome.Reason.FIRST_SIGHT)
        !optedIn -> TrustOutcome.Ask(TrustOutcome.Reason.NOT_OPTED_IN)
        previous != current -> TrustOutcome.Ask(TrustOutcome.Reason.SURFACE_CHANGED)
        else -> TrustOutcome.Approve
    }

    /** 给日志和 UI 用的一句话，说明为什么还要人确认。 */
    fun describe(outcome: TrustOutcome): String = when (outcome) {
        TrustOutcome.Approve -> "能力面未变，自动通过"
        is TrustOutcome.Ask -> when (outcome.reason) {
            TrustOutcome.Reason.FIRST_SIGHT -> "第一次见到这个插件，需要确认"
            TrustOutcome.Reason.NOT_OPTED_IN -> "未开启「信任后续版本」，需要确认"
            TrustOutcome.Reason.SURFACE_CHANGED -> "权限或依赖有变化，需要确认"
        }
    }
}
