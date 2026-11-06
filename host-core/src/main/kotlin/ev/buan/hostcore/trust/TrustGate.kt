package ev.buan.hostcore.trust

import ev.buan.hostcore.validation.SigningState

/**
 * 容器从哪来。
 *
 * 这不是插件能自己声明的字段：`assets` 里的容器是随 APK 一起发出去的，`EV/mods` 里的容器
 * 是谁都能写的。两者能担保的东西完全不同，所以它必须是宿主在扫描时记下来的事实。
 */
enum class PluginOrigin {
    /** 宿主 APK 的 assets 里带着的容器：内容完整性由 APK 自身的签名担保。 */
    BUILT_IN,

    /** 外部投放目录或用户手动导入的文件。 */
    EXTERNAL,
}

/**
 * 规则 §二「信任判定」的三档。
 *
 * 三档说的不是「插件好不好」，而是**它能跑在哪个执行环境里**：可信同进程，受限要隔离，
 * 不可信不跑。判定输入只有宿主拿得到的事实，插件无法参与。
 */
enum class TrustTier {
    /** 随包内置，或签名与宿主一致 → 同进程。 */
    TRUSTED,

    /** 用户确认过这批字节的第三方容器 → 隔离执行，只经 broker 调用宿主服务。 */
    RESTRICTED,

    /** 来源或签名不明 → 默认拒绝。判定缺失时按这一档处理。 */
    UNTRUSTED,
}

/**
 * 一档，加一句为什么。
 *
 * 理由要能直接念给人听：界面上只留一个标签的话，「受限」和「不可信」看起来一样，
 * 而这两件事该做的事完全不同——一件是"去隔离它"，一件是"别让它跑"。
 */
data class TrustDecision(
    val tier: TrustTier,
    val reason: String,
) {
    /** 同进程的资格。**不只是**权限问题：受限档位同进程运行等于没有隔离。 */
    val mayShareProcess: Boolean get() = tier != TrustTier.UNTRUSTED
}

/**
 * 信任判定：纯函数，同样的输入永远同样的输出，不碰存储、不碰时间、不读文件。
 *
 * 三个事实决定档位，顺序也是它们的优先级：
 *
 *  1. **来源**——内置容器的完整性已经由宿主 APK 的签名担保，不需要第二份签名；
 *  2. **签名**——外部容器签名与宿主一致，说明是我们自己的构建产物；
 *  3. **用户的批准**——前两条都不成立时，唯一能升级的路径是人确认过这批字节（按 sha256，
 *     不是按 id：批准的是字节，换了字节就不再是同一个东西）。
 *
 * 都不成立就是不可信。**没有"插件自称可信"这条路**：这里的所有参数都由宿主测量得到。
 */
object TrustGate {

    /**
     * @param origin 容器从哪来，宿主扫描时判定。
     * @param signing 容器的签名事实；jar / dex 没有 Android 签名，是 `SigningState.Unsigned`。
     * @param sha256 容器当前的字节摘要。
     * @param approvedSha256 用户为这个 id 批准过的摘要；null 表示没批准过任何版本。
     * @param hostCertificateSha256 宿主自己的证书摘要集合。
     */
    fun decide(
        origin: PluginOrigin,
        signing: SigningState,
        sha256: String,
        approvedSha256: String?,
        hostCertificateSha256: Set<String>,
    ): TrustDecision {
        // 内置优先于签名：assets 里的东西改一个字节，APK 签名就已失效，再验一次容器签名
        // 只是把同一件事做两遍。它也是唯一能让"随包内置、签名通过"这句话成立的读法——
        // 插件容器是 jar 时根本没有自己的签名可验。
        if (origin == PluginOrigin.BUILT_IN) {
            return TrustDecision(TrustTier.TRUSTED, "随包内置，内容由宿主 APK 的签名担保")
        }
        val approved = approvedSha256 != null && approvedSha256.equals(sha256, ignoreCase = true)
        return when (signing) {
            is SigningState.Signed -> when {
                // 空证书集不是"没签名"，是签坏了：既不能拿来比对，也不该被批准放行。
                signing.certificateSha256.isEmpty() ->
                    TrustDecision(TrustTier.UNTRUSTED, "签名信息为空，无法判定来源")

                signing.certificateSha256.any { it in hostCertificateSha256 } ->
                    TrustDecision(TrustTier.TRUSTED, "外部导入，签名与宿主一致")

                approved ->
                    TrustDecision(TrustTier.RESTRICTED, "第三方签名，你确认过这批字节")

                else ->
                    TrustDecision(TrustTier.UNTRUSTED, "签名与宿主不一致（第三方证书），拒绝执行")
            }

            SigningState.Unsigned -> when {
                approved -> TrustDecision(TrustTier.RESTRICTED, "未签名，你确认过这批字节")
                else -> TrustDecision(TrustTier.UNTRUSTED, "未签名，尚未确认")
            }
        }
    }

    /** 界面上的一个字，用在卡片的一眼层。 */
    fun label(tier: TrustTier): String = when (tier) {
        TrustTier.TRUSTED -> "可信"
        TrustTier.RESTRICTED -> "受限"
        TrustTier.UNTRUSTED -> "不可信"
    }

    /** 标签下面那句话：这一档在运行时意味着什么。 */
    fun meaning(tier: TrustTier): String = when (tier) {
        TrustTier.TRUSTED -> "与宿主同进程运行"
        TrustTier.RESTRICTED -> "确认过的第三方容器：隔离执行，只经 broker 调用宿主服务"
        TrustTier.UNTRUSTED -> "来源或签名不明：默认拒绝，不会被执行"
    }
}
