package top.ccbase.campus.net

import top.ccbase.campus.net.StudentError

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * 输入是服务端真响应的逐字副本（src/test/resources 下的 grab_status_real.json / grab_catalog_real.json）。
 *
 * 为什么必须用真样本：这里出过一次「假验证」—— 只看 HTTP 200 就以为对了，
 * 结果 `/api/v2/grab/status` 在 App 上一直显示「监控状态无法解析」。
 * 真相是服务端 targets 是 `SELECT *` 出来的**数据库整行**，
 * `auto_submit` 是 INTEGER 0/1，而 App 侧声明成 Boolean → kotlinx.serialization 直接抛异常。
 * HTTP 200 完全挡不住这类错，只有把响应体喂进真解析器才看得见。
 *
 * 样本不联网、不依赖服务端活着：隧道一重启地址就变，测试不能跟着一起死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class GrabApiTest {

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    /** 只回固定正文的假传输层 —— 顺带断言请求确实带上了令牌 */
    private fun apiReturning(body: String, code: Int = 200, seen: MutableList<String> = mutableListOf()) =
        CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, headers, _ ->
                seen += "$method $url ${headers["Authorization"]}"
                HttpReply(code, body)
            },
        )

    private val realStatus = sample("grab_status_real.json")
    private val realCatalog = sample("grab_catalog_real.json")

    /** flt=all 里第一条还没预检的课：probe_ok / probe_reason 都是 SQL NULL */
    private val realUnprobed = sample("grab_catalog_null_probe_real.json")

    /** flt=conf：cn>0 的课，带 clash 明细（撞的是哪门课） */
    private val realConflict = sample("grab_catalog_conflict_real.json")

    // ------------------------------------------------------------ 真响应样本

    @Test
    fun `真实 status 响应必须能解析_这正是那个坏掉的地方`() = runBlocking {
        val r = apiReturning(realStatus).grabStatus("tok")
        assertTrue(
            "真响应解析失败：${(r as? ApiResult.Err)?.message}",
            r is ApiResult.Ok,
        )
    }

    @Test
    fun `真实 status 的每个字段都要对得上`() = runBlocking {
        val s = (apiReturning(realStatus).grabStatus("tok") as ApiResult.Ok).value
        assertEquals("monitor_on", true, s.monitor_on)
        assertEquals("configured", true, s.configured)
        assertEquals("logged_in", true, s.logged_in)
        assertEquals("interval", 20, s.interval)
        assertEquals("last_check", "09:16:23", s.last_check)
        assertEquals("last_error 空串不能被当成 null", "", s.last_error)
        assertEquals("targets 三条", 3, s.targets.size)
        assertEquals("logs 二十条", 20, s.logs.size)
        assertEquals("catalog.total", 526, s.catalog.total)
        assertEquals("catalog.free", 294, s.catalog.free)
        assertEquals("catalog.ok_clean", 73, s.catalog.ok_clean)
        assertEquals("catalog.updated", "2026-09-16 08:24:45", s.catalog.updated)
    }

    @Test
    fun `targets 是真的数据库行_字段逐字对`() = runBlocking {
        val s = (apiReturning(realStatus).grabStatus("tok") as ApiResult.Ok).value
        val t = s.targets.first { it.lesson_id == 318856 }
        assertEquals("系统工程与运筹学", t.course)
        assertEquals("教师一", t.teacher)
        assertEquals(242, t.turn_id)
        assertEquals(110, t.limit_cnt)
        assertEquals(1, t.enabled)
        assertEquals(0, t.grabbed)
        assertEquals("", t.clash_text)
        assertEquals("2026-09-17 01:00:58", t.created_at)
    }

    @Test
    fun `targets 的 auto_submit 是整数 0 而不是 false_这是当初解析崩掉的原因`() = runBlocking {
        // 服务端弹的是 SELECT * 的整行，SQLite 里 auto_submit INTEGER 0/1。
        // 声明成 Boolean 会直接抛 "Unexpected JSON token" —— 界面只能显示"无法解析"。
        assertTrue(
            "样本必须保持服务端的原始形状（0/1），改样本等于把这次事故的证物擦掉",
            realStatus.contains("\"auto_submit\":0") || realStatus.contains("\"auto_submit\": 0"),
        )
        val s = (apiReturning(realStatus).grabStatus("tok") as ApiResult.Ok).value
        assertTrue("0 必须解析成 false", s.targets.all { !it.auto_submit })
    }

    @Test
    fun `auto_submit 将来若改成真布尔也不能崩`() = runBlocking {
        val asBool = realStatus.replace("\"auto_submit\":0", "\"auto_submit\":false")
        val s = (apiReturning(asBool).grabStatus("tok") as ApiResult.Ok).value
        assertFalse(s.targets.first().auto_submit)

        val asOne = realStatus.replace("\"auto_submit\":0", "\"auto_submit\":1")
        val s1 = (apiReturning(asOne).grabStatus("tok") as ApiResult.Ok).value
        assertTrue("1 必须解析成 true", s1.targets.first().auto_submit)
    }

    @Test
    fun `logs 的字段逐字对`() = runBlocking {
        val s = (apiReturning(realStatus).grabStatus("tok") as ApiResult.Ok).value
        val l = s.logs.first()
        assertEquals(79, l.id)
        assertEquals("2026-09-17 01:02:53", l.ts)
        assertEquals("alert", l.level)
        assertTrue("日志正文不能被截断：${l.msg}", l.msg.contains("有位了"))
    }

    @Test
    fun `catalog 的 stats 与 lessons 分开_扁平形状别搞混`() = runBlocking {
        val c = (apiReturning(realCatalog).grabCatalog("tok") as ApiResult.Ok).value
        assertEquals(73, c.total)
        // 样本是按 limit=3 取的，服务端会原样回显请求的 limit（不是 lessons 的条数）
        assertEquals(3, c.limit)
        assertEquals(3, c.lessons.size)
        // stats 是嵌套对象，不是和 lessons 平级的那几个键
        assertEquals(526, c.stats.total)
        assertEquals(73, c.stats.ok_clean)
    }

    @Test
    fun `真实 catalog 行的周次节次教室都在 place 里`() = runBlocking {
        val c = (apiReturning(realCatalog).grabCatalog("tok") as ApiResult.Ok).value
        val l = c.lessons.first { it.lesson_id == 318856 }
        assertEquals(85, l.free)
        assertEquals(25, l.used)
        assertEquals(110, l.limit_cnt)
        assertEquals(1, l.probe_ok)
        assertTrue("place 里含周次", l.place.contains("10~17周"))
        assertTrue("place 里含节次", l.place.contains("8~9节"))
        assertTrue("place 里含教室", l.place.contains("B楼I区212"))
        assertTrue("有余位 + 不冲突 + 预检放行 = 能抢", l.grabbable)
    }

    @Test
    fun `请求确实带上了令牌`() = runBlocking {
        val seen = mutableListOf<String>()
        apiReturning(realStatus, seen = seen).grabStatus("my-token")
        assertTrue("必须带 Bearer 令牌：$seen", seen.single().contains("Bearer my-token"))
        assertTrue("路径必须是 /api/v2/grab/status：$seen", seen.single().contains("/api/v2/grab/status"))
    }

    // ------------------------------------------------------------ 错误分类

    private fun errOf(code: Int, body: String) = runBlocking {
        (apiReturning(body, code = code).grabStatus("tok") as ApiResult.Err)
    }

    @Test
    fun `登录过期是 401 太频繁是 429_不能混成一句`() {
        assertEquals(401, errOf(401, """{"detail":"令牌无效或已过期"}""").code)
        assertEquals(429, errOf(429, """{"detail":"尝试过于频繁"}""").code)
        assertEquals("这不是作者账号", 403, errOf(403, """{"detail":"该功能不对外开放"}""").code)
        assertEquals(503, errOf(503, """{"detail":"抢课模块未部署"}""").code)
    }

    /**
     * 真机截图暴露的坑：**协程被取消被当成了网络故障**。
     *
     * `CancellationException` 是 `Exception` 的子类，原来 `catch (e: Exception)` 把它一起吞了：
     * 离开组合 / 切页 / 参数变化重新拉起 → 界面写一条红色"网络不通"，
     * 而且协程继续往下跑，这条假报错就常驻在页面上（用户看到的是 App 坏了）。
     * 取消不是错误，必须原样抛出去。
     */
    @Test
    fun `协程被取消不等于网络不通`() {
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ ->
                throw CancellationException("The coroutine scope left the composition")
            },
        )
        var propagated = false
        runBlocking {
            try {
                api.grabCatalog("tok")
            } catch (e: CancellationException) {
                propagated = true
            }
        }
        assertTrue("取消必须原样抛出，不能翻译成 ApiResult.Err", propagated)
    }

    /**
     * 第二个真机坑：界面文案里塞了完整网址（40+ 字符的隧道地址 + 查询串），
     * 手机上就是一坨看不懂的东西。地址是给开发者定位用的 → 只进 logcat。
     */
    @Test
    fun `网络故障文案不许塞网址_地址只进 logcat`() {
        ShadowLog.clear()
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ -> throw java.net.ConnectException("连不上") },
        )
        val r = runBlocking { api.grabStatus("tok") } as ApiResult.Err
        val msg = r.message
        // 2026-09-24 定稿：网址/异常类名/真因**都不上屏**，一律只给学生一句话
        assertEquals("界面文案只允许这一句：$msg", StudentError.TEXT, msg)
        assertFalse("界面文案里不能出现网址：$msg", msg.contains("http"))
        val logs = ShadowLog.getLogs()
        assertTrue("地址必须留在 logcat 里，不然没法定位：${logs.size} 条",
            logs.any { it.tag == "StudentError" && it.msg.contains("example.invalid") })
        assertTrue("真实异常类型也必须留在日志里",
            logs.any { it.tag == "StudentError" && it.throwable is java.net.ConnectException })
    }

    @Test
    fun `网络不通是 0 而不是 401`() {
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { _, _, _, _ -> throw java.io.IOException("连不上") },
        )
        val e = runBlocking { api.grabStatus("tok") as ApiResult.Err }
        assertEquals(0, e.code)
    }

    @Test
    fun `403 不许漏出功能名_统一收成一句话`() {
        // 「该功能不对外开放」这类话**不写功能名，但等于宣布"有个隐藏功能存在"** ——
        // 和「别让人知道有这功能」那条红线冲突，所以也收成同一句（与 403 的 code 分开：
        // 客户端逻辑照样能靠 code 判断）。
        val e = errOf(403, """{"detail":"该功能不对外开放"}""")
        assertEquals(403, e.code)
        assertEquals("403 不该透出功能存在：${e.message}", StudentError.TEXT, e.message)
    }

    @Test
    fun `服务端加字段时旧 App 不能崩`() = runBlocking {
        val extra = realStatus.replaceFirst("{", """{"brand_new_field":{"a":1},""")
        assertTrue(apiReturning(extra).grabStatus("tok") is ApiResult.Ok)
    }

    // ------------------------------------------------------------ 同一份契约上的另外两个坑

    @Test
    fun `没预检过的课_probe_reason 是 null_清单也必须解析得动`() = runBlocking {
        // 实测 flt=all 里有 58 条 probe_reason 是 SQL NULL。
        // 声明成非空 String 的话，"全部 / 有余位" 两个档位的整个清单都会解析失败
        // （界面上只显示一句"课程清单无法解析"，跟前一个问题一模一样）。
        assertTrue(
            "样本必须保持 NULL 原样（服务端真的会这么回），改样本等于把证据擦掉",
            realUnprobed.contains("\"probe_reason\":null"),
        )
        val c = apiReturning(realUnprobed).grabCatalog("tok")
        assertTrue("含 null 的清单解析失败：${(c as? ApiResult.Err)?.message}", c is ApiResult.Ok)
        val l = (c as ApiResult.Ok).value.lessons.first()
        assertEquals("通用英语", l.course)
        assertTrue("没预检 = probe_ok 为 null", l.probe_ok == null)
        assertTrue("没预检 = probe_reason 为 null", l.probe_reason == null)
        assertFalse("拿不到预检结论不能当成能抢", l.grabbable)
    }

    @Test
    fun `冲突的课要能解析出撞的是哪门课`() = runBlocking {
        val c = (apiReturning(realConflict).grabCatalog("tok") as ApiResult.Ok).value
        val l = c.lessons.first { it.lesson_id == 317452 }
        assertEquals("流体力学拓展选讲", l.course)
        assertEquals(1, l.cn)
        assertTrue("clash 明细必须解析出来：${l.clash}", l.clash.isNotEmpty())
        assertEquals("通用英语", l.clash.first().course)
        assertEquals(3, l.clash.first().weekday)
        assertEquals("11:25-13:50", l.clash.first().time_text)
        assertEquals("A楼406", l.clash.first().room)
        assertFalse("预检不放行的课不是能抢的", l.grabbable)
    }

    @Test
    fun `冲突样本里没有冲突的行不能被读成有冲突`() = runBlocking {
        val c = (apiReturning(realCatalog).grabCatalog("tok") as ApiResult.Ok).value
        assertTrue("cn=0 的课 clash 必须是空的", c.lessons.all { it.clash.isEmpty() || it.cn > 0 })
        assertTrue(c.lessons.filter { it.cn == 0 }.all { it.clash.isEmpty() })
    }

    @Test
    fun `解析失败_原文进 logcat 而不是上屏`() = runBlocking {
        // 上次那次事故最难的地方是：界面只说"监控状态无法解析"，没人知道是哪个字段。
        // 2026-09-24 定稿后：**字段名不再上屏**，但原文（含字段名）必须落在 logcat 里 ——
        // 排查靠日志，不靠让用户截图。
        ShadowLog.clear()
        val broken = realStatus.replace("\"enabled\":1", "\"enabled\":\"不是数字\"")
        val e = apiReturning(broken).grabStatus("tok") as ApiResult.Err
        assertEquals(-1, e.code)
        assertEquals("界面只给一句话：${e.message}", StudentError.TEXT, e.message)
        assertFalse("字段名不许上屏：${e.message}", e.message.contains("enabled"))
        assertTrue("但原文必须在日志里：${ShadowLog.getLogs().size} 条",
            ShadowLog.getLogs().any { it.tag == "StudentError" })
    }

    // ------------------------------------------------------------ 写接口（自动抢课相关）

    @Test
    fun `加入监控的请求体里带 auto_submit_但是否开启只认服务端回的值`() = runBlocking {
        val seen = mutableListOf<String>()
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, headers, payload ->
                seen += "$method $url ${headers["Authorization"]} $payload"
                // 服务端真实行为：固定把 auto_submit 写成 0（multiuser_api.py 里那一列是写死的）
                HttpReply(200, """{"ok":true,"clash":[],"clash_text":"","auto_submit":false}""")
            },
        )
        val l = GrabLesson(lesson_id = 1, turn_id = 242, course = "系统工程与运筹学",
            teacher = "教师一", limit_cnt = 110)
        val r = (api.grabAddTarget("tk", l, autoSubmit = true) as ApiResult.Ok).value
        assertTrue("请求里必须带上这个意图：${seen.single()}",
            seen.single().contains("\"auto_submit\":true"))
        assertFalse("服务端回 false 就必须是 false —— 不能自己当成功", r.auto_submit)
        assertTrue("路径对不上：${seen.single()}", seen.single().contains("/api/v2/grab/target"))
    }

    @Test
    fun `取消监控是 DELETE 那一条`() = runBlocking {
        val seen = mutableListOf<String>()
        val api = CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, headers, _ ->
                seen += "$method $url ${headers["Authorization"]}"
                HttpReply(200, """{"ok":true,"deleted":1}""")
            },
        )
        assertTrue(api.grabDelTarget("tk", 7) is ApiResult.Ok)
        assertEquals("DELETE https://example.invalid/api/v2/grab/target/7 Bearer tk", seen.single())
    }
}
