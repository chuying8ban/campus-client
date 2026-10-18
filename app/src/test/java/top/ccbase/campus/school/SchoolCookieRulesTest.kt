package top.ccbase.campus.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动登录」的全部机制就是这两条纯规则 —— 这里逐条钉住。
 *
 * 为什么值得单测：这两条错了的表现是"用户又被打回登录页"，而在手机上看起来
 * 跟"学校会话过期"一模一样，事后根本分不清是谁的问题。
 */
class SchoolCookieRulesTest {

    @Test
    fun `认得两种会话 cookie_空值不算登录`() {
        assertTrue(SchoolCookieRules.hasSession("SESSION=abc123"))
        assertTrue(SchoolCookieRules.hasSession("foo=1; __pstsid__=zz9; bar=2"))
        assertFalse("空值的会话 cookie 等于没登录", SchoolCookieRules.hasSession("SESSION="))
        assertFalse(SchoolCookieRules.hasSession("foo=1; bar=2"))
        assertFalse(SchoolCookieRules.hasSession(null))
        assertFalse(SchoolCookieRules.hasSession(""))
    }

    @Test
    fun `取值按名字精确匹配_不认前缀相同的一串`() {
        assertEquals("v1", SchoolCookieRules.valueOf("SESSION=v1; SESSIONX=v2", "SESSION"))
        assertEquals("前一串名字不同，不当成同一条", "v1", SchoolCookieRules.valueOf("SESSIONX=v2;SESSION=v1", "SESSION"))
        assertNull(SchoolCookieRules.valueOf("SESSIONX=v2", "SESSION"))
        // 值里带等号（base64 常见）必须原样保留，不能被截断
        assertEquals("a=b=c", SchoolCookieRules.valueOf("__pstsid__=a=b=c", "__pstsid__"))
    }

    @Test
    fun `改写成持久 cookie 时保留值与路径_并带上过期时间`() {
        val spec = SchoolCookieRules.persistent(
            cookie = "SESSION=abc123",
            path = "/student",
            maxAgeSeconds = 2_592_000,
            expiresRfc1123 = "Mon, 16 Nov 2026 00:00:00 GMT",
        )
        assertTrue(spec.startsWith("SESSION=abc123;"))
        assertTrue("路径必须和教务下发的一致，否则浏览器不会带着它", spec.contains("Path=/student"))
        assertTrue("没有 Max-Age 就还是会话 cookie，App 一死就没了", spec.contains("Max-Age=2592000"))
        assertTrue(spec.contains("Expires=Mon, 16 Nov 2026 00:00:00 GMT"))
        assertTrue("教务全程 HTTPS", spec.contains("Secure"))
    }
}
