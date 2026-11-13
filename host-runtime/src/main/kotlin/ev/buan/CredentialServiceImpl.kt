package ev.buan

import org.cordis.api.CredentialService
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService

/**
 * [CredentialService] 的宿主实现：一个插件一份，作用域是它自己的那份凭据文件。
 *
 * 三道门，缺一不可：
 *
 *  1. **声明**：没在元数据里写 `SECRETS` 的插件根本不拿到这个绑定（见 [provideCredentialIfDeclared]），
 *     `ctx[CredentialService.Key]` 是 null；
 *  2. **授权**：声明了但用户没批，每次调用都抛 `SecurityException`——不重启也能在管理页补授权；
 *  3. **归属**：每个实例带着自己的 pluginId，读写只在这个 id 的文件里。
 *
 * 读取留一行审计（名字，不含值）。规则要求"保管和裁决"，裁决的一部分就是"事后能说清谁取过"。
 */
internal class CredentialServiceImpl(
    private val pluginId: String,
    private val store: CredentialStore,
    private val permissions: PermissionService,
) : CredentialService {

    override fun names(): List<String> = checked { store.names(pluginId) }

    override fun read(name: String): String? = checked {
        val value = store.read(pluginId, name)
        if (value != null) audit("read", name)
        value
    }

    override fun write(name: String, value: String) = checked {
        store.write(pluginId, name, value)
        audit("write", name)
    }

    override fun erase(name: String) = checked {
        store.erase(pluginId, name)
        audit("erase", name)
    }

    /** 每次调用都重新查授权：撤销应当立刻生效，而不是等下一次激活。 */
    private inline fun <T> checked(block: () -> T): T {
        permissions.require(HostPermission.SECRETS)
        return block()
    }

    /**
     * 一行审计。**不记值**，只记"谁碰了哪个名字"——日志会被贴进 issue，密钥不该出现在那里。
     */
    private fun audit(action: String, name: String) {
        android.util.Log.i("EV", "credential $action $name by $pluginId")
    }
}

/**
 * 按声明提供凭据服务，与 `providePluginStorageIfDeclared` 同一套规则。
 *
 * 用工厂而不是直接传实例：未声明的插件连实现都不该被构造出来。
 * 返回是否装上了绑定。
 */
internal suspend fun provideCredentialIfDeclared(
    pluginContext: org.cordis.Context,
    requested: Set<HostPermission>,
    credentials: () -> CredentialService,
): Boolean {
    if (HostPermission.SECRETS !in requested) return false
    pluginContext.provide(CredentialService.Key, credentials()).awaitReady()
    return true
}
