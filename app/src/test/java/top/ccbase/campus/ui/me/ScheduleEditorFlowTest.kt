package top.ccbase.campus.ui.me

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.ManualSchedule
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.ui.theme.CampusTheme

/**
 * 编辑课表的两条主诉（2026-10-21）：
 * ① 「没有课的卡片要空在那，支持点击即改」—— 点空卡片就能新增，且新增的课必须落在
 *    **那一节的节次**上（以前手动加的课没有 p_start，永远进不了周视图的格子）；
 * ② 「改一个地点同步到所有相关课程」—— 改一处，同一门课的其他时段一起变。
 *
 * 一切定位走 testTag：卡片上的文字是合并节点（「大学英语 / 编辑 / 第 1-2 节…」），
 * 按文字找会在合并列表上打偏；而且 Robolectric 的窗口只有 320×470px，
 * 不先 performScrollTo 的点击会落在视口外，看起来「点了没反应」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleEditorFlowTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var ctx: Context
    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java).allowMainThreadQueries().build()
        PeriodTableStore.resetForTest()
    }

    @After
    fun tearDown() = PeriodTableStore.resetForTest()

    @Test
    fun `点空的第1节就进到这张卡对应节次的新增表单`() {
        rule.setContent { CampusTheme { ScheduleEditor(ctx, db, {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("empty:unit:1").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("empty:unit:1").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("新增一条").performScrollTo().assertExists()
        rule.onNodeWithText("第 1 节 · 09:30-10:15").performScrollTo().assertExists()
    }

    @Test
    fun `空卡片新增的课落在那一节的节次上_自定义时间才写钟点`() {
        // 写回逻辑直接测：界面上点「保存」这条路已经有 ScheduleToolsTest 守着
        val f = EditState.newCourse(day = 2, unit = 3).copy(name = "线性代数")
        assertNull(f.validate(Seed()))
        val plan = f.applyTo(Seed())
        val slot = plan.slots.single()
        assertEquals("空卡片是第 3 节，新增的课就得是第 3 节", 3, slot.p_start)
        assertEquals(3, slot.p_end)
        assertEquals(2, slot.weekday)
        assertNull("按节次排的课不写死 time_text", slot.time_text)
        assertEquals("线性代数", plan.courses.single().name)
        assertEquals("默认全学期", 20, slot.weeks!!.split(',').size)

        val g = EditState.newCourse(day = 2, unit = 3)
            .copy(name = "书法", customTime = true, start = "19:00", end = "20:30")
        val custom = g.applyTo(Seed()).slots.single()
        assertEquals("自定义时间就把钟点写进 time_text", "19:00-20:30", custom.time_text)
        assertNull(custom.p_start)
    }

    @Test
    fun `新增自习默认取作息表的时间并结合同类地点`() {
        val plan = Seed(selfstudy = listOf(SelfStudy(1, 1, "早自习", "08:45", "09:15", "A301")))
        val f = EditState.newStudy(day = 3, kind = top.ccbase.campus.domain.PeriodTable.MORNING_STUDY, plan = plan)
        assertEquals("08:45", f.start)
        assertEquals("09:15", f.end)
        assertEquals("地点默认抄同类另一天", "A301", f.room)
        val next = f.applyTo(plan)
        assertEquals(2, next.selfstudy.size)
        assertEquals(3, next.selfstudy.first { it.weekday == 3 }.weekday)
    }

    @Test
    fun `一天里已经有早自习就不让再加第二条`() {
        val plan = Seed(selfstudy = listOf(SelfStudy(1, 1, "早自习", "08:45", "09:15", "A301")))
        val dup = EditState.newStudy(day = 1, kind = top.ccbase.campus.domain.PeriodTable.MORNING_STUDY, plan = plan)
        assertNotNull("重复的那天要挡住，别让课表里出现两张早自习", dup.validate(plan))
    }

    @Test
    fun `改一处地点可以同步到同一门课的所有时段`() {
        seedTwoSlotsSameCourse()
        rule.setContent { CampusTheme { ScheduleEditor(ctx, db, {}) } }
        // 默认显示今天；种子课排在周一，先切到周一
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("day:1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("day:1").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("slot:1500000001").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("slot:1500000001").performScrollTo().performClick()

        // 同一门课有 2 处 → 表单必须给出「同步到全部 2 条」这个选择
        rule.onNodeWithText("同步到全部 2 条").performScrollTo().assertExists()
        rule.onNodeWithText("地点（可选）").performTextClearance()
        rule.onNodeWithText("地点（可选）").performTextInput("II区308")
        rule.onNodeWithText("保存到本机").performScrollTo().performClick()
        waitForSave { runBlocking { db.dao().slots().first().all { it.room == "II区308" } } }
        assertTrue(runBlocking { db.dao().slots().first() }.all { it.room == "II区308" })
    }

    @Test
    fun `选只改这一条时其他时段不受影响`() {
        seedTwoSlotsSameCourse()
        rule.setContent { CampusTheme { ScheduleEditor(ctx, db, {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("day:1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("day:1").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("slot:1500000001").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("slot:1500000001").performScrollTo().performClick()
        rule.onNodeWithText("只改这一条").performScrollTo().performClick()
        rule.onNodeWithText("地点（可选）").performTextClearance()
        rule.onNodeWithText("地点（可选）").performTextInput("II区308")
        rule.onNodeWithText("保存到本机").performScrollTo().performClick()
        waitForSave { runBlocking { db.dao().slots().first().count { it.room == "II区308" } == 1 } }
        assertEquals(1, runBlocking { db.dao().slots().first() }.count { it.room == "II区308" })
        assertEquals(1, runBlocking { db.dao().slots().first() }.count { it.room == "I区212" })
    }

    @Test
    fun `没课的日子也留着卡片_点它按当天的节次新增`() {
        seedTwoSlotsSameCourse()
        rule.setContent { CampusTheme { ScheduleEditor(ctx, db, {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("day:1").fetchSemanticsNodes().isNotEmpty() }

        // 周一第 1-2 节有课、第 6-7 节有课；第 3 节应该是空卡
        rule.onNodeWithTag("day:1").performScrollTo().performClick()
        rule.onNodeWithTag("empty:unit:3").performScrollTo().assertExists()
        rule.onNodeWithTag("empty:unit:3").performClick()
        rule.onNodeWithText("第 3 节 · 11:25-12:10").performScrollTo().assertExists()
    }

    @Test
    fun `早晚自习的地点也能一次改完`() {
        val plan = Seed(
            selfstudy = listOf(
                SelfStudy(1, 1, "早自习", "08:45", "09:15", "A301"),
                SelfStudy(2, 2, "早自习", "08:45", "09:15", "A301"),
                SelfStudy(7, 1, "晚自习", "20:30", "22:05", "A301"),
            ),
        )
        val next = ManualSchedule.syncStudyPlace(plan, "早自习", "B102")
        assertEquals(listOf("B102", "B102"), next.selfstudy.filter { it.kind == "早自习" }.map { it.place })
        assertEquals("晚自习不该被动到", "A301", next.selfstudy.first { it.kind == "晚自习" }.place)
    }

    @Test
    fun `作息表关掉早自习并保存_已排的早自习会被清掉且要报条数`() {
        val plan = Seed(
            selfstudy = listOf(
                SelfStudy(1, 1, "早自习", "08:45", "09:15", "A301"),
                SelfStudy(2, 2, "早自习", "08:45", "09:15", "A301"),
                SelfStudy(7, 1, "晚自习", "20:30", "22:05", "A301"),
            ),
        )
        val table = top.ccbase.campus.domain.PeriodTable(
            morningStudy = top.ccbase.campus.domain.StudyWindow(enabled = false),
            eveningStudy = top.ccbase.campus.domain.StudyWindow(true, "19:30", "21:00"),
        )
        val (next, removed) = ManualSchedule.applyStudyWindow(plan, table)
        assertEquals("两条早自习都要清掉", 2, removed)
        assertEquals(listOf("晚自习"), next.selfstudy.map { it.kind })
        assertEquals("剩下的自习时间要按新作息改", "19:30", next.selfstudy.single().start)
        assertEquals("21:00", next.selfstudy.single().end)
    }

    @Test
    fun `同步地点不会碰别的课`() {
        val plan = Seed(
            courses = listOf(Course(1, "A课"), Course(2, "B课")),
            slots = listOf(
                Slot(10, 1, 1, p_start = 1, p_end = 1, room = "X"),
                Slot(11, 2, 1, p_start = 2, p_end = 2, room = "X"),
                Slot(12, 3, 2, p_start = 1, p_end = 1, room = "Y"),
            ),
        )
        val next = ManualSchedule.syncRoom(plan, 1, "Z")
        assertEquals(listOf("Z", "Z"), next.slots.filter { it.course_id == 1 }.map { it.room })
        assertEquals("别的课的地点保持原样", "Y", next.slots.first { it.course_id == 2 }.room)
    }

    /** 等保存落库；超时就把界面上的文字全打出来（弹出的说明就是原因，别猜） */
    private fun waitForSave(timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(30)
        }
        val texts = rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fail("保存没落库。屏幕上的文字：" + texts.joinToString(" | "))
    }

    private fun seedTwoSlotsSameCourse() = runBlocking {
        ManualSchedule.save(
            db,
            Seed(
                courses = listOf(Course(1500000001, "大学英语", teacher = "王")),
                slots = listOf(
                    Slot(1500000001, 1, 1500000001, p_start = 1, p_end = 2, room = "I区212", weeks = "1,2,3"),
                    Slot(1500000002, 1, 1500000001, p_start = 6, p_end = 7, room = "I区212", weeks = "1,2,3"),
                ),
            ),
        )
    }
}
