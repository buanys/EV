package ev.buan

import android.content.Context
import ev.buan.hostcore.trust.CapabilitySurface

/**
 * 未签名插件的"能力面"记录与"信任后续版本"开关（ADR-0008）。
 *
 * 与 [UnsignedApprovalStore] 分开一个 SharedPreferences 文件是有意的：那个类用
 * `preferences.all` 当作 `id -> sha256` 表读，往同一个文件里加 `perms:xxx` 这类键会被它
 * 当成一个插件 id，顺带把信任表读脏。分开就没有这条隐式耦合。
 *
 * 默认值是关键：没有记录 = 没有可比对的能力面，[trustsUpdates] 默认 false。
 * 两件事都意味着"回到问人"，与这个类出现之前完全一致。
 */
class CapabilityTrustStore(context: Context) {

    private val preferences = context.getSharedPreferences("ev.trust", Context.MODE_PRIVATE)

    /** 上次批准时记下的能力面；从没记录过返回 null。 */
    fun surface(id: String): CapabilitySurface? {
        val permissions = preferences.getStringSet(KEY_PERMISSIONS + id, null) ?: return null
        val dependencies = preferences.getStringSet(KEY_DEPENDENCIES + id, null).orEmpty()
        // 取出来的是 SharedPreferences 持有的引用，复制一份再用，避免调用方改到它。
        return CapabilitySurface(permissions.toSet(), dependencies.toSet())
    }

    /** 覆盖记录。只在"通过"的路径上调用——被拒绝的扩权不能写进去。 */
    fun record(id: String, surface: CapabilitySurface) {
        preferences.edit()
            .putStringSet(KEY_PERMISSIONS + id, HashSet(surface.permissions))
            .putStringSet(KEY_DEPENDENCIES + id, HashSet(surface.dependencies))
            .commit()
    }

    /**
     * 只在没有记录时写入。
     *
     * 人工批准一个插件后，下一次启动它会因为 sha256 匹配直接通过、走不到 pending 分支，
     * 那时才补记它的能力面——所以这里是 "if absent"，不能覆盖掉一次扩权的比对基准。
     */
    fun recordIfAbsent(id: String, surface: CapabilitySurface) {
        if (surface(id) == null) record(id, surface)
    }

    /** 用户是否为这个 id 打开了「信任后续版本」。默认关。 */
    fun trustsUpdates(id: String): Boolean =
        preferences.getBoolean(KEY_TRUST + id, false)

    fun setTrustsUpdates(id: String, trust: Boolean) {
        preferences.edit().putBoolean(KEY_TRUST + id, trust).commit()
    }

    /**
     * 撤回整条链（ADR-0008 决策 2）：能力面、开关、以及 [UnsignedApprovalStore] 里的
     * 字节批准都由调用方一起清掉。这里只负责自己这三个键。
     */
    fun forget(id: String) {
        preferences.edit()
            .remove(KEY_PERMISSIONS + id)
            .remove(KEY_DEPENDENCIES + id)
            .remove(KEY_TRUST + id)
            .commit()
    }

    private companion object {
        const val KEY_PERMISSIONS = "perms:"
        const val KEY_DEPENDENCIES = "deps:"
        const val KEY_TRUST = "trust:"
    }
}
