package ev.buan.isolation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.runBlocking

/**
 * 受限插件的执行进程（`android:process=":plugin"`）。
 *
 * 三个动作，全部同步：**装一个插件**、**调一个工具**、**卸一个插件**。宿主进程只通过这个
 * binder 跟它说话，没有第二条路。
 *
 * 同步是为了对上 `Binder.transact` 的语义：调用方本来就拿着一个阻塞的线程（工具调用在 IO
 * 调度器上），在这里 `runBlocking` 不会卡住任何 UI。反过来做成异步的，就要在宿主侧再造一套
 * 请求/应答的对应关系，而收益只是"少阻塞一个已经阻塞的线程"。
 *
 * 进程死掉时 `onDestroy` 不保证被调用（被 OOM 杀掉就是没有），所以这里**不做清理承诺**：
 * 宿主那边靠 `DeathRecipient` 记一次失败，下次启动重新装。
 */
class PluginHostService : Service() {

    private val runtime by lazy { ChildPluginRuntime(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = Bridge()

    override fun onDestroy() {
        // 正常退出（用户停用、宿主解绑）时把插件全部拆掉：dex 的类加载器和 Fiber 都还在
        // 这个进程里，不解只有两种结局——泄漏，或者下次绑定时跟新实例抢同一个插件。
        // 这里阻塞是刻意的：进程就快没了，异步拆等于没拆。
        runCatching { runBlocking { runtime.unloadAll() } }
        super.onDestroy()
    }

    private inner class Bridge : android.os.Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(PluginHostProtocol.DESCRIPTOR)
            val answer = reply ?: return true
            when (code) {
                PluginHostProtocol.LOAD -> {
                    val id = data.readString().orEmpty()
                    val path = data.readString().orEmpty()
                    val entryClass = data.readString().orEmpty()
                    val version = data.readString().orEmpty()
                    val sha256 = data.readString().orEmpty()
                    val storageGranted = data.readInt() == 1
                    val outcome = runCatching {
                        runBlocking {
                            runtime.load(id, path, entryClass, version, sha256, storageGranted)
                        }
                    }
                    answer.writeInt(
                        if (outcome.isSuccess) PluginHostProtocol.STATUS_OK else PluginHostProtocol.STATUS_ERROR,
                    )
                    val specs = outcome.getOrNull()
                    if (specs != null) {
                        PluginHostProtocol.writeSpecs(answer, specs)
                    } else {
                        answer.writeString(describe(outcome.exceptionOrNull()))
                    }
                    return true
                }

                PluginHostProtocol.INVOKE -> {
                    val id = data.readString().orEmpty()
                    val tool = data.readString().orEmpty()
                    val arguments = data.readString().orEmpty()
                    val outcome = runCatching {
                        runBlocking { runtime.invoke(id, tool, arguments) }
                    }
                    answer.writeInt(
                        if (outcome.isSuccess) PluginHostProtocol.STATUS_OK else PluginHostProtocol.STATUS_ERROR,
                    )
                    val result = outcome.getOrNull()
                    if (result != null) {
                        PluginHostProtocol.writeResult(answer, result)
                    } else {
                        answer.writeString(describe(outcome.exceptionOrNull()))
                    }
                    return true
                }

                PluginHostProtocol.UNLOAD -> {
                    val id = data.readString().orEmpty()
                    val outcome = runCatching { runBlocking { runtime.unload(id) } }
                    answer.writeInt(
                        if (outcome.isSuccess) PluginHostProtocol.STATUS_OK else PluginHostProtocol.STATUS_ERROR,
                    )
                    answer.writeString(outcome.exceptionOrNull()?.let(::describe).orEmpty())
                    return true
                }

                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    /**
     * 失败原因要指到类名/方法名那一层：`InvocationTargetException` 这种包装类型对读日志的人
     * 没有任何用处，真正能照做的是它里面那句 "ClassNotFoundException: xxx"。
     */
    private fun describe(error: Throwable?): String {
        if (error == null) return "未知错误"
        return generateSequence(error) { it.cause }
            .joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
    }
}
