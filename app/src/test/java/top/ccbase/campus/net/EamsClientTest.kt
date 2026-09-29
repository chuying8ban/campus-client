package top.ccbase.campus.net

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.ccbase.campus.domain.activitiesFrom
import top.ccbase.campus.domain.parseActivities
import top.ccbase.campus.domain.summarize

/**
 * 教务客户端（App 自己爬课表）的链路测试。
 *
 * 全程用**假传输层**：不碰真教务、没有任何真实账号密码。
 * 真数据那份用 `eams/print_data.json`（从教务导出的真实课表 JSON）。
 */
class EamsClientTest {

    private class Rec(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `登录用 sha1(salt-密码) 而不是明文_并把 Set-Cookie 收下来`() {
        val seen = mutableListOf<Rec>()
        val t = Transport { m, u, h, b ->
            seen += Rec(m, u, h, b)
            when {
                u.endsWith("/student/login-salt") -> HttpReply(200, "\"SALT9\"")
                u.endsWith("/student/login") -> HttpReply(
                    200, """{"result":true,"message":"ok"}""",
                    mapOf("Set-Cookie" to "SESSION=abc123; Path=/; HttpOnly, __pstsid__=pst999; Path=/"),
                )
                else -> HttpReply(404, "nope")
            }
        }
        val c = EamsClient(t)
        c.login("10000000000", "hunter2")

        val post = seen.last()
        assertEquals("POST", post.method)
        assertTrue("密码不能明文出门", !post.body!!.contains("hunter2"))
        assertTrue("要发 sha1(salt-密码)", post.body!!.contains(sha1("SALT9-hunter2")))
        assertTrue("盐是引号包着的值，要去引号", !post.body!!.contains("SALT9-hunter2\"-"))
        assertEquals("两个 Cookie 都要收（.NET 的 SESSION 和 __pstsid__）",
            setOf("SESSION", "__pstsid__"), c.cookieNames())
    }

    @Test
    fun `拿课表时把会话 Cookie 带上_并且不跟随重定向`() {
        val seen = mutableListOf<Rec>()
        val t = Transport { m, u, h, b ->
            seen += Rec(m, u, h, b)
            when {
                u.endsWith("/student/login-salt") -> HttpReply(200, "\"S\"")
                u.endsWith("/student/login") -> HttpReply(200, """{"result":true}""",
                    mapOf("Set-Cookie" to "SESSION=k1; Path=/, __pstsid__=k2; Path=/"))
                u.contains("/for-std/course-table/semester/82/print-data") ->
                    HttpReply(200, """{"studentTableVms":[{"activities":[]}]}""", mapOf("Content-Type" to "application/json"))
                u.contains("course-table") -> HttpReply(200,
                    "var semesters = JSON.parse('[{\"id\":1,\"abbrEn\":\"2025-2026-1\"},{\"id\":82,\"abbrEn\":\"2026-2027-2\"}]');\n" +
                    "var currentSemester = {\"id\":82,\"abbrEn\":\"2026-2027-2\",\"calendarAssoc\":[{\"id\":1}]};")
                else -> HttpReply(404, "nope")
            }
        }
        val c = EamsClient(t)
        c.login("1", "p")
        val json = c.fetchTimetableJson()

        assertTrue("取到了课表 JSON", json.contains("studentTableVms"))
        val printReq = seen.last()
        assertTrue("课表请求必须带会话 Cookie（教务只认 Cookie，没有 token）",
            printReq.headers["Cookie"]?.contains("SESSION=k1") == true)
        assertTrue("学期 id 要用列表里匹配 abbrEn 的那个，不能抓嵌套对象里的 id=1",
            printReq.url.contains("/semester/82/"))
        assertTrue("Referer 要带（教务认这个）", seen.first { it.url.endsWith("/student/login") }.headers["Referer"]?.contains("login") == true)
    }

    @Test
    fun `会话失效时抛 EamsAuthLost_不能把登录页 HTML 当成课表`() {
        val t = Transport { _, u, _, _ ->
            when {
                u.endsWith("/student/login-salt") -> HttpReply(200, "\"S\"")
                u.endsWith("/student/login") -> HttpReply(200, """{"result":true}""")
                else -> HttpReply(200, "<html>登录页面</html>", mapOf("Content-Type" to "text/html"))
            }
        }
        val c = EamsClient(t)
        c.login("1", "p")
        try {
            c.fetchTimetableJson()
            fail("应该抛 EamsAuthLost")
        } catch (e: EamsAuthLost) {
            assertTrue("错误信息要是人话，别把 HTML 当课表", e.message!!.contains("学期列表") || e.message!!.contains("登录"))
        }
    }

    @Test
    fun `302 跳转算会话失效_按重定向处理而不是当成数据`() {
        val t = Transport { _, u, _, _ ->
            if (u.endsWith("/student/login-salt")) HttpReply(200, "\"S\"")
            else if (u.endsWith("/student/login")) HttpReply(200, """{"result":true}""")
            else HttpReply(302, "", mapOf("Location" to "/student/login"))
        }
        val c = EamsClient(t)
        c.login("1", "p")
        try {
            c.fetchTimetableJson()
            fail("302 应该被识别为会话失效")
        } catch (e: EamsAuthLost) {
            assertTrue(e.message!!.contains("302") || e.message!!.contains("重定向"))
        }
    }

    @Test
    fun `密码错时抛 EamsLoginFailed_并把教务原话带出来`() {
        val t = Transport { _, u, _, _ ->
            if (u.endsWith("/student/login-salt")) HttpReply(200, "\"S\"")
            else HttpReply(200, """{"result":false,"message":"用户名或密码错误"}""")
        }
        val c = EamsClient(t)
        try {
            c.login("1", "wrong")
            fail("应该抛 EamsLoginFailed")
        } catch (e: EamsLoginFailed) {
            assertEquals("用户名或密码错误", e.message)
        }
    }

    @Test
    fun `真实教务 JSON 能取到精确周次与节次（单双周·换机房那门课）`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("eams/print_data.json")!!
            .bufferedReader().readText()
        val acts = activitiesFrom(raw)
        assertTrue("print_data.json 里要有 activities", acts != null && acts.size > 0)
        val parsed = parseActivities(acts!!)
        assertEquals("真实样本里 19 段课", 19, parsed.size)

        // 周五 1-2 节 那两段：同一门课、不同周次、不同教室
        val friday = parsed.filter { it.weekday == 5 && it.startUnit == 1 }
        assertEquals("周五 1-2 节有两段（换机房那门课）", 2, friday.size)
        val sets = friday.map { it.weeks.toSet() }
        assertTrue("两段的周次集合必须不一样（不然就是丢了精确周次）", sets.distinct().size == 2)
        assertTrue("周次必须是稀疏集合，不能是连续区间",
            sets.any { s -> s.isNotEmpty() && (s.max() - s.min() + 1) != s.size })

        // 周几/节次/教室都在，且没有一处需要解析文本
        assertTrue(parsed.all { it.weekday in 1..7 && it.startUnit >= 1 && it.endUnit >= it.startUnit })
        assertTrue("要能看出爬到了什么", summarize(parsed).contains("门课"))
    }
}
