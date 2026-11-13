package org.cordis.api

import org.cordis.ServiceKey

/**
 * 通讯录里的一条记录。
 *
 * 号码保留标签（"手机"、"公司"、"家"）而不是只给一串数字：模型据此决定该用哪个，
 * 也只有这样"打给他公司"这种请求才不至于随机挑一个。
 */
data class Contact(
    val id: Long,
    val name: String,
    val numbers: List<ContactField> = emptyList(),
    val emails: List<ContactField> = emptyList(),
)

/** 一个号码或邮箱，带它自己的标签。 */
data class ContactField(val value: String, val label: String)

/**
 * 读取通讯录。**只读**。
 *
 * 写通讯录要 `WRITE_CONTACTS`，宿主没有声明那个权限——所以这里没有 `create`/`update`。
 * 与其提供一个必然抛 `SecurityException` 的方法，不如让它根本不存在：
 * 插件作者看接口就知道边界在哪。
 *
 * 声明 `CONTACTS` 才拿得到绑定；未授权时每次调用抛 `SecurityException`（与日历同一规则）。
 */
interface ContactsService {

    /** 按姓名或号码模糊查找，最多 [limit] 条。空查询返回最近添加的联系人。 */
    suspend fun search(query: String, limit: Int = 20): List<Contact>

    /** 按 id 读一条；不存在返回 null。 */
    suspend fun read(id: Long): Contact?

    companion object {
        val Key = ServiceKey<ContactsService>("host/contacts")
    }
}
