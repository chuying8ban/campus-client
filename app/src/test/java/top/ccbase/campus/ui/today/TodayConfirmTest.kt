package top.ccbase.campus.ui.today

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.CampusApplication
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.domain.UNIT_TIMES
import top.ccbase.campus.domain.loadToday
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 今日页任务行的点击语义（2026-09-18 按用户要求重定的那一版）：
 *
 *  点整行 → **打开这一项的详情**（怎么做 / 有视频就给链接）；
 *  完成 / 取消完成是**详情里的两个动作** —— 点行本身不改变任何状态。
 *
 * 为什么要这么定：整行热区又宽又低，"点一下就完成"被误触过（
 * 上一版只好加一道「确认完成？」，但那只挡住了误触，没解决"看不了怎么做"）。
 * 现在状态改动只发生在详情里的明确按钮上。
 *
 * 和 TaskDoneConfirmTest 一样，不硬编码文案：任务从库里查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xhdpi")
class TodayConfirmTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app() = ApplicationProvider.getApplicationContext<CampusApplication>()

    private fun waitFor(text: String, ms: Long = 15_000) =
        rule.waitUntil(ms) {
            rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun tap(text: String, exact: Boolean = false) =
        rule.onAllNodesWithText(text, substring = !exact)[0].performClick()

    private fun isDone(taskId: Int): Boolean = runBlocking {
        app().db.dao().isDone(taskId, LocalDate.now().toString()) > 0
    }

    /**
     * 夹具任务（[TodayFixture]）：**必然**属于"今天"，不看日历、不靠种子库碰运气。
     *
     * 以前这里是"查不到就 `assumeTrue` 跳过"—— 跳过就是**没验**（2026-09-19 核实：
     * 种子库没写 `plan_source`，所以这两条**永远**跳过）。现在查不到就断言失败：
     * 夹具坏了必须红。
     */
    private fun fixtureTask(): Pair<Int, String> = runBlocking {
        app().seedJob?.join()
        val db = app().db
        TodayFixture.seed(db)
        val mine = loadToday(db).let { it.tasks + it.standing }
            .firstOrNull { it.id == TodayFixture.TASK_ID }
        assertNotNull("夹具任务没进「今日」列表 —— 夹具失效了，不许拿跳过当通过", mine)
        mine!!.id to mine.title
    }

    @Test
    fun `点开今日任务先看详情_完成要确认`() {
        val (id, title) = fixtureTask()

        rule.setContent { CampusTheme { TodayScreen(app().db) } }
        waitFor("今日任务")

        // 任务列表在课表下面，可能没进视口 —— 先滚到它
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title, substring = true))
        rule.waitForIdle()

        assertFalse("用例前提：这条应当是未完成的", isDone(id))

        // ① 点整行 → 打开**详情**，绝不能顺手把它标成完成
        tap(title)
        waitFor("任务详情")
        assertFalse("点开任务只是看详情，不能直接完成", isDone(id))

        // ② 详情里的「完成」→ 仍要过「确认完成？」这一关
        tap("完成", exact = true)
        waitFor("确认完成？")
        assertFalse("确认之前不能落库", isDone(id))

        // ③ 取消 → 什么也没发生（详情还在）
        tap("取消", exact = true)
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("确认完成？").fetchSemanticsNodes().isEmpty()
        }
        assertFalse("取消 = 什么也没发生", isDone(id))

        // ④ 再来一次并确认 → 这次才落库
        tap("完成", exact = true)
        waitFor("确认完成？")
        tap("确认完成", exact = true)
        rule.waitUntil(15_000) { isDone(id) }
        assertTrue("点「确认完成」之后必须真的打卡", isDone(id))
    }

    @Test
    fun `已完成的任务在详情里点取消完成就撤销`() {
        val db = app().db
        val (id, title) = fixtureTask()

        // 先把它标成今天已完成（模拟今早点过完成）
        runBlocking {
            db.dao().markDone(TaskDone(task_id = id, day = LocalDate.now().toString(), at = "09:00"))
        }

        rule.setContent { CampusTheme { TodayScreen(db) } }
        waitFor("今日任务")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title, substring = true))
        rule.waitForIdle()

        // 点开 → 已完成的这一项，动作应当是「取消完成」
        tap(title)
        waitFor("任务详情")
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("取消完成", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 撤销是安全的 → 一点就回，不再挡一道确认
        tap("取消完成")
        rule.waitUntil(15_000) { !isDone(id) }
        assertFalse("点「取消完成」必须立刻撤销", isDone(id))
    }

    @Test
    fun `左列显示时间范围_不是只有开始钟点`() {
        // 用户原话（2026-09-18）：左列只有开始时间，想知道这节上到几点。
        // ⚠️ 必须喂**线上真实形状**：ClassNow.timeText 是 slotTimeLabel() 拼出来的
        // 「1-2 节 · 09:30-11:05」，不是原始 time_text。
        // 第一版我喂的是自己臆想的「1-2」/「6」，于是"通过"了一个错实现 ——
        // 用户装上后在手机上看到的还是老样子。测试数据形状不对，等于没测。
        assertEquals("09:30-11:05", timeLabel("1-2 节 · 09:30-11:05"))
        assertEquals("12:15-13:50", timeLabel("4-5 节 · 12:15-13:50"))
        assertEquals("16:00-17:35", timeLabel("6-7 节 · 16:00-17:35"))
        assertEquals("17:55-19:30", timeLabel("8-9 节 · 17:55-19:30"))
        // 原始 time_text 的形状（线上库里是 '9:30-11:05'，没有前导零 → 两个钟点都补齐，
        // 否则「9:30-11:05」和「09:30」在同一列里对不齐）
        assertEquals("09:30-11:05", timeLabel("9:30-11:05"))
        // 只有节次、没有钟点 → 查官方作息表，给**整段**（几节就查到第几节的结束）
        assertEquals("16:00-16:45", timeLabel("6"))
        assertEquals("09:30-11:05", timeLabel("1-2 节"))
        // 只有一个钟点（降级/老数据）→ 给一个钟点，**不编造**结束时间
        assertEquals("09:30", timeLabel("1-2 节 · 09:30"))
        // 自习行的纯钟点原样保留
        assertEquals("08:45", timeLabel("08:45"))
        assertEquals("19:00", timeLabel("19:00"))
        // 空/认不出来 → 原样返回，不编造
        assertEquals("", timeLabel(""))
        assertEquals("??", timeLabel("??"))
    }

    @Test
    fun `自习行左列也给时间范围_早晚自习都给起止`() {
        // 用户原话（2026-09-18）：「早自习和晚自习的具体时间也加上」——
        // 课节那列早就给范围了，唯独自习只有一个起点，同一列两种语言。
        // 下面两个期望值是**线上库里 selfstudy 的真值**（早自习 08:45-09:15、
        // 晚自习 20:30-22:05），不是照"时长"推算出来的。
        assertEquals("08:45-09:15", selfStudyLabel("08:45", "09:15"))
        assertEquals("20:30-22:05", selfStudyLabel("20:30", "22:05"))
        // 晚自习末点必须跟官方作息表对上：第 10 节开始、第 11 节下课。
        // （他先说「一小时四十五分」→ 22:15，次日更正「到十点零五」。
        //   这条断言就是拦住"照用户口述的时长去算末点"这个错。）
        assertEquals(
            "20:30-22:05",
            selfStudyLabel(UNIT_TIMES.getValue(10).first, UNIT_TIMES.getValue(11).second),
        )
        // 起点缺前导零也要补齐，否则跟课节列对不齐
        assertEquals("09:30-11:05", selfStudyLabel("9:30", "11:05"))
        // 只有起点 → 只给起点，**不编造**结束时间（跟 timeLabel 同规矩）
        assertEquals("08:45", selfStudyLabel("08:45", ""))
        assertEquals("08:45", selfStudyLabel("08:45", "08:45"))
        assertEquals("", selfStudyLabel("", ""))
        // 列宽守卫：不许超过 `HH:mm-HH:mm`
        listOf(selfStudyLabel("08:45", "09:15"), selfStudyLabel("20:30", "22:05")).forEach {
            assertTrue("「$it」超过 11 字符会把左列撑爆", it.length <= 11)
        }
    }

    /**
     * 列宽守卫：左列从「09:30」变成「09:30-11:05」，宽了近一倍。
     *
     * 这里钉的是**不会把它撑爆**的那条线：线上真实标签渲染出来必须 ≤ 11 个字符
     * （= `HH:mm-HH:mm`）。多一个字就说明函数在往里塞别的东西（节次、教室…），
     * 那正是列宽会被挤、文字会被截断的成因 —— 用户看到的就是"还是不齐/又看不全"。
     */
    @Test
    fun `左列标签不许超过一个时间范围的宽度`() {
        val real = listOf(
            "1-2 节 · 09:30-11:05", "4-5 节 · 12:15-13:50", "6-7 节 · 16:00-17:35",
            "8-9 节 · 17:55-19:30", "10-11 节 · 20:30-22:05", "9:30-11:05", "6", "1-2 节",
        )
        real.forEach { input ->
            val out = timeLabel(input)
            assertTrue("「$input」→「$out」超过 11 字符，列会被撑宽", out.length <= 11)
            if (out.contains(':')) {
                assertTrue("「$input」→「$out」里的钟点必须补齐前导零（否则同一列对不齐）",
                    out.split("-").all { it.length == 5 && it[2] == ':' })
            }
        }
    }

    @Test
    fun `点开任务能看到完成方法和视频链接_且不会误标完成`() {
        // 用户诉求：点开这一项能看到"怎么下手"，有视频就要附链接。
        // 数据来源必须是**真实形状**：种子库里 [37] 盲打练习本来就是 3 步骤 + 5 资料，
        // 但"每日"任务里**没有视频** —— 所以自己补一条 kind='video' 的 B 站资源，
        // 专门把"有视频就附链接"这条路走通。列名都取代码里真读过的字段，不猜。
        val db = app().db
        val firstTask = runBlocking {
            app().seedJob?.join()
            loadToday(db).tasks.firstOrNull()
        }
        if (firstTask == null) return          // 种子里今天没任务就不测（不假装通过）
        val taskId = firstTask.id

        runBlocking {
            val raw = db.openHelper.writableDatabase
            // 本地实体是 @PrimaryKey val id: Int 且**没有 autoGenerate**，也没有 uid 列
            // （uid 只存在于服务端库）→ 必须自己给 id，且不能塞 uid。
            raw.execSQL(
                "INSERT INTO study_steps (id, task_id, seq, text, minutes, kind) VALUES (990001, ?, 1, ?, 25, 'learn')",
                arrayOf(taskId, "测试用第一步：打开题目列表"),
            )
            raw.execSQL(
                "INSERT INTO resources (id, task_id, kind, title, url, source, why, http, embed, sort) " +
                    "VALUES (990002, ?, 'video', ?, ?, 'bilibili（B站）', ?, 200, 1, 0)",
                arrayOf(
                    taskId,
                    "测试用视频：入门讲解",
                    "https://www.bilibili.com/video/BV1vyzXB6Eps/",
                    "边看边跟着敲，比自己啃文档更容易坚持。",
                ),
            )
        }

        rule.setContent { CampusTheme { TodayScreen(db) } }
        waitFor(firstTask.title)
        // 点整行 → 详情（上一版这里是点一个小按钮就地摊开，现在是同一份数据进详情层）
        tap(firstTask.title)
        waitFor("任务详情")

        assertTrue(
            "详情里要能看到完成方法的第一步",
            rule.onAllNodesWithText("测试用第一步：打开题目列表", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "有视频就要能看到视频条目（标题）",
            rule.onAllNodesWithText("测试用视频：入门讲解", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "视频要给可点的入口（播放 ▶）",
            rule.onAllNodesWithText("播放", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        // 最关键的一条：**打开详情不能改变状态** ——
        // 完成只发生在详情里的「完成」按钮上（那条链路由本文件第一条用例钉死）。
        assertFalse("点开详情不能顺手把任务标成完成", isDone(taskId))

        // 关掉 → 回到列表，详情里的内容跟着消失
        tap("关闭")
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("测试用第一步：打开题目列表", substring = true).fetchSemanticsNodes().isEmpty()
        }
    }
}
