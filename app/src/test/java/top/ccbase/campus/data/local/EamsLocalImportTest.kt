package top.ccbase.campus.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.remote.PlanApplier
import top.ccbase.campus.domain.activitiesFrom

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EamsLocalImportTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun realActivities(): String {
        val raw = javaClass.classLoader!!.getResourceAsStream("eams/print_data.json")!!
            .bufferedReader().readText()
        return activitiesFrom(raw)!!.toString()
    }

    @Test
    fun `真实教务课表导入为九门课十九段并保留精确周次`() = runBlocking {
        val result = EamsLocalImport.import(db, realActivities())

        assertTrue("真样本必须导入成功", result.imported)
        assertEquals(9, result.counts["courses"])
        assertEquals(19, result.counts["slots"])
        assertEquals(9, db.dao().courses().first().size)
        assertEquals(19, db.dao().slots().first().size)
        assertEquals(PlanApplier.LOCAL, db.dao().metaGet(PlanApplier.K_SOURCE))
        assertEquals("1", db.dao().metaGet(PlanApplier.K_ONBOARD))

        val friday = db.dao().slots().first()
            .filter { it.weekday == 5 && it.p_start == 1 }
        assertEquals("周五 1-2 节同一门课必须保留两段（换机房）", 2, friday.size)
        val sets = friday.mapNotNull { slot ->
            slot.weeks?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet()
        }
        assertEquals("两段精确周次集合不能一样", 2, sets.distinct().size)
        assertTrue(
            "精确周次必须稀疏且非连续区间",
            sets.any { s -> s.isNotEmpty() && (s.max() - s.min() + 1) != s.size },
        )
    }

    @Test
    fun `本地导入只替换课程时段_不碰任务打卡专注`() = runBlocking {
        db.dao().putCourses(listOf(Course(id = 1, name = "旧课")))
        db.dao().putSlots(listOf(Slot(id = 10, weekday = 1, course_id = 1, p_start = 1, p_end = 2)))
        val taskId = 100
        db.dao().markDone(TaskDone(task_id = taskId, day = "2026-10-01", at = "10:00"))
        db.dao().saveCheckin(Checkin(day = "2026-10-01", note = "在学", updated_at = "10:00"))
        db.dao().saveSession(Session(started_at = "20:00", ended_at = "20:30", minutes = 30, day = "2026-10-01"))

        EamsLocalImport.import(db, realActivities())

        assertEquals("完成记录不能被本地导入清掉", 1, db.dao().doneOn("2026-10-01").first().size)
        assertNotNull("打卡记录不能被本地导入清掉", db.dao().checkin("2026-10-01"))
        assertEquals(
            "专注记录不能被本地导入清掉",
            1,
            db.dao().recentActiveDays(10).count { it == "2026-10-01" },
        )
    }

    @Test
    fun `weekIndexes 缺失时按 weeksStr 展开范围`() {
        val json = """[
          {"courseCode":"A1","courseName":"大学物理","weekday":2,"startUnit":1,"endUnit":2,
           "weeksStr":"3~11","room":"A101","teachers":["王老师"]},
          {"courseCode":"A1","courseName":"大学物理","weekday":4,"startUnit":3,"endUnit":4,
           "weeksStr":"3,4,5","room":"A101","teachers":["王老师"]}
        ]"""
        val plan = EamsLocalImport.build(json)
        assertEquals(1, plan.courses.size)
        assertEquals(2, plan.slots.size)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9, 10, 11), plan.slots[0].weeks!!.split(",").map { it.toInt() })
        assertEquals(listOf(3, 4, 5), plan.slots[1].weeks!!.split(",").map { it.toInt() })
    }
}
