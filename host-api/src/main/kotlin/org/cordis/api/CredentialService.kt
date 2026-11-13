package org.cordis.api

import org.cordis.ServiceKey

/**
 * 凭据：由宿主保管的密钥，插件只能通过这个接口读写自己的那一份。
 *
 * 为什么必须归宿主：规则里写着"凭据由宿主保管和裁决，插件只拿到经授权的访问结果"。
 * 在此之前，模型的 API key 就躺在插件自己的 `providers.json` 里——那是一个插件随时会写、
 * 会被备份、会被"清空插件数据"顺手删掉的文件。凭据不该和插件的运行时状态用同一个生命周期。
 *
 * **这一层做什么、不做什么**（照例写清楚，免得被当成承诺）：
 *
 *  - **做**：把字节搬到宿主私有目录（`files/secrets/`），插件拿不到路径；读写都走这里，
 *    于是宿主知道谁在什么时候取了哪个名字；未声明 [HostPermission.SECRETS] 的插件根本拿不到
 *    这个服务，声明了但没授权时每次调用都抛 `SecurityException`。
 *  - **不做**：不代理网络请求，不替代 broker。插件拿到明文之后仍然自己发 HTTP——真正的隔离
 *    要等 5c 的子进程 + broker。也**不加密**：Android 的 app 私有目录已经是同 UID 之外读不到，
 *    这一层的价值是"换个柜子 + 一把钥匙"，不是"保险箱"。用 Keystore 加密是后续可选项。
 *
 * 名字是插件自己定的（比如 `provider:<id>`），宿主不认识它的含义，只负责隔离与记账。
 */
interface CredentialService {

    /** 这个插件名下有哪些凭据名（按字典序）。不返回内容。 */
    fun names(): List<String>

    /** 读一个凭据；没有时返回 null。未被授权会抛 [SecurityException]。 */
    fun read(name: String): String?

    /** 写一个凭据（覆盖）。空值会被拒绝——要删请用 [erase]。 */
    fun write(name: String, value: String)

    /** 删一个凭据；不存在时静默返回。 */
    fun erase(name: String)

    companion object {
        val Key = ServiceKey<CredentialService>("host/credential")
    }
}
