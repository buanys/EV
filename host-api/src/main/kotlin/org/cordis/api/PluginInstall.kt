package org.cordis.api

import org.cordis.ServiceKey

/**
 * 装一个插件容器（jar + sidecar）到宿主。
 *
 * 为什么需要这条契约：插件能把源码编成容器（能力已由 `forge-compiler` 验过），
 * 但它**写不了** `EV/mods`、**跑不了**安装扫描、**激活不了**自己——这三件事都在宿主手里。
 * 没有这条契约，锻造的产物只能停在磁盘上等人工搬运。
 *
 * 这一层刻意很窄：
 *  - 输入是字节 + sidecar 文本，不是文件路径。插件的工作目录对宿主没有意义，
 *    而"让插件指定一个宿主去读的路径"是一个需要额外防守的入口。
 *  - 输出是一个结论，不是宿主的内部状态。调用方要知道的是"成没成、要不要人批、为什么没成"。
 *  - **不越过审批**：未签名容器在宿主策略下必须逐 hash 批准（见 [PluginInstallStatus.NEEDS_APPROVAL]）。
 */
interface PluginInstallService {

    /**
     * 校验 → 落盘到 `EV/mods` → 记录审批 → 扫描 → 激活，一次走完。
     *
     * 失败不抛异常：拒绝和"等人批"都是正常结果，调用方据此继续对话或提示用户。
     */
    suspend fun install(request: PluginInstallRequest): PluginInstallResult

    /**
     * 卸载：停掉插件、删掉它在 `EV/mods` 的容器、清掉启用偏好。
     *
     * 存储区**不删**——那是「清理」的职责，两者混在一起会让卸载变成数据丢失。
     * 返回是否真的移除了一个已知插件；未知 id 不报错，因为是幂等调用。
     */
    suspend fun uninstall(pluginId: String): Boolean

    companion object {
        val Key = ServiceKey<PluginInstallService>("host/plugin-install")
    }
}

/**
 * 一次安装请求。
 *
 * 不是 data class：它带着 [jar] 这段字节，按内容比较既贵又没有意义，
 * 而生成的 `equals` 会给调用方一个"内容相等等于同一次安装"的错误暗示。
 */
class PluginInstallRequest(
    /** 容器本体。写出的文件名由 sidecar 里的 id 决定，不由调用方决定。 */
    val jar: ByteArray,
    /** sidecar 文本。宿主从里面读 id / 入口类 / 版本 / 权限 / 依赖。 */
    val sidecarJson: String,
    /**
     * 调用方是否已经取得用户对**这一份**容器的明确同意。
     *
     * 默认 false，也就是"先装上、停在待批准"：宿主仍然把这份哈希的批准权留给用户，
     * 由人通过插件管理页或调用方自己的确认界面点头。传 true 只应发生在那次点头之后。
     */
    val approveUnsigned: Boolean = false,
)

/** 一次安装的结论。 */
enum class PluginInstallStatus {
    /** 容器已被宿主接受；[PluginInstallResult.active] 说明它此刻是否已经在跑。 */
    INSTALLED,

    /** 未签名容器停在待批准：把 [PluginInstallResult.sha256] 交给用户确认后再装一次。 */
    NEEDS_APPROVAL,

    /** 被宿主拒绝，[PluginInstallResult.code] 是宿主自己的拒绝码，[PluginInstallResult.message] 是原因。 */
    REJECTED,
}

data class PluginInstallResult(
    val status: PluginInstallStatus,
    val pluginId: String,
    val version: String? = null,
    /** 需要批准时给出这一份容器的 sha256——用户批准的就是这个值，不是 id。 */
    val sha256: String? = null,
    val code: String? = null,
    val message: String? = null,
    /** 已安装时：是否已经在这个进程里跑起来。false 通常意味着安全模式或需要重启。 */
    val active: Boolean = false,
)

/**
 * 请求宿主重启进程。
 *
 * 存在的理由只有一个：**替换一个已经加载的容器**。类加载器把旧 dex 握在手里，
 * 就地换文件不会换掉已经在跑的类，只有新进程才能拿到新字节。
 * 首次安装不需要它——[PluginInstallService.install] 会在当前进程里直接激活。
 */
interface HostRestart {
    /**
     * 安排一次进程重启。返回 false 表示当前环境给不出可靠的重启，
     * 调用方必须退化成"提示用户手动重启"，而不是假装成功。
     */
    fun request(reason: String): Boolean

    companion object {
        val Key = ServiceKey<HostRestart>("host/restart")
    }
}
