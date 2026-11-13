package ev.buan.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import ev.buan.InstalledPlugin
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolResult
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 宿主这一侧的 broker：绑 `:plugin` 进程，装插件，把远端工具包成宿主进程里的 [Tool]。
 *
 * 三条不变量：
 *
 *  1. **每个插件一条连接**。共享一条连接会让"停用某个插件"变成"整条连接上还有什么"的问题；
 *     现在停用就是 `unload` + `unbind`，与其它插件无关。
 *  2. **工具是代理，不是副本**。`spec` 由远端提供（名字、描述、参数 schema），`invoke` 每次都
 *     过 binder——宿主不缓存任何执行结果，也不替插件做判断。
 *  3. **进程死了要说出来**。`DeathRecipient` 触发时回调 [onDied]，宿主据此记一次失败并把工具
 *     撤下：一个已经不在的工具比一个报错的工具更危险，模型会以为它还能用。
 */
internal class RestrictedBroker(
    private val context: Context,
    /** 子进程消失时的回调；宿主用它记失败、撤工具。在 binder 线程上调用。 */
    private val onDied: (String) -> Unit,
) {

    private val bindings = mutableMapOf<String, Connection>()

    private inner class Connection(val id: String) {
        @Volatile
        var binder: IBinder? = null
        lateinit var callback: ServiceConnection
    }

    /** 装插件并返回它提供的工具。失败抛异常，调用方转成卡片上的一行原因。 */
    suspend fun load(plugin: InstalledPlugin, storageGranted: Boolean): List<Tool> {
        val binder = bind(plugin.id)
        val specs = transact(
            binder = binder,
            code = PluginHostProtocol.LOAD,
            write = { data ->
                data.writeString(plugin.id)
                data.writeString(plugin.file.absolutePath)
                data.writeString(plugin.entryClass)
                data.writeString(plugin.version)
                data.writeString(plugin.sha256)
                data.writeInt(if (storageGranted) 1 else 0)
            },
            read = { reply -> PluginHostProtocol.readSpecs(reply) },
        )
        return specs.map { spec -> RemoteTool(plugin.id, spec) }
    }

    suspend fun unload(id: String) {
        val connection = bindings.remove(id) ?: return
        connection.binder?.let { binder ->
            runCatching {
                transact(
                    binder = binder,
                    code = PluginHostProtocol.UNLOAD,
                    write = { data -> data.writeString(id) },
                    read = { reply -> reply.readString() },
                )
            }
        }
        runCatching { connection.binder?.unlinkToDeath(deathOf(id), 0) }
        runCatching { context.unbindService(connection.callback) }
    }

    /** 一个远端工具在宿主进程里的样子：规范来自子进程，执行也回到子进程。 */
    inner class RemoteTool(private val pluginId: String, override val spec: ToolSpec) : Tool {
        override suspend fun invoke(argumentsJson: String): String = invokeResult(argumentsJson).text

        override suspend fun invokeResult(argumentsJson: String): ToolResult {
            val binder = bindings[pluginId]?.binder
                ?: error("插件 $pluginId 的进程已经不在")
            return transact(
                binder = binder,
                code = PluginHostProtocol.INVOKE,
                write = { data ->
                    data.writeString(pluginId)
                    data.writeString(spec.name)
                    data.writeString(argumentsJson)
                },
                read = { reply -> PluginHostProtocol.readResult(reply) },
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun bind(id: String): IBinder {
        bindings[id]?.binder?.let { if (it.isBinderAlive) return it }
        val connection = Connection(id)
        val connected = CompletableDeferred<IBinder>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    connected.completeExceptionally(IllegalStateException("子进程绑定成功但没有返回 binder"))
                    return
                }
                connection.binder = service
                runCatching { service.linkToDeath(deathOf(id), 0) }
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connection.binder = null
            }

            override fun onBindingDied(name: ComponentName?) {
                connection.binder = null
                onDied(id)
            }
        }
        connection.callback = callback
        bindings[id] = connection
        context.bindService(Intent(context, PluginHostService::class.java), callback, Context.BIND_AUTO_CREATE)
        // 绑定是异步的：等它真的连上，而不是先假设它连上了——那样第一个工具会在调用时才炸。
        return withTimeout(BIND_TIMEOUT_MS) { connected.await() }
    }

    private var deaths = mutableMapOf<String, IBinder.DeathRecipient>()

    private fun deathOf(id: String): IBinder.DeathRecipient = deaths.getOrPut(id) {
        IBinder.DeathRecipient {
            bindings.remove(id)?.let { it.binder = null }
            onDied(id)
        }
    }

    /**
     * 一次同步 binder 调用：写参数 → transact → 读状态 → 读结果。
     *
     * 状态码由 [PluginHostProtocol] 定死（0 成功、1 失败），失败时同一段 payload 是原因字符串
     * ——所以调用方永远拿到"结果或者一句能读的话"，不会拿到半截 Parcel。
     */
    private suspend fun <T> transact(
        binder: IBinder,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T = withContext(Dispatchers.IO) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PluginHostProtocol.DESCRIPTOR)
            write(data)
            if (!binder.transact(code, data, reply, 0)) error("子进程拒绝了请求 $code")
            reply.readInt().let { status ->
                when (status) {
                    PluginHostProtocol.STATUS_OK -> read(reply)
                    PluginHostProtocol.STATUS_ERROR -> error(reply.readString().orEmpty())
                    else -> error("子进程返回了无法识别的状态 $status（可能是协议版本不一致）")
                }
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
