package top.ccbase.campus.school

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
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.EamsLocalImport
import top.ccbase.campus.net.SchoolImportPolicy

/**
 * 粘贴导入的**落库**验收 —— 只测到解析不够，学生要的是课表里真出现这门课。
 *
 * 这条链路必须自己证明三件事：
 *  ① 解析出的 activities 能被白名单整份放行（不丢字段、不塞非法字段）；
 *  ② EamsLocalImport 真的写进了库（课程数/时段数对得上）；
 *  ③ 节次被作息表换算成了钟点，且周次串原样落库（不然课表页显示"没有周次"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulePasteImportTest {

    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val pasted =
        "<table>" +
            "<tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>" +
            "<tr><td>第1-2节</td><td>高等数学A(1)<br>教师：张三<br>地点：逸夫楼201<br>1-16周</td>" +
            "<td></td><td></td></tr>" +
            "<tr><td>第3-4节</td><td>线性代数<br>教师：李四<br>地点：C3-105<br>1-5,7-11单周</td>" +
            "<td>大学英语<br>教师：王五<br>地点：文成楼125<br>2-16双周</td><td></td></tr>" +
            "</table>"

    @Test
    fun `粘贴解析后能真的写进课表`() = runBlocking {
        val parsed = ScheduleGridImport.parse(pasted)
        assertTrue("解析阶段就该认出课程：${parsed.warnings}", parsed.courses.isNotEmpty())

        val json = SchoolImportPolicy.activityAllowlist(parsed.activitiesJson())
        val res = EamsLocalImport.import(db, json)
        assertTrue("导入未生效", res.imported)
        assertEquals(3, res.counts["courses"])
        assertEquals(3, res.counts["slots"])

        val courses = db.dao().allCoursesOnce()
        assertEquals(setOf("高等数学A(1)", "线性代数", "大学英语"), courses.map { it.name }.toSet())

        val math = courses.first { it.name == "高等数学A(1)" }
        assertEquals("张三", math.teacher)

        val slot = db.dao().slotsOfCourse(math.id).first().single()
        assertEquals(1, slot.weekday)
        assertEquals(1, slot.p_start)
        assertEquals(2, slot.p_end)
        assertEquals("逸夫楼201", slot.room)
        assertEquals("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16", slot.weeks)
        // 节次 → 钟点：走作息表换算，不能留空
        assertNotNull("节次没换算成时间", slot.time_text)
        assertTrue("时间格式不对：${slot.time_text}", slot.time_text!!.matches(Regex("\\d{2}:\\d{2}-\\d{2}:\\d{2}")))

        val lin = courses.first { it.name == "线性代数" }
        val linSlot = db.dao().slotsOfCourse(lin.id).first().single()
        assertEquals("1,3,5,7,9,11", linSlot.weeks)
        assertEquals(3, linSlot.p_start)
        assertEquals(4, linSlot.p_end)
    }

    @Test
    fun `空内容不会覆盖已有课表`() = runBlocking {
        val seed = ScheduleGridImport.parse(pasted).activitiesJson()
        EamsLocalImport.import(db, SchoolImportPolicy.activityAllowlist(seed))
        val before = db.dao().allCoursesOnce().size

        val empty = ScheduleGridImport.parse("随便一段没有课表的话")
        assertTrue(empty.isEmpty)
        val res = EamsLocalImport.import(db, SchoolImportPolicy.activityAllowlist(empty.activitiesJson()))
        assertTrue("空课表不该被当成导入成功", !res.imported)
        assertEquals("旧课表被清掉了", before, db.dao().allCoursesOnce().size)
    }
}
