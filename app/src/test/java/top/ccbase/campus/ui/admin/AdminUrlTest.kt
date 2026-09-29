package top.ccbase.campus.ui.admin

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 后台页地址怎么拼 —— 纯函数，单独钉住。
 *
 * 令牌走 fragment（不发给服务器）：拼错一个字符，页面就读不到令牌、退回登录页，
 * 而"能打开但每次要重新登录"这种毛病在真机上不容易一眼看出来，所以值得单测。
 */
@RunWith(RobolectricTestRunner::class)
class AdminUrlTest {

    @Test
    fun `有令牌时把令牌塞进 fragment`() {
        assertEquals(ADMIN_URL + "#token=abc123", adminUrlFor("abc123"))
    }

    @Test
    fun `没令牌就不带 fragment_页面自己会显示登录框`() {
        assertEquals(ADMIN_URL, adminUrlFor(null))
        assertEquals(ADMIN_URL, adminUrlFor(""))
        assertEquals(ADMIN_URL, adminUrlFor("   "))
    }

    @Test
    fun `令牌里的特殊字符要编码_否则 fragment 会被截断`() {
        val tricky = "a b/c+d=e&f"
        val u = adminUrlFor(tricky)
        assertTrue("井号以外的分隔符必须编码：$u", !u.substringAfter("#token=").contains('&'))
        // 页面侧用 decodeURIComponent 还原，必须拿回原值
        assertEquals(tricky, Uri.decode(u.substringAfter("#token=")))
    }

    @Test
    fun `永远跟随构建时的站点地址_不接受外部地址`() {
        assertTrue(adminUrlFor("t").startsWith(ADMIN_URL))
        assertTrue(ADMIN_URL.startsWith("https://"))
    }
}
