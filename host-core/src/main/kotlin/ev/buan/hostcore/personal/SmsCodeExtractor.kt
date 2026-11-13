package ev.buan.hostcore.personal

/**
 * 从短信正文里挑验证码。
 *
 * 抽成纯函数是因为这条规则**最容易写错、也最难在真机上验证**：
 * 一条短信里同时有订单号、手机号、验证码是常态，而"挑错一个"的后果是
 * 模型拿着错误的数字去登录，报错信息还指不到这里。
 *
 * 两条规则：
 *
 * 1. **候选必须是 4–8 位连续数字，两侧不能紧邻数字。**
 *    `\b` 在这里不可靠——中文与数字之间没有单词边界，"订单号1234567请查收"要能匹配，
 *    "13800138000"（11 位手机号）必须不能。
 * 2. **带关键词的短信优先。** "您的验证码是 123456" 比一条恰好含 6 位数字的营销短信
 *    更可能是答案，即使后者更晚到。
 */
object SmsCodeExtractor {

    /** 4 到 8 位连续数字，前后都不能是数字。 */
    private val CANDIDATE = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "确认码", "口令",
        "code", "verification", "otp", "passcode",
    )

    /** 单条短信里的第一个候选码；没有就是 null。 */
    fun extract(body: String): String? =
        CANDIDATE.find(body)?.groupValues?.get(1)

    /**
     * 按"关键词优先、其次顺序"从多条正文里挑一个。
     *
     * [bodies] 的先后顺序有意义：调用方按时间倒序传进来，就是"最新的优先"。
     * 关键词只在**同一轮**里加权——一条三天前的"验证码 111111"不该压过一条
     * 刚到的裸数字短信，所以调用方负责先把时间窗口收窄。
     */
    fun pick(bodies: List<String>): String? {
        val keyworded = bodies.filter { body -> KEYWORDS.any { body.contains(it, ignoreCase = true) } }
        return (keyworded + bodies).firstNotNullOfOrNull { extract(it) }
    }
}
