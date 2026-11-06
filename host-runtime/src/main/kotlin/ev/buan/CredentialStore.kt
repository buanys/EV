package ev.buan

import ev.buan.hostcore.plugin.PluginId
import org.json.JSONObject
import java.io.File

/**
 * 凭据的实际存放地：`files/secrets/<pluginId>.json`，一个插件一个文件。
 *
 * 为什么不是每个凭据一个文件：凭据数量很少（几个模型 Provider），一个插件一份 JSON 让
 * "列出有哪些"和"整体替换"都变成一次读写，也不会在目录里留下一串猜不出用途的散件。
 *
 * 为什么放宿主目录而不是插件存储区：插件的存储会随"清理"被删、随重装被换，
 * 而密钥属于用户，不该跟插件的运行时状态共用生命周期。放置位置本身就是隔离——
 * 插件没有这个路径，只能经 [CredentialServiceImpl] 按名字要。
 *
 * 这一层是**纯文件逻辑**，不认识插件、不检查权限，因此可以脱离 Android 单测。
 */
internal class CredentialStore(private val root: File) {

    fun names(pluginId: String): List<String> = readAll(pluginId).keys().asSequence().sorted().toList()

    fun read(pluginId: String, name: String): String? = readAll(pluginId).optString(name).takeIf { it.isNotEmpty() }

    fun write(pluginId: String, name: String, value: String) {
        require(name.isNotBlank()) { "凭据名不能为空" }
        require(value.isNotEmpty()) { "凭据值不能为空；要删除请用 erase" }
        val all = readAll(pluginId)
        all.put(name, value)
        writeAll(pluginId, all)
    }

    fun erase(pluginId: String, name: String) {
        val all = readAll(pluginId)
        if (!all.has(name)) return
        all.remove(name)
        if (all.length() == 0) fileFor(pluginId).delete() else writeAll(pluginId, all)
    }

    /** 删掉一个插件的全部凭据（卸载时用）。 */
    fun eraseAll(pluginId: String) {
        fileFor(pluginId).delete()
    }

    private fun fileFor(pluginId: String): File {
        root.mkdirs()
        return File(root, "${PluginId.fileName(pluginId)}.json")
    }

    /**
     * 读不出来的文件当成"没有凭据"，不是错误。
     *
     * 一个损坏的 JSON 不该让插件激活失败——最坏情况是用户重新填一次 Key，
     * 而让整个模型服务起不来是更糟的结果。
     */
    private fun readAll(pluginId: String): JSONObject {
        val file = fileFor(pluginId)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
    }

    private fun writeAll(pluginId: String, all: JSONObject) {
        val file = fileFor(pluginId)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(all.toString())
        // 原子替换：写一半的凭据文件比没有更糟。同目录 rename 在同一文件系统上是原子的。
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "无法写入凭据文件 $file" }
        }
        // app 私有目录本来就是同 UID 之外读不到；这里只是把"同 UID 内的其他进程"再挡一层。
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
    }
}
