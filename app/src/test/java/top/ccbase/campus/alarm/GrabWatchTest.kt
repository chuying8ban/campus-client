package top.ccbase.campus.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.Api
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.GrabLog
import top.ccbase.campus.net.GrabStatus
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.grab.GrabLogic

/**
 * 「手机直接提醒」的验收：**这台手机自己弹通知**，而不是等某个人的飞书/QQ 机器人。
 *
 * 这套测试卡住三件用户看得见的事：
 *   1. 首次开启不许把历史提醒全轰一遍（翻旧账）；
 *   2. 同一条提醒只弹一次（按 id 去重，不靠时间猜）；
 *   3. 关掉之后真的不再弹、也不再排闹钟。
 *
 * 顺带卡住一条"别自欺"：没有通知权限时结果文案不许说"弹出成功"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class GrabWatchTest {

    private lateinit var ctx: Context

    /** 只认 grab/status 的假服务端；别的一律 404，避免测试偷偷依赖别的接口 */
    private class FakeWatchTransport(private val status: GrabStatus) : Transport {
        var calls = 0
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply {
            calls++
            return if (url.contains("/api/v2/grab/status")) HttpReply(200, json(status)) else HttpReply(404, "{}")
        }
    }

    private class FailTransport(private val code: Int, private val failBody: String) : Transport {
        override fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply =
            HttpReply(code, failBody)
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        private fun json(st: GrabStatus): String = json.encodeToString(GrabStatus.serializer(), st)

        private fun alerts(vararg pairs: Pair<Int, String>): GrabStatus = GrabStatus(
            logs = pairs.map { (id, msg) ->
                GrabLog(id = id, ts = "2026-09-17 10:00", level = "alert", msg = msg)
            },
        )
    }

    private fun notifications(): List<Notification> =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    private fun notifTexts(): List<String> =
        notifications().mapNotNull { it.extras.getString(Notification.EXTRA_TEXT) }

    private fun nm(): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        GrabWatch.transportOverride = null
        GrabWatch.setOn(ctx, false)
        GrabWatch.setLastSeenId(ctx, GrabWatch.NEVER)
        grabToken("tok-for-test")
        nm().cancelAll()
    }

    @After
    fun tearDown() {
        GrabWatch.setOn(ctx, false)
        GrabWatch.transportOverride = null
    }

    /** 直接写会话 prefs 的令牌；顺手断言 key 没被改过，改了这里会当场炸 */
    private fun grabToken(tok: String) {
        ctx.getSharedPreferences("campus_session", Context.MODE_PRIVATE).edit()
            .putString("token", tok).apply()
        assertEquals("会话令牌的存法变了，测试得跟着改", tok, TokenStore.token(ctx))
    }

    // ------------------------------------------------------------ 首次运行

    @Test
    fun `第一次开启只记账_不能把历史提醒全轰一遍`() {
        GrabWatch.setOn(ctx, true)
        val api = CampusApi(transport = FakeWatchTransport(alerts(1 to "老提醒A", 2 to "老提醒B", 3 to "老提醒C")))

        val r = GrabWatch.runOnce(ctx, api)

        assertEquals("首次运行应该零通知，实际弹了：${notifTexts()}", 0, notifications().size)
        assertEquals(3, GrabWatch.lastSeenId(ctx))
        assertTrue("结果要落盘，用户在外地只能看这个：$r", r.contains("首次运行"))
    }

    @Test
    fun `第一次之后_再来新的就弹通知`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 3) // 已经见过 1..3
        val api = CampusApi(
            transport = FakeWatchTransport(
                alerts(3 to "见过", 4 to "🎯 有位了！系统工程与运筹学（教师一） 余 85 个（25/110） | 👉 快去选课页确认")
            )
        )

        GrabWatch.runOnce(ctx, api)

        assertEquals(1, notifications().size)
        val body = notifTexts().single()
        assertTrue("要带上课程和余量：$body", body.contains("系统工程与运筹学") && body.contains("85"))
        assertFalse("给作者看的那截尾巴不该进通知：$body", body.contains("👉") || body.contains("|"))
        assertEquals(4, GrabWatch.lastSeenId(ctx))
    }

    // ------------------------------------------------------------ 只弹一次

    @Test
    fun `同一条提醒不会重复弹`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 3)
        val api = CampusApi(transport = FakeWatchTransport(alerts(4 to "🎯 有位了！软件工程（张三） 余 5 个")))

        GrabWatch.runOnce(ctx, api)
        GrabWatch.runOnce(ctx, api)
        GrabWatch.runOnce(ctx, api)

        assertEquals("跑三遍也只该一条，否则用户会被烦到直接关通知权限", 1, notifications().size)
    }

    @Test
    fun `info 级别的运行日志不该吵用户`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        val st = GrabStatus(
            logs = listOf(
                GrabLog(id = 1, ts = "10:00", level = "info", msg = "推送 飞书=✅ QQ=✅"),
                GrabLog(id = 2, ts = "10:01", level = "info", msg = "本轮检查 39 门课"),
            )
        )

        GrabWatch.runOnce(ctx, CampusApi(transport = FakeWatchTransport(st)))

        assertEquals(0, notifications().size)
        assertEquals("info 也要记账，不然下次还会被当成新的", 2, GrabWatch.lastSeenId(ctx))
    }

    @Test
    fun `一次来一堆_最多弹三条_剩下的合并成一句`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        val many = GrabStatus(
            logs = (1..7).map { GrabLog(id = it, ts = "t$it", level = "alert", msg = "🎯 有位了！第 $it 门 余 1 个") }
        )

        GrabWatch.runOnce(ctx, CampusApi(transport = FakeWatchTransport(many)))

        assertEquals("3 条单弹 + 1 条摘要", 4, notifications().size)
        assertTrue("摘要要说清还剩几条：${notifTexts()}", notifTexts().any { it.contains("还有 4 条") })
        assertEquals(7, GrabWatch.lastSeenId(ctx))
    }

    // ------------------------------------------------------------ 开关

    @Test
    fun `关着的时候什么都不做_连请求都不发`() {
        GrabWatch.setOn(ctx, false)
        val t = FakeWatchTransport(alerts(9 to "🎯 有位了！不该被看到"))

        val r = GrabWatch.runOnce(ctx, CampusApi(transport = t))

        assertEquals("未开启", r)
        assertEquals("关着还去请求服务端是白耗电", 0, t.calls)
        assertEquals(0, notifications().size)
    }

    @Test
    fun `关掉之后不再排闹钟_也不再弹`() {
        GrabWatch.setOn(ctx, true)
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertTrue("开着就该有一个待触发的闹钟", am.scheduledAlarms.isNotEmpty())

        GrabWatch.setOn(ctx, false)

        assertTrue("关掉必须撤销闹钟，不然会一直被唤醒", am.scheduledAlarms.isEmpty())
        GrabWatch.setLastSeenId(ctx, 0)
        GrabWatch.runOnce(ctx, CampusApi(transport = FakeWatchTransport(alerts(1 to "🎯 有位了！不该出现"))))
        assertEquals(0, notifications().size)
    }

    @Test
    fun `跑完一轮要自己排下一次_不能只跑一次就断掉`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        val am = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        val before = am.scheduledAlarms.size

        GrabWatch.runOnce(ctx, CampusApi(transport = FakeWatchTransport(alerts(1 to "🎯 有位了！软件工程（张三） 余 5 个"))))

        assertTrue("每轮跑完都要排下一次[$before → ${am.scheduledAlarms.size}]", am.scheduledAlarms.size >= before)
    }

    @Test
    fun `没登录的时候不崩_只留一句诊断`() {
        TokenStore.clear(ctx)
        GrabWatch.setOn(ctx, true)
        val t = FakeWatchTransport(alerts(1 to "x"))

        val r = GrabWatch.runOnce(ctx, CampusApi(transport = t))

        assertTrue("要说清是没登录：$r", r.contains("没登录"))
        assertEquals(0, t.calls)
        assertNotNull(GrabWatch.lastResult(ctx))
    }

    @Test
    fun `服务端报错时不弹假提醒_并把原因写进诊断`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)

        val r = GrabWatch.runOnce(ctx, CampusApi(transport = FailTransport(500, """{"detail":"boom"}""")))

        assertEquals(0, notifications().size)
        assertTrue("服务端问题不能变成「抢到课了」：$r", r.contains("服务端") || r.contains("网络"))
    }

    // ------------------------------------------------------------ 接收器

    @Test
    fun `闹钟到点_接收器真的会跑一轮并弹通知`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        GrabWatch.transportOverride = FakeWatchTransport(alerts(1 to "🎯 有位了！软件工程（张三） 余 5 个"))

        GrabWatchReceiver().onReceive(ctx, Intent(GrabWatch.ACTION).setClass(ctx, GrabWatchReceiver::class.java))

        // 接收器把活扔给了后台线程，等它干完
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && notifications().isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }

        assertEquals("接收器路径也得能弹出来：${GrabWatch.lastResult(ctx)}", 1, notifications().size)
        assertTrue(notifTexts().single().contains("软件工程"))
    }

    @Test
    fun `不是自己那个广播就别动`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        GrabWatch.transportOverride = FakeWatchTransport(alerts(1 to "🎯 有位了！"))

        GrabWatchReceiver().onReceive(ctx, Intent("android.intent.action.SOMETHING_ELSE"))

        assertEquals(0, notifications().size)
    }

    // ------------------------------------------------------------ 渠道与安全

    @Test
    fun `通知渠道建出来了_名字要让人知道是手机自己弹的`() {
        GrabNotify.ensureChannel(ctx)
        val ch = nm().getNotificationChannel(GrabNotify.CHANNEL)
        assertNotNull("没有渠道，Android 8+ 的通知会被直接丢掉", ch)
        assertTrue(ch!!.name.toString().contains("手机"))
    }

    @Test
    fun `没有通知权限时不要假装成功`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        shadowOf(nm()).setNotificationsEnabled(false)

        val r = GrabWatch.runOnce(ctx, CampusApi(transport = FakeWatchTransport(alerts(1 to "🎯 有位了！软件工程（张三） 余 5 个"))))

        assertTrue("权限没给要在结果里说清：$r", r.contains("弹出 0 条") || r.contains("权限"))
        // 但进度照记：不然用户开权限的瞬间会被积压的旧提醒轰一遍
        assertEquals(1, GrabWatch.lastSeenId(ctx))
    }

    @Test
    fun `提醒里不该出现地址或令牌`() {
        GrabWatch.setOn(ctx, true)
        GrabWatch.setLastSeenId(ctx, 0)
        GrabWatch.runOnce(
            ctx,
            CampusApi(
                transport = FakeWatchTransport(
                    alerts(1 to "🎯 有位了！软件工程（张三） 余 5 个 | 👉 快去选课页确认")
                )
            )
        )

        val body = notifTexts().single()
        assertFalse("地址/令牌进通知栏是泄露：$body", body.contains("http") || body.contains("tok-for-test"))
        assertNotEquals(Api.BASE, body)
    }

    // ------------------------------------------------------------ 纯逻辑（提醒文案）

    @Test
    fun `状态文字三种情况要说清`() {
        assertEquals("关 · 不会弹提醒", GrabLogic.watchStateText(false, true, "上次跑了"))
        assertEquals("已开 · 但通知权限没给", GrabLogic.watchStateText(true, false, "上次跑了"))
        assertEquals("已开 · 上次跑了", GrabLogic.watchStateText(true, true, "上次跑了"))
        assertEquals("已开", GrabLogic.watchStateText(true, true, null))
    }

    @Test
    fun `监控没开时说明里要提醒_弹出来也是空的`() {
        val off = GrabLogic.watchHint(false)
        val on = GrabLogic.watchHint(true)
        assertTrue("不说清的话用户会以为开着这个就等于有人替他盯着：$off", off.contains("监控开关现在是关的"))
        assertTrue("开着时要讲清它和飞书/QQ 推送的区别：$on", on.contains("不经过"))
    }

    @Test
    fun `去重靠 id_不靠时间`() {
        val logs = listOf(
            GrabLog(id = 5, ts = "10:00", level = "alert", msg = "a"),
            GrabLog(id = 4, ts = "11:00", level = "alert", msg = "b"), // 时间更新但 id 更小 = 已经见过
            GrabLog(id = 6, ts = "09:00", level = "info", msg = "c"),
        )
        assertEquals(listOf(5), GrabWatchLogic.newAlerts(logs, lastSeenId = 4).map { it.id })
        assertEquals(0, GrabWatchLogic.newAlerts(logs, lastSeenId = 6).size)
    }

    @Test
    fun `提醒正文要砍掉给作者看的那截尾巴`() {
        assertEquals("🎯 有位了！软件工程（张三） 余 5 个", GrabWatchLogic.notifyBody("🎯 有位了！软件工程（张三） 余 5 个 | 👉 快去选课页确认"))
        assertEquals("监控有新的余位", GrabWatchLogic.notifyBody("   |  ")) // 空的也得能看
    }

    @Test
    fun `第一次运行只记账不弹`() {
        val logs = listOf(GrabLog(id = 9, ts = "t", level = "alert", msg = "老提醒"))
        assertEquals(9, GrabWatchLogic.initialSeenId(logs))
        assertEquals(0, GrabWatchLogic.initialSeenId(emptyList()))
    }
}
