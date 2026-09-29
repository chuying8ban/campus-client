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
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader
import java.time.LocalDate

/**
 * 看板逻辑的交叉验证。
 *
 * `streak` 是这里最值得测的：网页版的口径有一条**反直觉但重要**的规则 ——
 * "今天还没开始不算断"。如果哪天有人"顺手优化"成"必须今天也活跃"，
 * 用户每天早上打开 App 都会看到连续天数归零，那种挫败感足以让人卸载。
 * 这条规则必须被测试锁住。
 *
 * 统计基准取自 seed.json：任务 39（其中本学期 active=1 的 14 项）/ 步骤 68 / 资源 165 / 里程碑 9 / 清单 7
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardLogicTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun d(s: String) = LocalDate.parse(s)

    // ---------------------------------------------------------- 连续天数

    @Test
    fun `没有任何记录时连续天数是零`() {
        assertEquals(0, streak(emptySet(), d("2026-09-16")))
    }

    @Test
    fun `今天做过就从今天开始数`() {
        val days = setOf("2026-09-16", "2026-09-15", "2026-09-14")
        assertEquals(3, streak(days, d("2026-09-16")))
    }

    @Test
    fun `今天还没开始不算断 —— 从昨天往前数`() {
        // 这条是网页版的原有规则，也是"别把用户劝退"的关键
        val days = setOf("2026-09-15", "2026-09-14", "2026-09-13")
        assertEquals(3, streak(days, d("2026-09-16")))
    }

    @Test
    fun `中间断一天就停在那里`() {
        val days = setOf("2026-09-16", "2026-09-15", "2026-09-13", "2026-09-12")
        assertEquals(2, streak(days, d("2026-09-16")))
    }

    @Test
    fun `隔了一天以上就不算连续 —— 昨天也没做就是零`() {
        val days = setOf("2026-09-14", "2026-09-13")
        assertEquals(0, streak(days, d("2026-09-16")))
    }

    @Test
    fun `跨月也能连`() {
        val days = setOf("2026-10-01", "2026-09-30", "2026-09-29")
        assertEquals(3, streak(days, d("2026-10-01")))
    }

    // ---------------------------------------------------------- 统计

    @Test
    fun `统计数字与基准一致`() = runBlocking {
        SeedImporter.import(db, SeedLoader.load(ctx))
        val s = boardStats(
            tasks = db.dao().tasks().first(),
            steps = db.dao().allSteps().first(),
            resources = db.dao().resources().first(),
            activeDays = emptySet(),
            today = d("2026-09-16"),
        )
        // 2026-09-16 内容扩充：8 门公共课入库 → 任务 39→47、资源 165→211
        assertEquals(47, s.taskTotal)
        assertEquals("本学期要做的应是 14 项", 14, s.activeTasks)
        assertEquals(68, s.stepsTotal)
        assertEquals(211, s.resourceTotal)
        assertEquals("初始都没打勾", 0, s.stepsDone)
        assertEquals("没有一项全部完成", 0, s.tasksFinished)
    }

    @Test
    fun `全部打完勾的任务会被计入已完成`() = runBlocking {
        SeedImporter.import(db, SeedLoader.load(ctx))
        val steps = db.dao().allSteps().first()
        // 挑一个有步骤的任务，把它所有步骤打勾
        val target = steps.groupBy { it.task_id }.entries.first { it.value.size >= 2 }
        target.value.forEach { db.dao().markStep(it.id, "2026-09-16") }

        val s = boardStats(
            tasks = db.dao().tasks().first(),
            steps = db.dao().allSteps().first(),
            resources = db.dao().resources().first(),
            activeDays = setOf("2026-09-16"),
            today = d("2026-09-16"),
        )
        assertEquals("恰好一个任务被完成", 1, s.tasksFinished)
        assertEquals(target.value.size, s.stepsDone)
        assertEquals(1, s.streak)
    }

    @Test
    fun `里程碑与清单按 sort 排且数量与基准一致`() = runBlocking {
        SeedImporter.import(db, SeedLoader.load(ctx))
        val ms = orderedMilestones(db.dao().milestones().first())
        val cl = orderedChecklist(db.dao().checklist().first())
        assertEquals(9, ms.size)
        assertEquals(7, cl.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), ms.map { it.sort })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), cl.map { it.sort })
        assertTrue("第一条里程碑应是本学期", ms.first().when_text!!.contains("2026"))
    }
}
