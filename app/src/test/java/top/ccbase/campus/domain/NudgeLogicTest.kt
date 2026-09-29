package top.ccbase.campus.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import java.time.LocalDateTime

/**
 * 提醒/静音排程的交叉验证。
 *
 * 为什么这块最需要测：闹钟算错是**静默失败** —— 不崩不报错，只会表现为
 * "上课了手机没静音"，或者更糟"下课后还在静音、漏掉重要来电"。
 * 本机没模拟器，我点不了界面，只能把逻辑做成纯函数在这里对账。
 *
 * 基准取自 seed.json 里各课的真实周次区间：
 *   周三 09:30(3~19) / 11:25(3~19) / 16:00(3~11) / 17:55(10~15)
 *   周四 20:30(7~14)      周五 09:30(3~15) / 12:15(3~19) / 16:00(3~11) / 17:55(3~11)
 *   周一 09:30(3~19) / 12:15(3~19) / 16:00(10~15)     周二 11:25(3~15) / 16:00(3~11) / 16:00(13~16)
 *   周六 周日 无正课
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NudgeLogicTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context
    private lateinit var slots: List<Slot>
    private val start = "2026-08-31"   // 学期第 1 周周一

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries().build()
        slots = runBlocking {
            SeedImporter.import(db, SeedLoader.load(ctx))
            db.dao().slots().first()
        }
    }

    @After
    fun tearDown() = db.close()

    private fun next(s: String) = nextClassAt(slots, start, LocalDateTime.parse(s))

    // ---------------------------------------------------------- 时段解析

    @Test
    fun `时段解析能吃正常写法也扛得住脏数据`() {
        assertEquals(9, parseSpan("09:30-11:05")!!.first.hour)
        assertEquals(30, parseSpan("09:30-11:05")!!.first.minute)
        assertEquals(11, parseSpan("09:30-11:05")!!.second.hour)
        assertEquals(5, parseSpan("09:30-11:05")!!.second.minute)
        assertNotNull("个位数小时也应能解析", parseSpan("9:30-11:05"))
        assertNull("没有横杠 → null，不能抛异常", parseSpan("09:30"))
        assertNull("结束早于开始 → null", parseSpan("11:05-09:30"))
        assertNull("空值 → null", parseSpan(null))
    }

    // ---------------------------------------------------------- 下一节课

    @Test
    fun `早上找当天的第一节课`() {
        val r = next("2026-09-16T08:00")   // 周三 第3周
        assertNotNull(r)
        assertEquals(LocalDateTime.parse("2026-09-16T09:30"), r!!.first)
    }

    @Test
    fun `中午找当天的下一节`() {
        val r = next("2026-09-16T12:00")
        assertEquals(LocalDateTime.parse("2026-09-16T16:00"), r!!.first)
    }

    @Test
    fun `周四那节课在第3周还没开课要跳到周五`() {
        // 周四只有 20:30 那节(7~14周)，第3周不满足 → 应跳到周五 09:30
        val r = next("2026-09-16T18:00")
        assertEquals(LocalDateTime.parse("2026-09-18T09:30"), r!!.first)
    }

    @Test
    fun `第1周没课要能跨周找到开课那天`() {
        // 2026-09-01 是第 1 周周二，第 1~2 周所有课都没开
        val r = next("2026-09-01T00:00")
        assertNotNull("不能因为本周没课就返回空", r)
        assertEquals("第一节课应是第 3 周周一 09:30", LocalDateTime.parse("2026-09-14T09:30"), r!!.first)
    }

    @Test
    fun `周次过滤在正向也要对 —— 第11周周一多出一节16点的课`() {
        // 周一 16:00 那节只在 10~15 周：第 11 周周一 = 2026-11-09
        val r = next("2026-11-09T13:00")
        assertEquals(LocalDateTime.parse("2026-11-09T16:00"), r!!.first)
        // 同样时刻在第 4 周就没有这一节 → 应跳到周二
        val r4 = next("2026-09-21T13:00")   // 第 4 周周一
        assertEquals(LocalDateTime.parse("2026-09-22T11:25"), r4!!.first)
    }

    @Test
    fun `结课后没有课要找得到底而不是死循环`() {
        // 第 19 周之后所有课都结课了
        val r = next("2027-01-20T00:00")
        assertNull("没有课就该返回 null，不能无限往后找", r)
    }

    // ---------------------------------------------------------- 静音窗口

    @Test
    fun `未来一周的静音窗口数量与基准一致`() {
        val w = nudgeWindows(slots, start, LocalDateTime.parse("2026-09-16T00:00"), daysAhead = 7, leadMinutes = 1)
        // 周三3节(17:55那节没开) + 周四0节 + 周五4节 + 周六日0 + 周一两节 + 周二两节 = 11
        assertEquals(11, w.size)
        assertEquals("第一个窗口应比第一节课早 1 分钟", LocalDateTime.parse("2026-09-16T09:29"), w.first().start)
        assertEquals("窗口结束应等于下课时间", LocalDateTime.parse("2026-09-16T11:05"), w.first().end)
        assertEquals("最后一个窗口是周二 16:00 那节之前", LocalDateTime.parse("2026-09-22T15:59"), w.last().start)
        assertEquals(LocalDateTime.parse("2026-09-22T17:35"), w.last().end)
    }

    @Test
    fun `窗口按时间严格升序且不重复`() {
        val w = nudgeWindows(slots, start, LocalDateTime.parse("2026-09-16T00:00"), daysAhead = 14)
        assertEquals("时间应严格递增", w.size, w.map { it.start }.distinct().size)
        assertEquals(true, w.zipWithNext().all { it.first.start < it.second.start })
    }

    @Test
    fun `提前量可调 —— 改成提前10分钟整列窗口都跟着移`() {
        val a = nudgeWindows(slots, start, LocalDateTime.parse("2026-09-16T00:00"), daysAhead = 3, leadMinutes = 1)
        val b = nudgeWindows(slots, start, LocalDateTime.parse("2026-09-16T00:00"), daysAhead = 3, leadMinutes = 10)
        assertEquals(a.size, b.size)
        assertEquals(9, java.time.Duration.between(b.first().start, a.first().start).toMinutes())
    }

    /**
     * 线上形状必须也能排出静音窗口。
     *
     * 真实事故（2026-09-17 自查发现）：服务端的 `slot.time_text` 是「周一 1-2节」，
     * **不带时钟时间**；老实现用 `parseSpan(time_text)` 取开始时间 → 解析失败 →
     * `nextClassAt` 一门课都找不到 → `silenceWindows` 返回空 → Rescheduler 排 0 个闹钟。
     * 也就是说：课前自动静音在真实课表下从来没生效过（模板 seed 是时钟串，所以单测绿）。
     * 修法是按节次查作息表，这条用例喂的就是线上形状。
     */
    @Test
    fun `线上形状_只有节次没有时钟时间_也要排得出静音窗口`() {
        val live = listOf(
            Slot(id = 1, weekday = 1, course_id = 7, p_start = 1, p_end = 2,
                time_text = "周一 1-2节", room = "A楼406", week_from = 3, week_to = 19),
        )
        val span = slotSpan(live[0])
        assertNotNull("线上形状必须算得出时钟时间（否则静音功能是死的）", span)
        assertEquals("1-2 节 = 09:30 开始", "09:30", hhmm(span!!.first))
        assertEquals("1-2 节 = 11:05 结束", "11:05", hhmm(span.second))

        val w = nudgeWindows(
            live, "2026-08-31",
            LocalDateTime.parse("2026-09-17T08:00"), daysAhead = 7, leadMinutes = 1,
        )
        assertEquals("下一次上课是 09-21（周一）第 1-2 节，应该刚好排出一个窗口", 1, w.size)
        assertEquals("静音要提前 1 分钟", LocalDateTime.parse("2026-09-21T09:29"), w[0].start)
        assertEquals("静音持续到这节课下课", LocalDateTime.parse("2026-09-21T11:05"), w[0].end)
    }
}
