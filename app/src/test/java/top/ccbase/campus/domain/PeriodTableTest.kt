package top.ccbase.campus.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.data.local.Slot

/**
 * 作息时间表（2026-10-21 用户要求可改）。
 *
 * 钉三件事：
 * ① 默认值必须**逐分钟**复现教务官方作息 —— 用户不动它，课表上的时间就一点都不该变；
 * ② 规则改了，算出来的节次钟点要跟着对（含大课间只作用在选中的节后）；
 * ③ 离谱的配置（全 0 节、时间倒挂、大课间放在最后一节后）要在保存前被挡住，
 *    否则会变成「静音 / 课前提醒排不出来」那种静默失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeriodTableTest {

    @After
    fun tearDown() = PeriodTableStore.resetForTest()

    @Test
    fun `默认作息逐分钟等于教务官方单节时间`() {
        val units = PeriodTable().units()
        assertEquals(12, units.size)
        val expected = listOf(
            "09:30" to "10:15", "10:20" to "11:05", "11:25" to "12:10",
            "12:15" to "13:00", "13:05" to "13:50", "16:00" to "16:45",
            "16:50" to "17:35", "17:55" to "18:40", "18:45" to "19:30",
            "20:30" to "21:15", "21:20" to "22:05", "22:10" to "22:55",
        )
        units.forEachIndexed { i, u ->
            assertEquals("第 ${i + 1} 节开始", expected[i].first, u.start)
            assertEquals("第 ${i + 1} 节结束", expected[i].second, u.end)
        }
        assertEquals("上午", units[0].segment)
        assertEquals("下午", units[5].segment)
        assertEquals("晚上", units[9].segment)
    }

    @Test
    fun `改节长与课间会重算_大课间只听选中的节`() {
        val t = PeriodTable(
            morningStart = "08:00",
            morningCount = 4,
            afternoonCount = 0,
            eveningCount = 0,
            classMinutes = 40,
            breakMinutes = 10,
            bigBreakAfter = listOf(2),
            bigBreakMinutes = 30,
        )
        val u = t.units()
        assertEquals(4, u.size)
        assertEquals("08:00" to "08:40", u[0].start to u[0].end)
        assertEquals("第 1 节后是普通课间", "08:50", u[1].start)
        assertEquals("08:50" to "09:30", u[1].start to u[1].end)
        assertEquals("第 2 节后是大课间 30 分钟", "10:00", u[2].start)
        assertEquals("10:00" to "10:40", u[2].start to u[2].end)
        assertEquals("第 3 节后回到普通课间", "10:50", u[3].start)
    }

    @Test
    fun `段与段之间的空白由下一段的开始时间决定`() {
        val t = PeriodTable(morningCount = 2, afternoonCount = 1, eveningCount = 0)
        val u = t.units()
        assertEquals(3, u.size)
        assertEquals("上午最后一节 11:05 下课", "11:05", u[1].end)
        assertEquals("下午第一节 16:00 才开始（中间全是空白）", "16:00", u[2].start)
        assertEquals("下午", u[2].segment)
    }

    @Test
    fun `早晚自习可以设成没有`() {
        val t = PeriodTable(
            morningStudy = StudyWindow(enabled = false),
            eveningStudy = StudyWindow(enabled = true, "19:00", "21:00"),
        )
        assertFalse(t.studyEnabled(PeriodTable.MORNING_STUDY))
        assertTrue(t.studyEnabled(PeriodTable.EVENING_STUDY))
        assertEquals("19:00" to "21:00", t.study(PeriodTable.EVENING_STUDY)!!.start to t.study(PeriodTable.EVENING_STUDY)!!.end)
        assertNull("不认识的类型不编造时间窗", t.study("午休"))
        assertNull("作息合法：自习关掉不算错", t.validate())
    }

    @Test
    fun `离谱配置在保存前被挡住`() {
        assertNotNull("一天 0 节课", PeriodTable(morningCount = 0, afternoonCount = 0, eveningCount = 0).validate())
        assertNotNull("每节 0 分钟", PeriodTable(classMinutes = 0).validate())
        assertNotNull("上午第一节写成乱码", PeriodTable(morningStart = "九点半").validate())
        assertNotNull("大课间挂在最后一节之后", PeriodTable(morningCount = 1, afternoonCount = 0, eveningCount = 0, bigBreakAfter = listOf(1)).validate())
        assertNotNull("晚自习结束早于开始", PeriodTable(eveningStudy = StudyWindow(true, "22:00", "21:00")).validate())
        assertNull("正常改一套", PeriodTable(morningStart = "08:00", classMinutes = 40).validate())
    }

    @Test
    fun `越界输入会被夹回合法范围`() {
        val n = PeriodTable(morningCount = 99, classMinutes = 5, breakMinutes = -3, bigBreakAfter = listOf(0, 2, 2)).normalized()
        assertEquals(12, n.morningCount)
        assertEquals(10, n.classMinutes)
        assertEquals(0, n.breakMinutes)
        assertEquals(listOf(2), n.bigBreakAfter)
    }

    @Test
    fun `存盘再读回来是同一张表_坏数据退回默认`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java).allowMainThreadQueries().build()
        val custom = PeriodTable(morningStart = "08:10", classMinutes = 50, eveningCount = 0)
        PeriodTableStore.save(db, custom)
        PeriodTableStore.resetForTest()
        assertEquals("内存里被拨回默认了", "09:30", PeriodTableStore.table.morningStart)
        PeriodTableStore.load(db)
        assertEquals("从库里读回来的是用户改过的那套", custom.normalized(), PeriodTableStore.table)
        assertEquals("坏 JSON 不能把课表搞成空白", PeriodTable(), PeriodTableStore.decode("{ 这不是 json"))
        assertEquals("空串就走默认", PeriodTable(), PeriodTableStore.decode(null))
    }

    @Test
    fun `作息表一改_周视图左列的时间和跨节时段的钟点立刻跟着变`() {
        PeriodTableStore.resetForTest()
        assertEquals("09:30-10:15", unitTimeLabel(1))
        val slot = Slot(id = 1, weekday = 1, course_id = 1, p_start = 1, p_end = 2)
        assertEquals("09:30" to "11:05", hhmm(slotSpan(slot)!!.first) to hhmm(slotSpan(slot)!!.second))

        PeriodTableStore.setForTest(PeriodTable(morningStart = "08:00", classMinutes = 40, breakMinutes = 10, bigBreakAfter = emptyList()))
        assertEquals("08:00-08:40", unitTimeLabel(1))
        assertEquals("按节次排的时段没有 time_text，钟点就该由新作息算", "08:00" to "09:30",
            hhmm(slotSpan(slot)!!.first) to hhmm(slotSpan(slot)!!.second))
    }
}
