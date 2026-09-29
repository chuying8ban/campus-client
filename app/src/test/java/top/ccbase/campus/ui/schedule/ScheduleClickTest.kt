package top.ccbase.campus.ui.schedule

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 课表点击的**真点击**测试。
 *
 * 为什么必须有这一条：逻辑测试和渲染冒烟都不会发现"点了没反应" ——
 * 它们只证明页面画出来了。用户反馈的正是"点了没反应"，
 * 所以这里必须真的 performClick，再断言详情面板出现了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ScheduleClickTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            db.dao().putCourses(
                listOf(Course(id = 1, name = "数学分析（I）", short = "数学分析（I）A", credits = 5.0, teacher = "张三", week_from = 3, week_to = 19))
            )
            db.dao().putSlots(
                // time_text 用**线上真实形状**（周几 + 节次），不是「08:00-09:40」——
                // 老种子和服务端不是一回事，正是周视图画歪了却没被测出来的原因。
                listOf(Slot(id = 1, weekday = 1, course_id = 1, p_start = 1, p_end = 2, time_text = "周一 1-2节", room = "A楼I区301", week_from = 3, week_to = 19))
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun open() {
        rule.setContent { CampusTheme { ScheduleScreen(db = db) } }
        rule.waitForIdle()
    }

    /**
     * 有界等待某个文字出现，返回"最终到底有没有"。
     *
     * 详情面板里的教室／时间是 `LaunchedEffect { withContext(Dispatchers.IO) { 查 slots } }`
     * 读出来的，而 `rule.waitForIdle()` **不等 IO**（只等 Recomposer）。直接断言房间号会偶发失败
     * ——实测全量跑 3 次里挂 1 次，"详情面板没显示教室"，但面板其实开着，只是 slots 还没回来。
     * 这里等到就往下走，等不到（超时）也不抛，仍然由下面那条原始 assertTrue 报错。
     */
    private fun awaitText(text: String, timeoutMs: Long = 5_000): Boolean {
        runCatching {
            rule.waitUntil(timeoutMs) {
                rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        return rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * 翻到有课的那一周（课表默认停在当前周，当前周不一定有这门课）。
     *
     * 关键是「先等到箭头出现」：课表要先查一次库（IO）算出当前周，查完才渲染翻页箭头。
     * 以前这里直接 `onNodeWithText("›").performClick()` —— 箭头还没出来就抛
     * 「could not find any node satisfying ... '›'」，全量跑时偶发（实测 3 次挂 1 次，
     * 和本文件 awaitText 注释里那次是同一类问题：waitForIdle 不等 IO）。
     * 等不到就当作没得翻，直接返回，由调用方下面那条断言报真实原因（不掩盖失败）。
     */
    private fun advanceToWeekWith(needle: String) {
        var guard = 0
        while (rule.onAllNodesWithText(needle).fetchSemanticsNodes().isEmpty() && guard < 20) {
            if (!awaitText("›", timeoutMs = 2_000)) return
            rule.onAllNodesWithText("›")[0].performClick()   // 可能有多个箭头，取第一个
            rule.waitForIdle()
            guard++
        }
    }

    /**
     * 用户的硬需求（原话）：「我希望竖屏就能看到课程名字和教室的信息」。
     *
     * 竖屏默认**就是周视图**（教务那张表）——用户要求"做成教务系统那种形式的"。
     * 早先竖屏默认列表，是因为老版格子 40dp、把课程名压成四行；
     * 现在行按节次排、课块跨节、块里写全名+教室+教师，窄屏也读得下来。
     * 这条测试钉的就是"不点任何东西，竖屏第一眼就有课程名和教室"。
     */
    @Test
    fun `竖屏第一眼就能看到课程名和教室`() {
        open()
        advanceToWeekWith("数学分析（I）")
        assertTrue("竖屏默认视图里看不到完整课程名", awaitText("数学分析（I）"))
        assertTrue("竖屏默认视图里看不到教室", awaitText("A楼I区301"))
        // 列表的时间列走的是节次（slotLabel），不是 time_text ——
        // 线上 time_text 是「周一 1-2节」这种带星期的串，直接显示会把星期重复一遍。
        // 左列改成**一节一行**（用户：「左边不要出现几节课连一块的情况」），
        // 所以钉的是单节钟点，而不是老的「1-2 节」段标签。
        assertTrue("竖屏默认视图里看不到单节上课时间（第 1 节 09:30-10:15）", awaitText("09:30-10:15"))
        assertTrue("竖屏默认视图里看不到教师", awaitText("张三"))
    }

    /**
     * 用户原话：「周视图要做成教务系统里面的课表那种形式的，这样直观」。
     *
     * 钉子：**不点任何按钮**，竖屏第一眼就该是那张表。
     * 「09:30-11:05」只有周视图的左列会写（列表视图只写「1-2 节」），
     * 所以它能区分"默认是表格"还是"默认是列表" —— 改默认视图这条就红。
     */
    @Test
    fun `竖屏默认就是教务那张表`() {
        open()
        advanceToWeekWith("数学分析（I）")
        assertTrue(
            "竖屏默认不是周视图（没有左列的上课时间）",
            awaitText("09:30-10:15"),
        )
        assertTrue("表格里应该有星期表头", awaitText("一"))
        assertTrue("课块里要写出教室", awaitText("A楼I区301"))
    }

    @Test
    fun `点课表格子_必须弹出课程详情`() {
        open()
        // 课表页是**异步读库**出内容的：不先等页面出来就点，点到的是「正在读取课表…」，
        // 节点还不存在 → 偶发 AssertionError（发布闸门 2026-09-17 就这么红过一次）。
        assertTrue("课表页还没读出来（异步读库未完成）", awaitText("周视图"))
        // 竖屏默认已是周视图；这行显式点一下，保证无论如何都在表格那条路径上
        rule.onAllNodesWithText("周视图")[0].performClick()
        rule.waitForIdle()
        advanceToWeekWith("数学分析（I）")
        assertTrue("翻不到有课的周次，测试前提不成立", rule.onAllNodesWithText("数学分析（I）").fetchSemanticsNodes().isNotEmpty())

        rule.onAllNodesWithText("数学分析（I）")[0].performClick()
        rule.waitForIdle()

        // 详情面板的独有内容
        assertTrue(
            "点了课表格子，详情面板没出现 —— 这就是用户反馈的「点了没反应」",
            awaitText("上课时间")
        )
        // 教师与学分是拼在一行显示的（"张三 · 5 学分"），所以要按子串匹配
        assertTrue(
            "详情面板没显示教师",
            awaitText("张三")
        )
        assertTrue(
            "详情面板没显示教室",
            awaitText("A楼I区301")
        )
    }
}
