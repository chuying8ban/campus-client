package top.ccbase.campus.ui.grab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.ccbase.campus.data.remote.TokenStore
import top.ccbase.campus.net.ApiUser
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.GrabClash
import top.ccbase.campus.net.GrabCatalog
import top.ccbase.campus.net.GrabLesson
import top.ccbase.campus.net.GrabLog
import top.ccbase.campus.net.GrabStats
import top.ccbase.campus.net.GrabStatus
import top.ccbase.campus.net.GrabTarget
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 抢课页测试。
 *
 * 重点不是"渲染出来了"，而是**话说对了没有**：
 *   - 「我们的监控」和「教务登录」必须分开说（合成一句会自相矛盾）
 *   - 开关开着 ≠ 真的在盯（目标被清空后说"监控中"就是假话）
 *   - 清单空了必须说清为什么空（空列表不说话最像坏掉）
 *   - 冲突必须说（抢到了也得退的课，不能等抢完才发现）
 *   - **有冲突的课不许自动抢，而且不能只是灰着不说原因**
 *   - **自动提交默认必须是关的；开启必须经过确认层**（一次点击就开的路径不许存在）
 *
 * ⚠️ 渲染测试必须给 qualifiers：Robolectric 默认窗口只有 320x470 px，
 *    列表只组合视口内的 item，"找不到"经常是环境骗人（这个坑已经踩过一次）。
 *    这一页内容长，窗口要给够高度。
 * ⚠️ 渲染测试喂的是**服务端真响应样本**（src/test/resources），不是手写的假 JSON ——
 *    手写的假 JSON 会把契约错误一起手写进去，就永远测不出"契约对不上"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class GrabTest {

    @get:Rule
    val compose = createComposeRule()

    private val json = Json { ignoreUnknownKeys = true }

    private fun sample(name: String): String =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/$name")

    private val realStatus get() = sample("grab_status_real.json")
    private val realCatalog get() = sample("grab_catalog_real.json")

    /** 真样本解析成对象，供纯逻辑测试造数据用 */
    private fun realS(): GrabStatus = json.decodeFromString(realStatus)
    private fun realC(): List<GrabLesson> = json.decodeFromString<top.ccbase.campus.net.GrabCatalog>(realCatalog).lessons

    /** 会话：没有它 GrabScreen 会走"还没登录"的分支 */
    private fun login() {
        val ctx = RuntimeEnvironment.getApplication()
        TokenStore.save(ctx, "tok", "2099-01-01 00:00:00",
            ApiUser(uid = 1, student_id = "s", name = "n", canGrab = true))
    }

    /**
     * 假传输层：按路径回固定的真样本。
     * `statusSeq` 用来模拟"操作之后状态变了"（比如移除目标后 target 变空）。
     */
    private fun fakeApi(
        statusSeq: List<String> = listOf(realStatus),
        catalog: String = realCatalog,
        byQuery: Map<String, String> = emptyMap(),
        failCatalogs: Int = 0,
        addReply: String = """{"ok":true,"clash":[],"clash_text":"","auto_submit":false}""",
        seen: MutableList<String> = mutableListOf(),
    ): CampusApi {
        var i = 0
        var catalogCalls = 0
        return CampusApi(
            base = "https://example.invalid",
            transport = Transport { method, url, _, payload ->
                seen += "$method $url $payload".trim()
                when {
                    url.contains("/grab/status") ->
                        HttpReply(200, statusSeq[minOf(i++, statusSeq.size - 1)])
                    url.contains("/grab/catalog") -> {
                        // 按 q= 分流：确认层会按课名单独补查一次
                        val raw = Regex("[?&]q=([^&]*)").find(url)?.groupValues?.get(1).orEmpty()
                        val q = java.net.URLDecoder.decode(raw, "UTF-8")
                        // failCatalogs：前 N 次清单请求故意失败，用来验证"旧报错会被清掉"
                        if (++catalogCalls <= failCatalogs) HttpReply(500, """{"detail":"boom"}""")
                        else HttpReply(200, byQuery[q] ?: catalog)
                    }
                    url.contains("/grab/config") -> {
                        // 服务端语义：只改传上来的字段，然后回读真实状态
                        val mo = payload?.contains("\"monitor_on\":true") == true
                        val au = payload?.contains("\"auto_submit\":true") == true
                        HttpReply(200,
                            """{"ok":true,"monitor_on":$mo,"auto_submit":$au,"interval":20}""")
                    }
                    method == "POST" -> HttpReply(200, addReply)
                    method == "DELETE" -> HttpReply(200, """{"ok":true,"deleted":1}""")
                    else -> HttpReply(404, """{"detail":"no"}""")
                }
            },
        )
    }

    private fun st(
        on: Boolean = true, logged: Boolean = true, err: String = "",
        tg: List<GrabTarget> = emptyList(), total: Int = 526, clean: Int = 73,
    ) = GrabStatus(
        configured = true, monitor_on = on, logged_in = logged, last_error = err,
        interval = 20, last_check = "21:46", targets = tg,
        catalog = GrabStats(total = total, free = 294, ok_clean = clean),
    )

    // ---------------------------------------------------------------- 纯逻辑

    @Test
    fun `状态行把监控和教务登录分开说`() {
        val s = GrabLogic.statusLine(st(on = true, logged = false, tg = listOf(GrabTarget(id = 1))))
        assertTrue("必须出现监控状态", s.contains("监控中"))
        assertTrue("必须出现教务登录状态", s.contains("教务未登录"))
        assertFalse("没登录时不能只说「监控中」就完事", s == "监控中")
    }

    @Test
    fun `未配置与已停止不能混为一谈`() {
        val none = GrabLogic.statusLine(GrabStatus(configured = false))
        assertTrue(none.contains("未配置"))
        val stopped = GrabLogic.statusLine(st(on = false))
        assertTrue(stopped.contains("已停止"))
        assertEquals(1, GrabLogic.statusLevel(st(on = true, tg = listOf(GrabTarget(id = 1)))))
        assertEquals(2, GrabLogic.statusLevel(st(on = false)))
        assertEquals(3, GrabLogic.statusLevel(st(on = true, err = "登录失败")))
    }

    @Test
    fun `开关开着但没有目标_不能说成监控中`() {
        // 关掉监控真的发生过一次"目标被清空"之后，monitor_on 还是 1（服务端 cfg 没人改）。
        // 这时候说"监控中"，用户就会以为有人在替他抢 —— 这是最贵的一种假话。
        val s = st(on = true, tg = emptyList())
        assertEquals("开", GrabLogic.monitorSwitchText(s))
        assertFalse("没有目标就是不等于在盯", s.watching)
        assertTrue("要说实话：没有在盯任何课", GrabLogic.statusLine(s).contains("没有在盯任何课"))
        assertTrue(GrabLogic.statusLevel(s).let { it == 1 })  // 开关确实是"在监控"档
    }

    @Test
    fun `状态条要能把服务端那几个字段都说全`() {
        val m = GrabLogic.metaLine(realS())
        assertTrue("轮询间隔要说出来：$m", m.contains("20 秒"))
        assertTrue("目标数要说出来：$m", m.contains("目标 3 门"))
        // 上次检查时间只在标题行说一次 —— 两行并排同一个时间戳是纯噪音（真机截图里就是这样叠着的）
        assertFalse("第二行不要重复时间戳：$m", m.contains("09:16:23"))
        assertTrue("时间戳该在标题行里", GrabLogic.statusLine(realS()).contains("09:16:23"))
    }

    /**
     * 真机截图那一幕：首屏请求失败写了条红字，之后请求都成功了，红字还在 ——
     * 用户以为 App 坏了，其实数据早就拉回来了。成功之后必须把旧报错清掉。
     */
    @Test
    fun `旧报错在下次成功之后必须消失_不能常驻`() {
        login()
        render(fakeApi(failCatalogs = 1))
        awaitCount("服务端错误 500", substring = true)
        compose.onAllNodesWithText("刷新").onFirst().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            count("服务端错误 500", substring = true) == 0
        }
        assertEquals("成功之后还留着旧报错，会被当成 App 坏了", 0,
            count("服务端错误 500", substring = true))
        assertTrue("数据本身要正常显示出来", count("能抢且不冲突", substring = true) >= 1)
    }

    @Test
    fun `没清单时统计行说清该怎么办`() {
        val s = GrabLogic.statsLine(st(total = 0))
        assertTrue("应给出下一步动作", s.contains("重建清单"))
    }

    @Test
    fun `统计行强调的是能抢且不冲突的数`() {
        val s = GrabLogic.statsLine(st())
        assertTrue(s.contains("526"))
        assertTrue("要把 ok_clean 说出来，不是只说总数", s.contains("73"))
    }

    @Test
    fun `列表空了要说清为什么空`() {
        assertTrue(GrabLogic.emptyHint("ok", "").contains("不冲突"))
        assertTrue(GrabLogic.emptyHint("all", "数学分析（I）").contains("数学分析（I）"))
        assertTrue(GrabLogic.emptyHint("all", "").contains("重建清单"))
    }

    @Test
    fun `能抢的判定要四件同时成立`() {
        val base = GrabLesson(lesson_id = 1, course = "数学分析（I）", free = 5, mine = 0,
            probe_ok = 1, cn = 0)
        assertTrue("四件都成立才算能抢", base.grabbable)
        assertFalse("没余位不算", base.copy(free = 0).grabbable)
        assertFalse("已选过不算", base.copy(mine = 1).grabbable)
        assertFalse("预检不放行不算", base.copy(probe_ok = 0).grabbable)
        assertFalse("还没预检不算（不能拿没结论当可以）", base.copy(probe_ok = null).grabbable)
        assertFalse("撞课表不算", base.copy(cn = 2).grabbable)
    }

    @Test
    fun `加入监控后冲突必须说出来`() {
        assertTrue(GrabLogic.addedText("数学分析（I）", "").contains("已加入监控"))
        val withClash = GrabLogic.addedText("数学分析（I）", "与「程序设计基础（B）」冲突 2 节")
        assertTrue("有冲突必须提醒", withClash.contains("⚠"))
        assertTrue(withClash.contains("程序设计基础（B）"))
    }

    @Test
    fun `余量文案：已选 已满 有余位是三种话`() {
        val l = GrabLesson(lesson_id = 1, course = "x", free = 3)
        assertEquals("余 3", GrabLogic.seatsText(l))
        assertEquals("已满", GrabLogic.seatsText(l.copy(free = 0)))
        assertEquals("我已选", GrabLogic.seatsText(l.copy(mine = 1)))
    }

    @Test
    fun `余量要带分母_25比110更能看出这课有多热`() {
        val l = realC().first { it.lesson_id == 318856 }
        assertEquals("25/110", GrabLogic.seatsDetail(l))
    }

    @Test
    fun `学分不要拖没意义的小数点`() {
        assertEquals("3", GrabLogic.trimNum(3.0))
        assertEquals("2.5", GrabLogic.trimNum(2.5))
    }

    @Test
    fun `自动提交的说明必须写清是服务端在执行`() {
        assertTrue("要写清动作发生在服务端", GrabLogic.AUTO_SUBMIT_NOTE.contains("服务端"))
        assertTrue("还要写清这一页能开也能停", GrabLogic.AUTO_SUBMIT_NOTE.contains("能开也能停"))
        assertTrue("回读没开成就不能显示成已开（契约）",
            GrabLogic.AUTO_SUBMIT_NOTE.contains("回读"))
    }

    // ---------------------------------------------------------------- 课时段/班级/预检

    @Test
    fun `周次节次教室要能拆成一行一个时段`() {
        val l = realC().first { it.lesson_id == 318856 }
        val slots = GrabLogic.timeSlots(l.place)
        assertEquals("两个时段就该两行", 2, slots.size)
        assertTrue(slots[0].contains("10~17周"))
        assertTrue(slots[0].contains("星期二"))
        assertTrue(slots[0].contains("8~9节"))
        assertTrue(slots[0].contains("B楼I区212"))
        assertTrue("不能把换行符留在正文里：${slots[0]}", !slots[0].contains("\n"))
    }

    @Test
    fun `上课班级要说出来`() {
        val l = realC().first { it.lesson_id == 318856 }
        assertTrue(GrabLogic.classText(l).contains("自动化24"))
    }

    @Test
    fun `预检不放行要把学校的原话带出来`() {
        val blocked = GrabLesson(lesson_id = 1, course = "x", probe_ok = 0, probe_reason = "时间冲突")
        assertTrue(GrabLogic.probeText(blocked)!!.contains("时间冲突"))
        assertTrue("没结论的课不该说「预检不放行」", GrabLogic.probeText(
            GrabLesson(lesson_id = 1, course = "x", probe_ok = null)) == null)
    }

    // ---------------------------------------------------------------- 冲突规则（用户定的）

    @Test
    fun `有冲突的课不许自动抢_无冲突才允许`() {
        val ok = GrabLesson(lesson_id = 1, course = "数学分析（I）", cn = 0, free = 5, probe_ok = 1)
        val clash = GrabLesson(lesson_id = 2, course = "流体力学", cn = 1, free = 5, probe_ok = 1,
            clash = listOf(GrabClash(course = "通用英语", weekday = 3,
                time_text = "11:25-13:50", room = "A楼406")))
        assertTrue("没冲突就该允许", GrabLogic.canAuto(ok))
        assertFalse("有冲突就不许自动抢", GrabLogic.canAuto(clash))
        val why = GrabLogic.autoBlockedReason(clash)!!
        assertTrue("必须说清是冲突：$why", why.contains("和课表冲突"))
        assertTrue("还要说清和哪门课冲突：$why", why.contains("通用英语"))
        assertTrue("有冲突的行也要能加入监控（只是不许自动抢）",
            GrabLogic.addedText(clash.course, GrabLogic.clashText(clash)).contains("已加入监控"))
    }

    @Test
    fun `已选过的课也不许自动抢`() {
        assertFalse(GrabLogic.canAuto(GrabLesson(lesson_id = 1, course = "x", cn = 0, mine = 1)))
    }

    @Test
    fun `冲突说人话：和哪门课_什么时候_在哪`() {
        val clash = realClashLesson()
        val s = GrabLogic.clashText(clash)
        assertTrue(s.contains("通用英语"))
        assertTrue(s.contains("周三"))
        assertTrue(s.contains("11:25-13:50"))
        assertTrue(s.contains("A楼406"))
    }

    /** 真样本里 cn>0 的那门课 */
    private fun realClashLesson(): GrabLesson =
        json.decodeFromString<top.ccbase.campus.net.GrabCatalog>(
            sample("grab_catalog_conflict_real.json")).lessons.first { it.lesson_id == 317452 }

    @Test
    fun `确认层只列无冲突的目标_有冲突的单独说清原因`() {
        val clean = GrabTarget(id = 1, lesson_id = 318856, turn_id = 242,
            course = "系统工程与运筹学", teacher = "教师一", limit_cnt = 110, clash_text = "")
        val dirty = GrabTarget(id = 2, lesson_id = 317452, turn_id = 242,
            course = "流体力学拓展选讲", teacher = "教师七", limit_cnt = 100,
            clash_text = "通用英语 周三 11:25-13:50@A楼406")
        val lessons = listOf(
            GrabLesson(lesson_id = 318856, course = "系统工程与运筹学", cls = "自动化24-[1-4]班",
                free = 85, used = 25, limit_cnt = 110),
            GrabLesson(lesson_id = 317452, course = "流体力学拓展选讲", cls = "油气储运工程23-[1-3]班",
                free = 88, used = 12, limit_cnt = 100, cn = 1),
        )
        val allow = GrabLogic.autoConfirmLines(listOf(clean, dirty), lessons)
        assertEquals("确认层只能列无冲突的那一门", 1, allow.size)
        assertTrue("要说到教学班", allow[0].contains("自动化24-[1-4]班"))
        assertTrue("要说到余量", allow[0].contains("余 85"))
        assertTrue(GrabLogic.autoConfirmTitle(allow).contains("1 门"))

        val excluded = GrabLogic.autoExcludedLines(listOf(clean, dirty))
        assertEquals(1, excluded.size)
        assertTrue("有冲突的必须说明为什么不会被抢", excluded[0].contains("通用英语"))
        assertTrue(GrabLogic.autoConfirmTitle(emptyList()).contains("没有可自动抢"))
    }

    @Test
    fun `确认层必须写清会自动提交_而且有冲突的不抢`() {
        assertEquals("开启后服务器会自动向教务系统提交选课，无需你再确认。",
            GrabLogic.AUTO_CONFIRM_BODY)
        assertTrue(GrabLogic.AUTO_RULE.contains("有冲突的课不会抢"))
        assertTrue(GrabLogic.AUTO_RULE.contains("无时间冲突"))
    }

    // ---------------------------------------------------------------- 日志

    @Test
    fun `日志时间只留时分秒_级别要分得清`() {
        val g = realS().logs.first()
        assertEquals("01:02:53", GrabLogic.logTime(g.ts))
        assertEquals(2, GrabLogic.logLevel("alert"))
        assertEquals(3, GrabLogic.logLevel("error"))
        assertEquals(0, GrabLogic.logLevel("info"))
        assertEquals("提醒", GrabLogic.logLabel("alert"))
        assertEquals("错误", GrabLogic.logLabel("error"))
    }

    @Test
    fun `日志摘要要让人看出刚有动作`() {
        val logs = realS().logs
        val s = GrabLogic.logsSummary(logs)
        assertTrue("要说多少条：$s", s.contains("20 条"))
        assertTrue("要说最新一条时间：$s", s.contains("01:02:53"))
        assertTrue(GrabLogic.logsSummary(emptyList()).contains("还没有"))
    }

    @Test
    fun `自动提交到底跑没跑_只能看日志`() {
        val logs = realS().logs + GrabLog(id = 1, ts = "2026-09-17 01:03:00", level = "alert",
            msg = "🎯 有位了！X | ✅ 已自动提交并成功！")
        val hit = GrabLogic.autoSubmitLogs(logs)
        assertEquals(1, hit.size)
        assertTrue(GrabLogic.autoSubmitHeadline(logs).contains("1 条"))
        assertEquals("没有提交记录就不该硬说有", "",
            GrabLogic.autoSubmitHeadline(realS().logs))
    }

    // ---------------------------------------------------------------- 错误分类

    @Test
    fun `401_429_0 是三件不同的事_不能混成网络异常`() {
        val e401 = GrabLogic.errorText(401, "令牌无效或已过期")
        val e429 = GrabLogic.errorText(429, "尝试过于频繁")
        val e0 = GrabLogic.errorText(0, "连不上服务器")
        val e500 = GrabLogic.errorText(500, "internal error")
        assertNotEquals(e401, e429)
        assertNotEquals(e401, e0)
        assertTrue(e401.contains("登录") && e401.contains("过期"))
        assertTrue(e429.contains("频繁"))
        assertTrue(e0.contains("网络"))
        assertTrue("其他错误必须把码带出来：$e500", e500.contains("500"))
        assertTrue(GrabLogic.errorText(403, "x").contains("只对作者开放"))
    }

    // ---------------------------------------------------------------- 真渲染（喂真样本）

    private fun render(api: CampusApi) {
        login()
        val ctx = RuntimeEnvironment.getApplication()
        compose.setContent { CampusTheme { GrabScreen(ctx, api) } }
        compose.waitForIdle()
    }

    /**
     * 在渲染树里数节点。
     *
     * 为什么不用 assertIsDisplayed / assertExists：
     *   - assertIsDisplayed 会被 Robolectric 的视口判定坑（同一个节点在不同测试里一会儿可见一会儿不可见）
     *   - assertExists 在多条匹配时会炸，而"这条文案出现几次"本身就是我们要断言的东西
     * 数节点既不依赖视口，也能把重复条数说清楚。
     */
    private fun count(text: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    /**
     * 点击之后的写请求走 `Dispatchers.IO`（CampusApi.call 里那句 withContext）——
     * `waitForIdle()` **不等后台线程**，直接断言会随机失败。这里轮询等结果出来。
     * （踩过一次：同一个测试单独跑过、整类跑就挂，就是这里在赌时序。）
     */
    private fun awaitCount(text: String, substring: Boolean = false, atLeast: Int = 1) {
        compose.waitUntil(timeoutMillis = 10_000) { count(text, substring) >= atLeast }
    }

    @Test
    fun `真样本渲染：状态条把服务端的字段都说出来`() {
        render(fakeApi())
        compose.onNodeWithText("监控中 3 门", substring = true).assertIsDisplayed()
        compose.onNodeWithText("教务已登录", substring = true).assertIsDisplayed()
        compose.onNodeWithText("每 20 秒轮一次", substring = true).assertIsDisplayed()
        compose.onNodeWithText("目标 3 门", substring = true).assertIsDisplayed()
        compose.onNodeWithText("526 门开放", substring = true).assertIsDisplayed()
    }

    @Test
    fun `监控中的目标要看得见_在盯什么一目了然`() {
        render(fakeApi())
        assertEquals(1, count("正在盯（3）"))
        // 三条目标都是"在盯、没抢到"的同一种状态
        assertEquals(3, count("盯守中"))
        assertEquals(3, count("移除"))
        assertTrue("要在监控区里写清是盯的哪门课", count("系统工程与运筹学") >= 1)
        assertTrue("要写出教师和限额", count("教师一 · 限 110 人", substring = true) >= 1)
        // 余量和教学班：从清单里交叉对上（目标行本身没有这两个字段）
        assertTrue(count("余量：余 85", substring = true) >= 1)
        assertTrue(count("上课班级：自动化24-[1-4]班", substring = true) >= 1)
    }

    @Test
    fun `最近动作默认只露几条_展开能看到全部`() {
        render(fakeApi())
        assertEquals(1, count("最近动作"))
        assertEquals("摘要要说清多少条", 1, count("20 条 · 最新", substring = true))
        // 折叠时只露最新 5 条 → 其中 3 条是同一个"有位了"提醒
        assertEquals(3, count("有位了", substring = true))
        // 第 20 条（监控线程启动）在折叠状态下不该出现 —— 一屏塞 20 条就不是"看见"了
        assertEquals(0, count("监控线程启动"))
        compose.onNodeWithText("展开全部 20 条").performClick()
        compose.waitForIdle()
        assertEquals("展开后要真的多出来（20 条里有 6 条是提醒）", 6,
            count("有位了", substring = true))
        assertEquals("最后一条也要能看到", 1, count("监控线程启动"))
    }

    /**
     * 提醒必须由**这台手机自己弹**（原来的服务端推送只到作者一个人的飞书/QQ，
     * 换个同学装 App 什么都收不到）。这里卡住界面上那一步真的能把开关打开、
     * 并且真的排上了闹钟 —— 不然"能提醒"只是句标语。
     */
    @Test
    fun `抢课页的「手机直接提醒」开关点了要真的排上闹钟`() {
        val app = RuntimeEnvironment.getApplication()
        top.ccbase.campus.alarm.GrabWatch.setOn(app, false) // 起点：关
        // 通知权限默认给上，否则会走"先要权限"那条支路
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(true)

        render(fakeApi())

        assertEquals("页面上要有这一行，藏在设置里等于没有", 1, count("手机直接提醒"))
        assertEquals("关着的时候要说清后果，不能只写一个「关」", 1, count("关 · 不会弹提醒"))
        compose.onNodeWithText("开启").performClick()
        compose.waitForIdle()

        assertTrue("点了开启必须真的开", top.ccbase.campus.alarm.GrabWatch.isOn(app))
        assertTrue(
            "开了就要排闹钟，否则永远不会自己查",
            org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
                .scheduledAlarms.isNotEmpty(),
        )
        awaitCount("已开", substring = true)
        // 收尾：别把状态留给同一个类里后面的测试
        top.ccbase.campus.alarm.GrabWatch.setOn(app, false)
    }

    /**
     * 监控开关以前只有网页版能改，App 只能"教用户去浏览器"。
     * 现在 App 自己就能开 —— 条件是**必须真的打到服务端**，不是画个开关。
     */
    @Test
    fun `开启监控是真调服务端_不是画个开关`() {
        val off = json.encodeToString(GrabStatus.serializer(), realS().copy(monitor_on = false))
        val on = json.encodeToString(GrabStatus.serializer(), realS().copy(monitor_on = true))
        val seen = mutableListOf<String>()
        // 关 → 点开启 → 回读变开（真实服务端就是这样变的）
        render(fakeApi(statusSeq = listOf(off, on), seen = seen))
        compose.onNodeWithText("开启监控").performClick()
        compose.waitForIdle()
        // 第 1 下只弹确认层，不发写请求
        compose.onNodeWithText("开启监控？").assertIsDisplayed()
        assertTrue("还没确认就发写请求 = 一次点击就开，绝对不行：$seen",
            seen.none { it.startsWith("POST") })
        compose.onNodeWithText("确认开启").performClick()
        awaitCount("监控已开", substring = true)
        assertTrue("必须真的去开服务端监控：$seen",
            seen.any { it.startsWith("POST") && it.contains("/grab/config") &&
                it.contains("\"monitor_on\":true") })
    }

    @Test
    fun `关监控不再顺手删目标`() {
        val off = json.encodeToString(GrabStatus.serializer(), realS().copy(monitor_on = false))
        val seen = mutableListOf<String>()
        render(fakeApi(statusSeq = listOf(realStatus, off), seen = seen))
        compose.onNodeWithText("关闭监控").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认关闭").performClick()
        awaitCount("监控已关", substring = true)
        assertTrue("关监控要走配置接口：$seen",
            seen.any { it.startsWith("POST") && it.contains("/grab/config") &&
                it.contains("\"monitor_on\":false") })
        assertTrue("暂停监控不该把用户一门门点出来的目标删掉：$seen",
            seen.none { it.startsWith("DELETE") })
    }

    @Test
    fun `加入监控要有明确反馈_不能静默`() {
        // 这一步要点「加入监控」：把目标和日志压到最少，让按钮落在首屏内，
        // 免得点击坐标落在视口外（Robolectric 里这不是"找不到节点"，是静默不触发）
        val one = realS().targets.first()
        val short = json.encodeToString(GrabStatus.serializer(),
            realS().copy(targets = listOf(one), logs = emptyList()))
        val oneLesson = json.encodeToString(GrabCatalog.serializer(),
            json.decodeFromString(GrabCatalog.serializer(), realCatalog)
                .copy(lessons = listOf(realC().first())))
        render(fakeApi(statusSeq = listOf(short), catalog = oneLesson))
        assertEquals("点之前不该有反馈", 0, count("已加入监控", substring = true))
        compose.onNodeWithText("加入监控").performClick()
        awaitCount("已加入监控：", substring = true)
        assertEquals("点完必须冒出一条反馈", 1, count("已加入监控", substring = true))
        assertEquals("反馈里必须写清是哪门课", 1, count("已加入监控：", substring = true))
    }

    @Test
    fun `监控没在跑时_加入监控要拦下来并说清为什么`() {
        val stopped = json.encodeToString(GrabStatus.serializer(),
            realS().copy(monitor_on = false))
        render(fakeApi(statusSeq = listOf(stopped)))
        compose.onNodeWithText("监控已停止", substring = true).assertIsDisplayed()
        compose.onNodeWithText("现在没在盯任何课", substring = true).assertExists()
        compose.onAllNodesWithText("加入监控").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("加入监控不会生效", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- 需求 A：关闭监控

    @Test
    fun `关闭监控要过确认层_并说清真实会发生什么`() {
        render(fakeApi())
        compose.onNodeWithText("关闭监控").performClick()
        compose.waitForIdle()
        // 说清后果：不查、不推、不提交
        compose.onNodeWithText("不再查余量、不再推送", substring = true).assertIsDisplayed()
        // 而且要说清目标留着 —— 暂停 ≠ 清空清单
        compose.onNodeWithText("留着", substring = true).assertIsDisplayed()
        compose.onNodeWithText("移除全部", substring = true).assertDoesNotExist()
    }

    @Test
    fun `关掉之后状态条要立刻反映出来_目标还在`() {
        val off = json.encodeToString(GrabStatus.serializer(),
            realS().copy(monitor_on = false))
        val seen = mutableListOf<String>()
        render(fakeApi(statusSeq = listOf(realStatus, off), seen = seen))
        compose.onNodeWithText("关闭监控").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认关闭").performClick()
        awaitCount("监控已关", substring = true)
        // 目标没被删，回读也确实拿到"关"
        assertTrue("关监控不该删目标：$seen", seen.none { it.startsWith("DELETE") })
        assertEquals("目标要留着，用户随时能再开", 3, realS().targets.size)
    }

    // ---------------------------------------------------------------- 需求 B：自动抢课

    @Test
    fun `默认必须是关闭状态`() {
        // 真样本里 auto_submit=false、三条目标全是 0
        val s = realS()
        assertFalse("服务端默认就是关的", s.auto_submit)
        assertTrue(s.targets.none { it.auto_submit })
        assertFalse(s.autoEnabled)

        render(fakeApi())
        compose.onNodeWithText("⚡ 自动提交中").assertDoesNotExist()
        compose.onNodeWithText("服务端已开").assertDoesNotExist()
        compose.onNodeWithText("自动抢课").assertIsDisplayed()
    }

    @Test
    fun `开启必须经过确认层_一次点击不可能打开`() {
        val seen = mutableListOf<String>()
        render(fakeApi(seen = seen))

        // 第 1 下：只弹确认层，不带任何写请求
        compose.onNodeWithText("开启…").performClick()
        compose.waitForIdle()
        assertTrue("还没确认就发写请求 = 一次点击就开，绝对不行",
            seen.none { it.startsWith("POST") })
        compose.onNodeWithText("开启后服务器会自动向教务系统提交选课，无需你再确认。").assertIsDisplayed()
        compose.onNodeWithText("开启自动抢课？").assertIsDisplayed()
        // 规则那句话在页面和确认层里各出现一次
        compose.onAllNodesWithText("只对无时间冲突的课自动提交；有冲突的课不会抢。").onFirst()
            .assertExists()
        compose.onNodeWithText("会立即变成自动提交的 3 门：").assertIsDisplayed()
        compose.onNodeWithText("⚡ 自动提交中").assertDoesNotExist()

        // 取消 → 仍然关着，且没有发过写请求
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertTrue("取消之后不能有任何写请求", seen.none { it.startsWith("POST") })
        compose.onNodeWithText("开启后服务器会自动向教务系统提交选课，无需你再确认。").assertDoesNotExist()
        compose.onNodeWithText("⚡ 自动提交中").assertDoesNotExist()
    }

    @Test
    fun `确认之后全局开关和单课标记都要动`() {
        val seen = mutableListOf<String>()
        render(fakeApi(seen = seen,
            addReply = """{"ok":true,"clash":[],"clash_text":"","auto_submit":true}"""))
        compose.onNodeWithText("开启…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认开启").performClick()
        awaitCount("已开启自动提交", substring = true)
        assertTrue("全局开关要开：$seen",
            seen.any { it.startsWith("POST") && it.contains("/grab/config") &&
                it.contains("\"auto_submit\":true") })
        assertTrue("单门标记也要开（服务端要求两者同时为真）：$seen",
            seen.count { it.contains("/grab/target") && it.contains("\"auto_submit\":true") } >= 1)
    }

    @Test
    fun `服务端回读没接受_就必须照实说没开成`() {
        // 契约：显示只认服务端回读。回读 false 时不能显示成"已开启"
        render(fakeApi())   // 默认 addReply 里 auto_submit=false
        compose.onNodeWithText("开启…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认开启").performClick()
        awaitCount("服务端没接受开启", substring = true)
        compose.onNodeWithText("⚡ 自动提交中").assertDoesNotExist()
        compose.onNodeWithText("服务端已开").assertDoesNotExist()
    }

    @Test
    fun `服务端真的开了_才显示醒目的自动提交中`() {
        val on = json.encodeToString(GrabStatus.serializer(), realS().copy(auto_submit = true))
        render(fakeApi(statusSeq = listOf(on)))
        compose.onNodeWithText("⚡ 自动提交中").assertIsDisplayed()
        compose.onNodeWithText("立刻停止全部自动提交").assertIsDisplayed()
        compose.onNodeWithText("服务端已开").assertIsDisplayed()
        // 一键停：要走确认层
        compose.onNodeWithText("立刻停止全部自动提交").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("停止自动抢课？").assertIsDisplayed()
    }

    @Test
    fun `有冲突的目标_自动抢开关置灰且说清原因`() {
        // 把唯一一条目标造成"和课表冲突"（clash_text 用服务端 _fmt_clash 的真实格式）
        val one = realS().targets.first().copy(clash_text = "通用英语 周三 11:25-13:50@A楼406")
        val conflicted = json.encodeToString(GrabStatus.serializer(),
            realS().copy(targets = listOf(one)))
        val seen = mutableListOf<String>()
        render(fakeApi(statusSeq = listOf(conflicted), seen = seen))
        compose.onNodeWithText("不可自动抢 —— 和课表冲突：通用英语 周三 11:25-13:50@A楼406")
            .assertIsDisplayed()
        // 开关确实是"灰的"（点不动），不是只换个颜色
        compose.onNodeWithText("自动抢：关").assertIsNotEnabled()
        assertTrue("有冲突的课绝不许开自动抢（连写请求都不该发）：$seen",
            seen.none { it.startsWith("POST") })
        compose.onNodeWithText("开启后服务器会自动向教务系统提交选课，无需你再确认。").assertDoesNotExist()
    }

    @Test
    fun `无冲突的目标_自动抢开关可点_但一样要过确认层`() {
        val one = realS().targets.first()   // 真样本这条 clash_text 是空的 = 无冲突
        val clean = json.encodeToString(GrabStatus.serializer(),
            realS().copy(targets = listOf(one)))
        val seen = mutableListOf<String>()
        render(fakeApi(statusSeq = listOf(clean), seen = seen))
        compose.onNodeWithText("自动抢：关").assertIsEnabled()
        compose.onNodeWithText("自动抢：关").performClick()
        compose.waitForIdle()
        assertTrue("确认之前不许发写请求：$seen", seen.none { it.startsWith("POST") })
        compose.onNodeWithText("开启后服务器会自动向教务系统提交选课，无需你再确认。").assertIsDisplayed()
        compose.onNodeWithText("会立即变成自动提交的 1 门：").assertIsDisplayed()
    }

    @Test
    fun `无冲突的课_自动抢徽标是可用的`() {
        render(fakeApi())
        compose.onAllNodesWithText("自动抢 ✓").onFirst().assertIsDisplayed()
        compose.onNodeWithText("自动抢 ✕").assertDoesNotExist()
    }

    @Test
    fun `有冲突的课允许加入监控_但自动抢徽标是灰的`() {
        val conflictCatalog = sample("grab_catalog_conflict_real.json")
        render(fakeApi(catalog = conflictCatalog))
        compose.onAllNodesWithText("流体力学拓展选讲").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("自动抢 ✕").onFirst().assertIsNotEnabled()
        // 精确匹配：这门课只撞了「通用英语」一门
        compose.onNodeWithText("不可自动抢 —— 和课表冲突：通用英语 周三 11:25-13:50@A楼406")
            .assertIsDisplayed()
        // 加入监控仍然可用（冲突只挡自动抢，不挡盯余量）
        compose.onAllNodesWithText("加入监控").onFirst().assertExists()
        // 这一屏里不该出现任何"可自动抢"的绿灯
        compose.onNodeWithText("自动抢 ✓").assertDoesNotExist()
    }

    @Test
    fun `确认层要写清是哪些课_哪个教学班_余量多少`() {
        // 这门课在默认档位（能抢且不冲突）里查不到 —— 确认层必须自己按课名补查一次，
        // 否则用户按下"确认开启"之前根本看不到会被提交的是哪个教学班、还剩多少位
        val t = realS().targets.first().copy(id = 9, lesson_id = 317452,
            course = "流体力学拓展选讲", teacher = "教师七", limit_cnt = 100)
        val only = json.encodeToString(GrabStatus.serializer(), realS().copy(targets = listOf(t)))
        val seen = mutableListOf<String>()
        render(fakeApi(statusSeq = listOf(only), seen = seen,
            byQuery = mapOf("流体力学拓展选讲" to sample("grab_catalog_conflict_real.json"))))
        compose.onNodeWithText("开启…").performClick()
        awaitCount("· 流体力学拓展选讲 · 教师七", substring = true)
        // 一行里要能看到：哪门课/哪位老师 + 哪个教学班 + 还有多少余位
        assertTrue("要写出教学班", count("· 流体力学拓展选讲 · 教师七 · 油气储运工程23-[1-3]班",
            substring = true) >= 1)
        assertTrue("要写出余量（带分母）", count("余 88 · 12/100", substring = true) >= 1)
        assertTrue("必须真的去补查清单：$seen", seen.any { it.contains("q=%E6%B5%81") })
    }

    @Test
    fun `确认层里无冲突的列出来_有冲突的写清为什么不会被抢`() {
        val clean = realS().targets.first()
        val dirty = clean.copy(id = 2, lesson_id = 317452, course = "流体力学拓展选讲",
            teacher = "教师七", limit_cnt = 100,
            clash_text = "通用英语 周三 11:25-13:50@A楼406")
        val mixed = json.encodeToString(GrabStatus.serializer(),
            realS().copy(targets = listOf(clean, dirty)))
        render(fakeApi(statusSeq = listOf(mixed)))
        compose.onNodeWithText("开启…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("会立即变成自动提交的 1 门：").assertIsDisplayed()
        compose.onNodeWithText("不会自动抢的（有冲突）：").assertIsDisplayed()
        compose.onNodeWithText("· 流体力学拓展选讲 —— 和课表冲突：通用英语 周三 11:25-13:50@A楼406")
            .assertIsDisplayed()
    }

    @Test
    fun `没登录也能打开这一页_并给出人话提示`() {
        val ctx = RuntimeEnvironment.getApplication()
        TokenStore.clear(ctx)
        compose.setContent { CampusTheme { GrabScreen(ctx, fakeApi()) } }
        compose.waitForIdle()
        // 页面骨架必须在（筛选档位是这一页的入口）
        compose.onNodeWithText("能抢且不冲突").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsDisplayed()
    }
}
