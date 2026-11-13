package ev.buan

/**
 * `provides` 的语法与校验。
 *
 * 一个容器声明"我能提供什么"，宿主才知道**不装载它也能满足哪些查找**。
 *
 * 形式固定为 `<kind>:<name>`：
 * - `service:<ServiceKey.name>` —— 例如 `service:agent/service`，冒号后是 ServiceKey 的原文
 * - `slot:<slotId>` —— 例如 `slot:chat.composer`
 *
 * 缺省或空数组表示**必须常驻**：启动即激活。这条向后兼容规则是整件事的前提——
 * 老容器没有这个字段，行为必须和以前一模一样。
 *
 * 未知 kind 只丢弃、不报错：以后加 `command:` / `tool:` 时，新容器扔进旧宿主不会炸，
 * 只是那几项不会被当作懒加载条件。
 */
internal object ProvidesSpec {

    const val SERVICE = "service"
    const val SLOT = "slot"

    /** 已知的 kind。新增一种要同时在这里和解析处打开。 */
    val kinds: Set<String> = setOf(SERVICE, SLOT)

    /** name 字符集：ServiceKey 用 `/` 分段，槽位用 `.` 分段，两种都要放行。 */
    private val namePattern = Regex("^[A-Za-z0-9._/-]+$")

    /** sidecar 的键名，也是 APK meta-data 的键名。 */
    const val SIDECAR_KEY = "provides"
    const val APK_META_KEY = "ev.buan.provides"

    data class Parsed(
        /** 规范化后的条目（`kind:name`，去重、保序）。 */
        val items: Set<String>,
        /** 被丢弃的原文，用于在扫描结果里说明"这几项宿主不认识"。 */
        val rejected: List<String>,
    ) {
        val isLazy: Boolean get() = items.isNotEmpty()
    }

    fun parse(raw: List<String>): Parsed {
        val items = linkedSetOf<String>()
        val rejected = mutableListOf<String>()
        for (entry in raw) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0 || colon == trimmed.lastIndex) {
                rejected += trimmed
                continue
            }
            val kind = trimmed.substring(0, colon)
            val name = trimmed.substring(colon + 1)
            if (kind !in kinds || !namePattern.matches(name)) {
                rejected += trimmed
                continue
            }
            items += "$kind:$name"
        }
        return Parsed(items, rejected)
    }

    /** APK 的 meta-data 只能放字符串，用逗号分隔。 */
    fun splitMetaValue(raw: String?): List<String> =
        raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    fun serviceItem(keyName: String): String = "$SERVICE:$keyName"

    fun slotItem(slotId: String): String = "$SLOT:$slotId"
}
