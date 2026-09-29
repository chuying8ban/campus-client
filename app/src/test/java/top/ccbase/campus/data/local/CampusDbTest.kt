package top.ccbase.campus.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地库的真测试 —— 在 JVM 上跑（Robolectric），**不需要真机或模拟器**。
 *
 * 为什么值得专门搭这套：这台机器跑不了安卓模拟器（不在 kvm 组、sudo 要密码），
 * 所以"原生层我验不了"一直是最大的风险。Room/DAO/迁移这一层是纯逻辑，
 * Robolectric 能在 JVM 里真跑 —— 于是数据库层从"我推断它没问题"变成"我真跑过它没问题"。
 * P1 的所有数据逻辑（今日该显示什么、连续打卡几天、级联删除对不对）都能在这里先验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CampusDbTest {

    private lateinit var db: CampusDb
    private lateinit var dao: CampusDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `课表：课程与时段能写能读，weekday 过滤正确`() = runBlocking {
        dao.putCourses(
            listOf(
                Course(id = 1, name = "数学分析（I）", short = "数学分析（I）A", credits = 5.0, is_focus = 1, sort = 1),
                Course(id = 2, name = "程序设计基础（B）程序设计B", short = "程序设计B", credits = 4.0, is_focus = 1, sort = 2),
            )
        )
        dao.putSlots(
            listOf(
                Slot(id = 1, course_id = 1, weekday = 1, p_start = 1, p_end = 2, time_text = "09:30-11:05", room = "A楼119", week_from = 1, week_to = 18),
                Slot(id = 2, course_id = 2, weekday = 3, p_start = 3, p_end = 4, time_text = "11:25-13:50", room = "A楼406", week_from = 1, week_to = 18),
            )
        )

        val courses = dao.courses().first()
        assertEquals(2, courses.size)
        assertEquals("数学分析（I）", courses[0].name)
        assertEquals(5.0, courses[0].credits, 0.001)
        assertEquals(1, courses[0].is_focus)

        // 周一只有 1 节，周三只有 1 节 —— 按 weekday 过滤必须真的分得开
        val mon = dao.slotsOfDay(1).first()
        assertEquals(1, mon.size)
        assertEquals("A楼119", mon[0].room)
        val wed = dao.slotsOfDay(3).first()
        assertEquals(1, wed.size)
        assertEquals("A楼406", wed[0].room)
        assertTrue("周二不该有课", dao.slotsOfDay(2).first().isEmpty())
    }

    @Test
    fun `自习：早自习晚自习同一天可以并存，重复插入被唯一约束覆盖`() = runBlocking {
        dao.putSelfStudy(
            listOf(
                SelfStudy(id = 1, weekday = 1, kind = "早自习", start = "08:45", end = "09:15", place = "A楼I区301"),
                SelfStudy(id = 2, weekday = 1, kind = "晚自习", start = "20:30", end = "22:05", place = "A楼I区301"),
            )
        )
        assertEquals(2, dao.selfStudyOfDay(1).first().size)

        // 同 (weekday, kind) 再插一条 → REPLACE，不该变成 3 条
        dao.putSelfStudy(
            listOf(SelfStudy(id = 9, weekday = 1, kind = "早自习", start = "08:50", end = "09:20", place = "A楼I区301"))
        )
        val rows = dao.selfStudyOfDay(1).first()
        assertEquals("唯一约束应让同(weekday,kind)只留一条", 2, rows.size)
        assertEquals("08:50", rows.first { it.kind == "早自习" }.start)
    }

    @Test
    fun `任务：步骤与资源按 task_id 挂得住，能查到自己的那些`() = runBlocking {
        dao.putTasks(
            listOf(
                Task(id = 1, phase = "第3-4周", phase_order = 1, title = "数学分析（I）A：极限专项", track = "正课", active = 1, sort = 1),
                Task(id = 2, phase = "第3-4周", phase_order = 1, title = "程序设计基础（B）：指针吃透", track = "正课", active = 1, sort = 2),
            )
        )
        dao.putSteps(
            listOf(
                StudyStep(id = 1, task_id = 1, seq = 1, text = "看讲师甲极限 1-6 集", minutes = 60, kind = "watch"),
                StudyStep(id = 2, task_id = 1, seq = 2, text = "做课后 1.4 全部题", minutes = 45, kind = "practice"),
                StudyStep(id = 3, task_id = 2, seq = 1, text = "手写 strcpy/memcpy", minutes = 30, kind = "produce"),
            )
        )
        dao.putResources(
            listOf(
                Resource(id = 1, task_id = 1, kind = "video", title = "讲师甲 数学分析（I）", url = "https://www.bilibili.com/video/BV1bW411s7xp", source = "B站（讲师甲老师官方）", http = 200, embed = 1, sort = 1),
                Resource(id = 2, task_id = 2, kind = "doc", title = "菜鸟教程 C 指针", url = "https://www.runoob.com/cprogramming/c-pointers.html", source = "菜鸟教程", http = 200, sort = 1),
            )
        )

        val s1 = dao.stepsOfTask(1).first()
        assertEquals(2, s1.size)
        assertEquals("看讲师甲极限 1-6 集", s1[0].text)
        assertNull("新导入的步骤都该是未完成", s1[0].done_day)
        assertEquals(1, dao.stepsOfTask(2).first().size)

        val r1 = dao.resourcesOfTask(1).first()
        assertEquals(1, r1.size)
        assertTrue(r1[0].url.startsWith("https://"))
        assertEquals(2, dao.resources().first().size)
    }

    @Test
    fun `打卡：标记后能查到，取消后查不到`() = runBlocking {
        assertEquals(0, dao.isDone(1, "2026-09-16"))
        dao.markDone(TaskDone(task_id = 1, day = "2026-09-16", at = "2026-09-16T20:11:00"))
        assertEquals(1, dao.isDone(1, "2026-09-16"))
        assertEquals("别的日期不该被带上", 0, dao.isDone(1, "2026-09-15"))

        dao.unmarkDone(1, "2026-09-16")
        assertEquals(0, dao.isDone(1, "2026-09-16"))
    }

    @Test
    fun `统计：活跃天 = 专注记录 并 任务完成，且不含 checkins`() = runBlocking {
        dao.markDone(TaskDone(task_id = 1, day = "2026-09-16", at = "x"))
        dao.markDone(TaskDone(task_id = 2, day = "2026-09-16", at = "y")) // 同日重复
        dao.markDone(TaskDone(task_id = 1, day = "2026-09-15", at = "z"))
        dao.saveSession(Session(started_at = "20:00", minutes = 30, day = "2026-09-14"))
        dao.saveCheckin(Checkin(day = "2026-09-13", note = null, updated_at = "x"))

        val days = dao.recentActiveDays(30)
        assertEquals("同一天多条只算一天", listOf("2026-09-16", "2026-09-15", "2026-09-14"), days)
        // 这条断言是这次修正的重点：网页版 streak() 从不读 checkins
        assertTrue("checkins 不算活跃天", "2026-09-13" !in days)
    }

    @Test
    fun `meta：学期起始日能存能取 —— 第几周靠它算`() = runBlocking {
        assertNull(dao.metaGet("semester_start"))
        dao.metaPut(Meta(k = "semester_start", v = "2026-08-31"))
        assertEquals("2026-08-31", dao.metaGet("semester_start"))
        // 覆盖写
        dao.metaPut(Meta(k = "semester_start", v = "2026-09-07"))
        assertEquals("2026-09-07", dao.metaGet("semester_start"))
    }

    @Test
    fun `外键：删课程时它的时段级联删掉，不留孤儿行`() = runBlocking {
        dao.putCourses(listOf(Course(id = 1, name = "临时课")))
        dao.putSlots(listOf(Slot(id = 1, course_id = 1, weekday = 5, p_start = 1, p_end = 2)))
        assertEquals(1, dao.slots().first().size)

        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        db.openHelper.writableDatabase.execSQL("DELETE FROM courses WHERE id = 1")

        assertEquals("课程删了，它的时段必须跟着走", 0, dao.slots().first().size)
    }

    @Test
    fun `checkin：同一天重复打卡是覆盖不是新增`() = runBlocking {
        dao.saveCheckin(Checkin(day = "2026-09-16", note = "第一次", updated_at = "t1"))
        dao.saveCheckin(Checkin(day = "2026-09-16", note = "改主意了", updated_at = "t2"))
        val row = dao.checkin("2026-09-16")
        assertNotNull(row)
        assertEquals("改主意了", row!!.note)
    }
}
