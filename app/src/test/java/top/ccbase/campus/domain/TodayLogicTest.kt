package top.ccbase.campus.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「今日」规则的真跑测试 —— 在 JVM 上跑，不需要真机或模拟器。
 *
 * 为什么这个测试非写不可：
 * 「今天显示哪几节课 / 哪些任务该做 / 现在是第几周」是整个 App 的核心规则。
 * 本机装了不模拟器、看不到界面，如果只靠"我觉得逻辑对"，那用户装上之后
 * 发现少一节课、或者周次算错一天，我根本没有手段自查。
 *
 * **关键：期望值不是我想出来的，是网页版（Python 版同规则实现）算出来的。**
 * 跑 `python3 seed_export.py` 会打印基准值，下面这些常量就是从那里抄的。
 * 这样万一两边实现有偏差，测试会直接红 —— 而不是等用户发现"App 和网页版不一样"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayLogicTest {

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

    private fun at(s: String): LocalDateTime = LocalDateTime.parse(s)   // "2026-09-16T10:00"

    private fun today(now: String) = runBlocking {
        val seed = SeedLoader.load(ctx)
        SeedImporter.import(db, seed)
        computeToday(
            slots = db.dao().slots().first(),
            courses = db.dao().courses().first(),
            selfstudy = db.dao().selfStudy().first(),
            tasks = db.dao().tasks().first(),
            done = db.dao().doneSince("2026-01-01"),
            semesterStart = db.dao().metaGet("semester_start") ?: "2026-08-31",
            semesterName = db.dao().metaGet("semester_name"),
            termWeeks = db.dao().metaGet("term_weeks")?.toIntOrNull() ?: 20,
            now = at(now),
        )
    }

    // ---------------------------------------------------------------- 种子导入

    @Test
    fun `种子导入条目数与导出对账一致`() = runBlocking {
        val seed = SeedLoader.load(ctx)
        val r = SeedImporter.import(db, seed)
        assertTrue("首次应真导入", r.imported)
        assertEquals(11, seed.courses.size)
        assertEquals(19, seed.slots.size)
        assertEquals(10, seed.selfstudy.size)
        // 2026-09-16 内容扩充：8 门公共课入库（任务 47、资源 211）
        assertEquals(47, seed.tasks.size)
        assertEquals(68, seed.study_steps.size)
        assertEquals(211, seed.resources.size)
        assertEquals(7, seed.checklist.size)
        assertEquals(9, seed.milestones.size)
        assertEquals(11, db.dao().courseCount())
    }

    @Test
    fun `重复导入是幂等的`() = runBlocking {
        val seed = SeedLoader.load(ctx)
        SeedImporter.import(db, seed)
        val again = SeedImporter.import(db, seed)
        assertTrue("版本没变就不该再写", !again.imported)
        assertEquals(11, db.dao().courseCount())
    }

    @Test
    fun `重灌种子绝不碰用户完成记录`() = runBlocking {
        val seed = SeedLoader.load(ctx)
        SeedImporter.import(db, seed)
        // 模拟用户今天的打卡
        db.dao().markDone(TaskDone(task_id = 2, day = "2026-09-16", at = "12:00"))
        // 强制重灌（比如 App 版本升级带了新种子）
        SeedImporter.import(db, seed, force = true)
        assertEquals("用户记录必须还在，否则连续天数会一夜归零",
            1, db.dao().isDone(2, "2026-09-16"))
    }

    // ---------------------------------------------------------------- 周次换算

    @Test
    fun `周次换算与网页版基准一致`() {
        val s = "2026-08-31"
        assertEquals(1, weekNo(s, LocalDate.parse("2026-08-31")))   // 学期第一周周一
        assertEquals(1, weekNo(s, LocalDate.parse("2026-09-06")))   // 第一周周日
        assertEquals(2, weekNo(s, LocalDate.parse("2026-09-07")))   // 第二周周一
        assertEquals(3, weekNo(s, LocalDate.parse("2026-09-15")))
        assertEquals(3, weekNo(s, LocalDate.parse("2026-09-16")))
        assertEquals(3, weekNo(s, LocalDate.parse("2026-09-20")))   // 第三周周日
        assertEquals(4, weekNo(s, LocalDate.parse("2026-09-21")))   // 第四周周一
    }

    // ---------------------------------------------------------------- 今日视图

    @Test
    fun `2026-09-16 十点 与网页版基准逐项一致`() {
        val v = today("2026-09-16T10:00")
        assertEquals("2026-09-16", v.date)
        assertEquals(3, v.weekday)
        assertEquals("周三", v.weekdayCn)
        assertEquals(3, v.weekNo)
        assertEquals(20, v.termWeeks)

        // 三节课：09:30-11:05 正在上、后两节还没到
        assertEquals(3, v.classes.size)
        assertEquals(listOf("now", "todo", "todo"), v.classes.map { it.status })
        // 现在显示的是「节次 · 时钟」，比只给时钟串更清楚（也证明节次→时间那张表生效）
        assertEquals(
            listOf("1-2 节 · 09:30-11:05", "3-5 节 · 11:25-13:50", "6-7 节 · 16:00-17:35"),
            v.classes.map { it.timeText },
        )

        // 自习：早自习已过、晚自习未到
        assertEquals(2, v.selfstudy.size)
        assertEquals(listOf("done", "todo"), v.selfstudy.map { it.status })
        assertEquals(listOf("早自习", "晚自习"), v.selfstudy.map { it.kind })

        // 今日任务 4 个：1 个"今天有这门课" + 3 个"每日"
        assertEquals(4, v.tasks.size)
        assertEquals(1, v.tasks.count { it.why == "今天有这门课" })
        assertEquals(3, v.tasks.count { it.why == "每日" })

        // 常驻待办 5 个
        assertEquals(5, v.standing.size)
        assertTrue(v.standing.any { it.title.contains("找高年级同学要") })
    }

    @Test
    fun `晚自习时段状态变成 now`() {
        val v = today("2026-09-16T21:00")
        assertEquals(listOf("done", "now"), v.selfstudy.map { it.status })
        assertEquals(listOf("done", "done", "done"), v.classes.map { it.status })
    }

    @Test
    fun `周日没有正课但仍有晚自习`() {
        val v = today("2026-09-20T21:00")   // 2026-09-20 是周日
        assertEquals(7, v.weekday)
        assertEquals(0, v.classes.size)
        assertEquals(1, v.selfstudy.size)
        assertEquals("晚自习", v.selfstudy[0].kind)
    }

    @Test
    fun `完成的每日任务会标成已做`() = runBlocking {
        val v0 = today("2026-09-16T10:00")
        val t = v0.tasks.first { it.why == "每日" }
        db.dao().markDone(TaskDone(task_id = t.id, day = "2026-09-16", at = "10:30"))
        val v1 = today("2026-09-16T10:00")
        assertTrue("刚打卡的任务应显示已做", v1.tasks.first { it.id == t.id }.doneToday)
    }

    // ---------------------------------------------------------------- 去重

    /**
     * 这条来自真实截图：今日列表里「演示条目324：做配套练习 3~5 题」连着出现两行。
     *
     * 成因：内置种子 assets/seed.json 和技能包 assets/plan_templates.json 各含一条同名任务，
     * 两条都 active、cadence 都是 daily → cadence=="daily" 的分支把两条都收进今日列表。
     * 标题相同、id 不同，所以按 id 查重查不出来。
     *
     * **注意不能走 today() 助手**：那个助手每次都会重新导入种子（Content.replace 整体替换），
     * 会把这里插的重复行清掉 —— 测试就会因为"重复行没了"而假绿。
     */
    @Test
    fun `同名任务被两个来源各建一份_今日只显示一条`() = runBlocking {
        val seed = SeedLoader.load(ctx)
        SeedImporter.import(db, seed)
        val t = db.dao().tasks().first().first { it.cadence == "daily" }
        db.dao().putTasks(listOf(t.copy(id = 1_000_001)))
        assertEquals("两条同名任务应真的都在库里", 2, db.dao().tasks().first().count { it.title == t.title })

        val v = computeToday(
            slots = db.dao().slots().first(),
            courses = db.dao().courses().first(),
            selfstudy = db.dao().selfStudy().first(),
            tasks = db.dao().tasks().first(),
            done = db.dao().doneSince("2026-01-01"),
            semesterStart = db.dao().metaGet("semester_start") ?: "2026-08-31",
            semesterName = db.dao().metaGet("semester_name"),
            termWeeks = db.dao().metaGet("term_weeks")?.toIntOrNull() ?: 20,
            now = at("2026-09-16T10:00"),
        )
        assertEquals("今日不该出现两行同名任务", 1, v.tasks.count { it.title == t.title })
    }
}
