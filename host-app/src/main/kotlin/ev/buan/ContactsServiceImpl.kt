package ev.buan

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.api.Contact
import org.cordis.api.ContactField
import org.cordis.api.ContactsService
import org.cordis.api.HostPermission
import org.cordis.api.PermissionService

/**
 * `ContactsContract` 的实现：宿主侧的通讯录读取。
 *
 * 三件必须做对的事：
 *
 *  1. **两次查询，不是一次**。姓名在 `Contacts` 表，号码和邮箱在两张 `Data` 视图里；
 *     想一次 JOIN 出来要么写 raw SQL，要么拿到重复行。分两次更直白。
 *  2. **LIMIT 不能全靠 SQL**。`ContactsContract` 的 `sortOrder` 支持 `LIMIT` 只在部分 ROM 上成立，
 *     所以匹配完就按游标位置截断，不赌 ROM 行为。
 *  3. **每次调用都复核授权**：撤权之后已经拿到实例的插件不该还能继续读（与日历、凭据同一规则）。
 *
 * 只读是硬边界：宿主没有声明 `WRITE_CONTACTS`，这里也就不提供写入。
 */
internal class ContactsServiceImpl(
    private val context: Context,
    private val permission: PermissionService?,
) : ContactsService {

    private val resolver get() = context.contentResolver

    private fun require() {
        if (permission != null && !permission.isGranted(HostPermission.CONTACTS)) {
            throw SecurityException("缺少权限 CONTACTS（在插件管理页授予后重试）")
        }
    }

    override suspend fun search(query: String, limit: Int): List<Contact> =
        withContext(Dispatchers.IO) {
            require()
            val trimmed = query.trim()
            val ids = matchingIds(trimmed, limit)
            if (ids.isEmpty()) return@withContext emptyList()
            val numbers = fields(ids, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            val emails = fields(ids, ContactsContract.CommonDataKinds.Email.CONTENT_URI)
            ids.map { (id, name) ->
                Contact(
                    id = id,
                    name = name,
                    numbers = numbers[id].orEmpty(),
                    emails = emails[id].orEmpty(),
                )
            }
        }

    override suspend fun read(id: Long): Contact? = withContext(Dispatchers.IO) {
        require()
        val name = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(1).orEmpty() else null
        } ?: return@withContext null

        Contact(
            id = id,
            name = name,
            numbers = fields(listOf(id to name), ContactsContract.CommonDataKinds.Phone.CONTENT_URI)[id].orEmpty(),
            emails = fields(listOf(id to name), ContactsContract.CommonDataKinds.Email.CONTENT_URI)[id].orEmpty(),
        )
    }

    /**
     * 按姓名或号码找联系人 id。
     *
     * 号码也进匹配，是因为"打给 138xxxx"和"打给张三"在用户嘴里是同一件事，
     * 而模型手里常常只有号码。
     */
    private fun matchingIds(query: String, limit: Int): List<Pair<Long, String>> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val (selection, args) = if (query.isEmpty()) {
            null to null
        } else {
            val like = "%$query%"
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?" to arrayOf(like)
        }
        val sort = "${ContactsContract.Contacts.DISPLAY_NAME} ASC"

        val byName = collectIds(projection, selection, args, sort, limit)
        if (byName.size >= limit || query.isEmpty()) return byName.take(limit)

        // 名字没凑够再用号码补；`Filter.CONTENT_URI` 能按号码前缀匹配。
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            android.net.Uri.encode(query),
        )
        val byNumber = resolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    add(cursor.getLong(0) to cursor.getString(1).orEmpty())
                }
            }
        }.orEmpty()

        return (byName + byNumber)
            .distinctBy { it.first }
            .take(limit)
    }

    private fun collectIds(
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        sort: String?,
        limit: Int,
    ): List<Pair<Long, String>> = resolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        projection,
        selection,
        args,
        sort,
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext() && size < limit) {
                val name = cursor.getString(1).orEmpty().ifBlank { cursor.getString(0) }
                add(cursor.getLong(0) to name)
            }
        }
    }.orEmpty()

    /** 一次把一批联系人的号码（或邮箱）取回来，按 contact id 分组。 */
    private fun fields(
        ids: List<Pair<Long, String>>,
        uri: android.net.Uri,
    ): Map<Long, List<ContactField>> {
        if (ids.isEmpty()) return emptyMap()
        val isPhone = uri == ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val idColumn = if (isPhone) {
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        } else {
            ContactsContract.CommonDataKinds.Email.CONTACT_ID
        }
        val valueColumn = if (isPhone) {
            ContactsContract.CommonDataKinds.Phone.NUMBER
        } else {
            ContactsContract.CommonDataKinds.Email.ADDRESS
        }
        val typeColumn = if (isPhone) {
            ContactsContract.CommonDataKinds.Phone.TYPE
        } else {
            ContactsContract.CommonDataKinds.Email.TYPE
        }

        val placeholders = ids.joinToString(",") { "?" }
        val selection = "$idColumn IN ($placeholders) AND $valueColumn IS NOT NULL"
        val args = ids.map { it.first.toString() }.toTypedArray()

        return resolver.query(
            uri,
            arrayOf(idColumn, valueColumn, typeColumn),
            selection,
            args,
            null,
        )?.use { cursor ->
            val grouped = mutableMapOf<Long, MutableList<ContactField>>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val value = cursor.getString(1)?.trim().orEmpty()
                if (value.isEmpty()) continue
                val label = if (isPhone) phoneLabel(cursor.getInt(2)) else emailLabel(cursor.getInt(2))
                grouped.getOrPut(id) { mutableListOf() } += ContactField(value, label)
            }
            grouped
        }.orEmpty()
    }

    private fun phoneLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "手机"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "住宅"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "单位"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "主要"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "单位传真"
        else -> "其他"
    }

    private fun emailLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "私人"
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "单位"
        ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "手机"
        else -> "其他"
    }
}
