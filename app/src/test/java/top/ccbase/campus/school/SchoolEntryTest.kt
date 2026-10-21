package top.ccbase.campus.school

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 「更新课表」的入口与自动登录（2026-10-21 用户报）。
 *
 * 用户原话：「更新课表现在默认进入的还是老的教务系统，你更改为融合门户那个，
 * 要确保每个用户都改动生效，并且把自动登录做好」。
 *
 * 这里钉三件事：
 * 1. 起始网址**永远是**融合门户的统一身份认证，不看任何历史状态；
 * 2. 门户票据（CASTGC）必须被改成持久 cookie —— 缺这一步，CAS 每次都是新会话，
 *    用户每次都要重输密码 + 验证码，"自动登录"等于没做；
 * 3. 用户关掉「记住登录」时，门户票据必须一起删掉，不然下次还是静默进（以为退了其实没退）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchoolEntryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val profile = SchoolRegistry.cupk
    private val cm get() = CookieManager.getInstance()

    @Before
    fun reset() {
        SchoolSession.forget(ctx)
        cm.setCookie("https://eams.cupk.edu.cn/student/", "SESSION=; Max-Age=0; Path=/student/")
        cm.setCookie("https://cas.cupk.edu.cn/cas/", "CASTGC=; Max-Age=0; Path=/cas/")
    }

    @After
    fun clean() = reset()

    // ───────────────── 入口 ─────────────────

    @Test
    fun `不管有没有会话_起始网址都是融合门户`() {
        val expected = profile.origin + "/student/sso/login?targetUrl=/student/home"
        assertEquals("有会话有票据", expected, SchoolEntry.startUrl(profile, hasSession = true, hasTicket = true))
        assertEquals("有会话没票据", expected, SchoolEntry.startUrl(profile, hasSession = true, hasTicket = false))
        assertEquals("什么都没有", expected, SchoolEntry.startUrl(profile, hasSession = false, hasTicket = false))
        assertEquals("入口就是 loginUrl", profile.loginUrl, SchoolEntry.startUrl(profile))
    }

    @Test
    fun `起始网址不再指向老教务登录页`() {
        assertFalse(
            "以前会话有效时会直接进课表页，会话一过期就被甩到老登录页 —— 这正是用户报的那条路",
            SchoolEntry.isOldLoginPage(SchoolEntry.startUrl(profile)),
        )
        assertFalse(SchoolEntry.isOldLoginPage(profile.loginUrl))
    }

    @Test
    fun `认得老登录页_别把带后缀的路径也叫老登录页`() {
        assertTrue(SchoolEntry.isOldLoginPage("https://eams.cupk.edu.cn/student/login"))
        assertTrue(SchoolEntry.isOldLoginPage("https://eams.cupk.edu.cn/student/login/"))
        assertTrue(SchoolEntry.isOldLoginPage("https://eams.cupk.edu.cn/student/login?refer=x"))
        assertFalse("取 salt 的接口不是登录页", SchoolEntry.isOldLoginPage("https://eams.cupk.edu.cn/student/login-salt"))
        assertFalse(SchoolEntry.isOldLoginPage("https://eams.cupk.edu.cn/student/home"))
        assertFalse(SchoolEntry.isOldLoginPage("https://cas.cupk.edu.cn/cas/login"))
        assertFalse(SchoolEntry.isOldLoginPage(null))
    }

    // ───────────────── 自动登录（票据持久化） ─────────────────

    @Test
    fun `门户票据会被改成持久 cookie`() {
        cm.setCookie("https://cas.cupk.edu.cn/cas/", "CASTGC=tk-abc; Path=/cas/")
        assertTrue("先确认票据读得到", SchoolCookie.hasPortalTicket())

        val written = mutableListOf<Pair<String, String>>()
        val ok = SchoolCookie.persistSession(profile, set = { u, v -> written.add(u to v) })
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("有票据就该动手", ok)
        val tgt = written.firstOrNull { it.second.startsWith("CASTGC=tk-abc") }
        assertTrue("票据必须被写回 $written", tgt != null)
        assertTrue("门户路径是 /cas，写错路径浏览器不会带", tgt!!.first.contains("cas.cupk.edu.cn"))
        assertTrue("没有 Max-Age 就还是会话 cookie，进程一死就没了", tgt.second.contains("Max-Age="))
        assertTrue("全程 HTTPS", tgt.second.contains("Secure"))
    }

    @Test
    fun `教务会话同样会被改成持久 cookie_两条都要有`() {
        cm.setCookie("https://eams.cupk.edu.cn/student/", "SESSION=s-1; Path=/student/")
        cm.setCookie("https://cas.cupk.edu.cn/cas/", "CASTGC=tk-abc; Path=/cas/")

        val written = mutableListOf<Pair<String, String>>()
        SchoolCookie.persistSession(profile, set = { u, v -> written.add(u to v) })
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("教务会话", written.any { it.second.startsWith("SESSION=s-1") })
        assertTrue("门户票据 —— 少了这条用户每次都要重输密码+验证码", written.any { it.second.startsWith("CASTGC=tk-abc") })
    }

    @Test
    fun `关掉记住登录_门户票据一起删`() {
        cm.setCookie("https://cas.cupk.edu.cn/cas/", "CASTGC=tk-abc; Path=/cas/")
        val removed = mutableListOf<Pair<String, String>>()
        SchoolCookie.clearPortal(expire = { u, v -> removed.add(u to v) })
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("删的必须是 CAS 那一份", removed.isNotEmpty() && removed.all { it.first.contains("cas.cupk.edu.cn") })
        assertTrue("要删 CASTGC", removed.any { it.second.startsWith("CASTGC=;") })
        assertTrue("带 Max-Age=0 才算真删", removed.all { it.second.contains("Max-Age=0") })

        // 真机上这一发就把 Cookie 删掉了；Robolectric 的 CookieManager 会把带
        // Max-Age=0 的 Set-Cookie 直接丢弃、也不去移除已有的那条（已知 shadow 缺陷，
        // 同 SchoolSessionTest 的注释），所以这里只能核"删的报文对不对"，
        // 上面三条断言已经把路径、名字、Max-Age 全钉住了。
    }

    @Test
    fun `没票据时不给假阳性`() {
        assertFalse(SchoolCookie.hasPortalTicket())
        assertFalse(SchoolCookieRules.hasPortalTicket("SESSION=abc"))
        assertFalse(SchoolCookieRules.hasPortalTicket("CASTGC="))
        assertTrue(SchoolCookieRules.hasPortalTicket("CASTGC=x1"))
        assertTrue("兼容旧名", SchoolCookieRules.hasPortalTicket("TGC=x1"))
    }
}
