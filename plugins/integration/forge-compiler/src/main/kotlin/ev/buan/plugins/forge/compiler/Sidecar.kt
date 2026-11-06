package ev.buan.plugins.forge.compiler

import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成的插件 sidecar。
 *
 * 键名必须是宿主读的那两个：`permissions` / `dependencies`
 * （`PermissionMetadata.JAR_SIDECAR_KEY` / `DependencyMetadata.JAR_SIDECAR_KEY`），
 * **不是** APK 用的 `cordis.permissions` / `cordis.dependencies` 变体。写错不报错，只会静默丢声明。
 */
internal fun sidecarJson(request: ForgeRequest): String = JSONObject()
    .put("id", request.pluginId)
    .put("version", request.version)
    .put("entryClass", request.entryClass)
    .put("apiVersion", FORGED_API_VERSION)
    .put("permissions", JSONArray(request.permissions))
    .put("dependencies", JSONArray(request.dependencies))
    .toString(2)

/**
 * 生成插件声明的契约版本。
 *
 * 固定 1：`apiVersion` 没有随契约变更 bump 是本仓库的已知缺口（根 README「已知缺口」），
 * 在这里假装支持多版本只会让问题更难查。跨版本混装仍旧会 `NoSuchMethodError`。
 */
internal const val FORGED_API_VERSION = 1

/**
 * 容器 id 的形状校验，与宿主 `PluginId.requireValid` 同一套字符集，但**提前**在锻造时失败。
 *
 * 让一个 id 不合法的插件走完编译、dex、落盘，最后在扫描阶段被宿主拒掉，等于把用户的等待
 * 换成一句 install 期的错误；这里失败还能直接告诉模型怎么改。
 */
internal fun pluginIdProblem(pluginId: String): String? = when {
    pluginId.isBlank() -> "插件 id 不能为空"
    !pluginId.matches(Regex("[a-zA-Z0-9_.]+")) -> "插件 id 只能包含字母、数字、下划线和点：$pluginId"
    !pluginId.contains('.') -> "插件 id 需要至少一个点，例如 taotie.forged"
    else -> null
}

/** 入口类与 id 的一致性：sidecar 指到不存在的类，宿主只会在加载时给一句 ClassNotFound。 */
internal fun entryClassProblem(entryClass: String): String? = when {
    entryClass.isBlank() -> "入口类不能为空"
    !entryClass.matches(Regex("[a-zA-Z0-9_.]+")) -> "入口类必须是全限定类名：$entryClass"
    else -> null
}
