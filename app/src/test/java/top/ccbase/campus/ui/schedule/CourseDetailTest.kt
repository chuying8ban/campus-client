package top.ccbase.campus.ui.schedule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.local.Task

/**
 * 课程详情的数据层测试。
 *
 * 最要紧的一条：详情里**只能出现这门课的东西**。
 * 课表页按格子点进去时，如果查询漏了 course_id 过滤，
 * 用户看到的会是一门课挂着别的课的任务 —— 这类"串了"的错误肉眼很难发现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CourseDetailTest {

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

    private fun seed() = runBlocking {
        val d = db.dao()
        d.putCourses(
            listOf(
                Course(id = 1, name = "数学分析（I）", short = "数学分析（I）A", credits = 5.0, teacher = "张三"),
                Course(id = 2, name = "程序设计B", short = "程序设计基础（B）", credits = 3.0, teacher = "李四"),
            )
        )
        d.putSlots(
            listOf(
                Slot(id = 1, weekday = 1, course_id = 1, p_start = 1, p_end = 2, time_text = "08:00-09:40", room = "A楼I区301"),
                Slot(id = 2, weekday = 3, course_id = 1, p_start = 3, p_end = 4, time_text = "10:00-11:40", room = "A楼I区302"),
                Slot(id = 3, weekday = 2, course_id = 2, p_start = 1, p_end = 2, time_text = "08:00-09:40", room = "C6-101"),
            )
        )
        d.putTasks(
            listOf(
                Task(id = 1, phase = "第1周", title = "极限习题", course_id = 1),
                Task(id = 2, phase = "第1周", title = "指针练习", course_id = 2),
            )
        )
    }

    @Test
    fun `课程的时间段只返回这门课的`() = runBlocking {
        seed()
        val got = db.dao().slotsOfCourse(1).first()
        assertEquals(2, got.size)
        assertTrue("混进了别的课的时间段", got.all { it.course_id == 1 })
        assertFalse("别的课的教室不该出现", got.any { it.room?.contains("C6") == true })
    }

    @Test
    fun `课程的任务只返回这门课的`() = runBlocking {
        seed()
        val got = db.dao().tasksOfCourse(1).first()
        assertEquals(1, got.size)
        assertEquals("极限习题", got.single().title)
    }

    @Test
    fun `没有对应数据时返回空表而不是报错`() = runBlocking {
        seed()
        assertEquals(0, db.dao().slotsOfCourse(999).first().size)
        assertEquals(0, db.dao().tasksOfCourse(999).first().size)
    }

    // ------------------------------------------------------------ 周次文案

    @Test
    fun `周次文案_任何一种缺失都不许输出 null`() {
        val cases = listOf(
            Course(id = 1, name = "x", week_from = 3, week_to = 19) to "第 3~19 周",
            Course(id = 2, name = "x", week_from = 3, week_to = 3) to "第 3 周",
            Course(id = 3, name = "x", week_from = 3) to "第 3 周起",
            Course(id = 4, name = "x", week_to = 19) to "第 19 周止",
            Course(id = 5, name = "x") to "周次未标注",
        )
        cases.forEach { (c, want) ->
            val got = weeksText(c)
            assertEquals(want, got)
            assertFalse("文案里出现了 null：$got", got.contains("null"))
        }
    }

    @Test
    fun `学分去掉多余的_0`() {
        assertEquals("5", fmt(5.0))
        assertEquals("3", fmt(3.0))
        assertTrue(fmt(2.5).startsWith("2.5"))
        assertEquals("0", fmt(0.0))
    }
}
