package top.ccbase.campus.school

import android.content.Context

/**
 * 「更新课表」该从哪个网址开始 —— 一处决定，所有用户同一条路。
 *
 * 2026-10-21 用户原话：「更新课表现在默认进入的还是老的教务系统，你更改为融合门户那个，
 * 要确保每个用户都改动生效，并且把自动登录做好」。
 *
 * 老实现有两个洞，两个洞的表现都像"学校那边出问题"：
 *
 * 1. **会话还有效时直接跳课表页** —— 那条路上压根没经过融合门户；会话一过期，教务就把人
 *    甩到它自己的老登录页（`/student/login`，就是用户说的"老的教务系统"），
 *    而不是统一身份认证。
 * 2. **只持久化了教务的 `SESSION`，没持久化 CAS 票据** —— 进门户那次永远是全新会话，
 *    刚登过也照样要再输一遍密码 + 验证码，这就是"自动登录没做好"。
 *
 * 现在只有一条规则：**一律从融合门户的统一身份认证进**（[SchoolProfile.loginUrl]，
 * 即 `eams/student/sso/login` → `cas.cupk.edu.cn`）。
 *   · CAS 票据还在 → 静默带 ticket 回到教务，一个输入框都不出现；
 *   · 票据没了 → 在统一身份认证页登录一次（有存过账号会自动填表，验证码仍要本人输），
 *     登录成功后票据立刻持久化，之后再进就又是静默。
 *
 * 这条规则不读任何"上次用了哪个入口"的历史状态 —— 老用户升级上来也走门户，这就是
 * 用户要的"每个用户都改动生效"。唯一的例外是界面上那个**备用入口**开关（CAS 抽风时手动切
 * 教务直登），它不是持久状态，退出重进自动回到门户。
 *
 * 再加一条兜底：任何情况下被甩到老登录页，立刻改道门户（每次导入只改道一次，
 * 免得 CAS 自己跳老页面时来回打转）。
 */
object SchoolEntry {

    /** 老教务系统的登录页路径 —— 用户说的"老的教务系统"就是它。 */
    const val OLD_LOGIN_PATH = "/student/login"

    /**
     * 这次从哪开始：永远是融合门户的统一身份认证。
     *
     * 参数留着是为了让调用方和测试都能显式表达"会话/票据在不在"——它们**不影响**入口选择，
     * 只影响 CAS 会不会弹登录页；写成参数而不是读全局状态，是为了让这条规则可被单测钉住。
     */
    @Suppress("UNUSED_PARAMETER")
    fun startUrl(
        profile: SchoolProfile = SchoolRegistry.cupk,
        hasSession: Boolean = false,
        hasTicket: Boolean = false,
    ): String = profile.loginUrl

    /** 按当前设备状态算起始网址（入口仍然是门户）。 */
    fun startUrl(ctx: Context, profile: SchoolProfile = SchoolRegistry.cupk): String = startUrl(profile)

    /** 这个网址是不是老教务登录页（要被改道的那种）。 */
    fun isOldLoginPage(url: String?): Boolean {
        val path = url?.let { runCatching { java.net.URI(it).path }.getOrNull() } ?: return false
        return path.trimEnd('/').equals(OLD_LOGIN_PATH.trimEnd('/'), ignoreCase = true)
    }
}
