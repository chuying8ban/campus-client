package top.ccbase.campus.school

/**
 * 教务会话 Cookie 的两条**纯规则**（不碰 Android，可以直接单测）。
 *
 * ① 认会话：`SESSION` / `__pstsid__` 有非空值 = 这一刻是登录状态，没登录时一定读不到课表，
 *    所以界面上的「读取课表」按钮只在这条为真时出现。
 * ② 保留会话：浏览器默认把会话 Cookie 当"关掉就没"的临时物件，重进内置浏览器就又回到登录页。
 *    把同一条 cookie 改写成**带过期时间**的持久 cookie，服务端会话没过期就一直自动登录 ——
 *    全程只需要 Cookie，不需要密码，App 至今仍然不保存密码。
 */
object SchoolCookieRules {

    /** 教务把会话挂在这两个名字上；两个都要认。 */
    val SESSION_NAMES = listOf("SESSION", "__pstsid__")

    /** 从 `name=value; other=x` 这种 Cookie 头里取某个名字的值；没有/为空则 null。 */
    fun valueOf(header: String?, name: String): String? {
        header.orEmpty().split(";").forEach { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq <= 0) return@forEach
            if (p.substring(0, eq).trim() == name) {
                val v = p.substring(eq + 1).trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    /** 这个 Cookie 头里有没有任一已知会话 cookie（= 已登录）。 */
    fun hasSession(header: String?): Boolean = SESSION_NAMES.any { valueOf(header, it) != null }

    /**
     * 把 `SESSION=abc` 构造成一条持久 cookie：保留原来的名字与值，只补上路径、
     * 有效期与 Secure（教务全程 HTTPS，非 HTTPS 也不会接受它）。
     *
     * @param expiresRfc1123 `Wed, 16 Nov 2026 00:00:00 GMT`
     */
    fun persistent(cookie: String, path: String, maxAgeSeconds: Int, expiresRfc1123: String): String =
        "${cookie.trim()}; Path=$path; Max-Age=$maxAgeSeconds; Expires=$expiresRfc1123; Secure"
}
