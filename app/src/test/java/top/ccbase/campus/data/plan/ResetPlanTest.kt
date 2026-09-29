package top.ccbase.campus.data.plan

import top.ccbase.campus.data.remote.PlanApplier

import android.content.Context
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
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Task
import top.ccbase.campus.data.local.TaskDone
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader

/**
 * 「重置学习任务」的决策逻辑。
 *
 * **库一律用内存库 + 真种子导入，绝不碰 `app().db`**：这个功能会把整库扫一遍再改，
 * 用共享的应用库就会把后面跑的用例（TaskDeleteTest 那类）连坐带红 —— 全套曾经因此红过两条，
 * 单跑却全绿。规矩见技能 compose-robolectric-testing「UI 测试的数据隔离」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetPlanTest {

    private lateinit var ctx: Context
    private lateinit var db: CampusDb

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java).allowMainThreadQueries().build()
        runBlocking { SeedImporter.import(db, SeedLoader.load(ctx)) }
        ResetStore.clear(ctx)
    }

    @After
    fun tearDown() {
        // 内存库不手动 close（理由见 ResetUiTest）：界面侧还在收 Flow 时关库，
        // 异常会漏到后面跑的另一个测试类里。
        ResetStore.clear(ctx)
    }

    private fun tpl() = TplLoader.load(ctx)

    @Test
    fun `课名认得上内置块_认不出给null`() {
        val t = tpl()
        assertEquals("数学分析（I）", ResetPlan.matchCourse(t, "数学分析（I）"))
        assertEquals("程序设计基础（B）", ResetPlan.matchCourse(t, "程序设计基础（B）程序设计"))
        assertNull("军事理论导论这类没有资源包，该走通用块", ResetPlan.matchCourse(t, "军事理论导论"))
    }

    @Test
    fun `每门课一套_通用的只来一次`() {
        val rows = ResetPlan.build(
            tpl(),
            listOf(1 to "数学分析（I）", 2 to "程序设计基础（B）程序设计", 3 to "军事理论导论", 4 to "时政"),
        )
        val titles = rows.tasks.map { it.title }
        assertTrue("数学分析（I）那套要进来", titles.any { it.startsWith("数学分析（I）：") })
        assertTrue("程序设计基础（B）那套要进来", titles.any { it.startsWith("程序设计基础（B）：") })
        assertTrue("课程块之外还要有通用块", titles.any { it.contains("问教务老师") })
        // 通用块是「一次性的手续」：三门课都认不出，也不能来三遍
        assertEquals(
            "通用块只能来一次，不然就是几十条噪音",
            1,
            titles.count { it.contains("问教务老师") },
        )
        assertTrue("id 必须落在 200 万段（服务端同步碰不到）", rows.tasks.all { it.id >= ResetPlan.ID_BASE })
        assertEquals("id 不能重复", rows.tasks.size, rows.tasks.map { it.id }.toSet().size)
        assertTrue("模板任务要带步骤", rows.steps.isNotEmpty())
    }

    @Test
    fun `一门课都不认得出时也要给东西_不能重置成空白`() {
        val rows = ResetPlan.build(tpl(), listOf(1 to "某某新课", 2 to "另一门新课"))
        assertTrue("重置完不能是白板", rows.tasks.isNotEmpty())
        assertTrue(rows.tasks.all { it.id >= ResetPlan.ID_BASE })
    }

    @Test
    fun `决定谁打墓碑谁真删`() {
        val d = ResetPlan.decide(
            listOf(
                Task(id = 5, phase = "本学期", title = "服务端来的"),
                Task(id = 1_000_001, phase = "本学期", title = "模块包"),
                Task(id = 2_000_005, phase = "内置计划", title = "上次重置留下的"),
            ),
        )
        assertEquals("服务端来的只打墓碑（真删会被同步写回）", listOf(5), d.tombstone)
        assertEquals("本地的行真删", listOf(1_000_001, 2_000_005), d.deleteLocal)
    }

    @Test
    fun `备份能原样存回来`() {
        val snap = runBlocking { ResetPlan.snapshot(db, "2026-09-19 02:00") }
        val back = ResetPlan.decode(ResetPlan.encode(snap))
        assertEquals(snap.tasks.map { it.id }, back.tasks.map { it.id })
        assertEquals(snap.steps.size, back.steps.size)
        assertEquals(snap.resources.size, back.resources.size)
        assertEquals(snap.at, back.at)
    }

    @Test
    fun `重置后清空_来源标none_打勾归零_服务端任务只打墓碑_能恢复`() {
        val dao = db.dao()
        val before = runBlocking { dao.visibleTasks().first() }
        val victim = requireNotNull(before.firstOrNull { it.id < ResetPlan.SERVER_MAX }) {
            "前提：种子里得有服务端下发的任务"
        }
        val courses = runBlocking { dao.courses().first() }.map { it.id to it.name }
        runBlocking { dao.markDone(TaskDone(task_id = victim.id, day = "2026-09-19", at = "2026-09-19 08:00")) }

        val snap = runBlocking { ResetPlan.apply(db, tpl(), courses, "2026-09-19 02:00") }

        val after = runBlocking { dao.visibleTasks().first() }
        // 2026-09-19 改口径：用户说「重置后这里就应该没有任务了」—— 重置是**清空**，
        // 不是"换成内置模板那套默认任务"。这条断言以前写的是 after.isNotEmpty()，方向正好相反。
        assertTrue("重置后必须一条任务都不剩（清空，不是换一套）", after.isEmpty())
        assertEquals("服务端来的那条被墓碑挡掉", 0, after.count { it.id == victim.id })
        assertEquals("打勾要归零", 0, runBlocking { dao.doneSince("0000-00-00") }.size)
        // 行本身还在（只打墓碑）：删了的话下一次同步会把它写回来
        assertTrue(
            "服务端那行必须还在库里（只是被墓碑过滤）",
            runBlocking { dao.tasks().first() }.any { it.id == victim.id },
        )
        // 来源必须是 none：界面照此显示空态；自动同步（打开 App / 回前台）也会跳过它 ——
        // 保留 remote 的话服务端那份计划会立刻把清单塞回来，"清空"等于白清。
        assertEquals(
            "重置后来源必须是 none",
            PlanApplier.NONE, runBlocking { dao.metaGet(PlanApplier.K_SOURCE) },
        )

        runBlocking { ResetPlan.restore(db, snap) }
        val back = runBlocking { dao.visibleTasks().first() }
        assertTrue("恢复后原来的任务要回来", back.any { it.id == victim.id })
        assertEquals("恢复后打勾也要回来", 1, runBlocking { dao.doneSince("0000-00-00") }.size)
        assertEquals(
            "恢复后来源要一起还原（否则数据回来了、页面还显示空态）",
            PlanApplier.REMOTE, runBlocking { dao.metaGet(PlanApplier.K_SOURCE) },
        )
    }
}
