package top.ccbase.campus.data.plan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.TaskDone


/**
 * 「挑想学的」这块的测试。
 *
 * 两个最容易错、又最难在界面上发现的地方：
 *  ① **模块 id 段必须稳定** —— 如果按 JSON 顺序派 id，以后调整模块排列，
 *     已挑过的模块会换 id：旧行删不掉、新行重复出现（用户看到两份一样的任务）。
 *  ② **必须幂等** —— 每次启动都重放一遍"已挑模块"（同步远端计划后更是如此），
 *     不能变成每启动一次就多一份任务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModulesTest {

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

    private fun tpl() = TplLoader.load(ctx)

    private fun t(seq: Int = 1) = TplTask(title = "任务$seq", steps = listOf(TplStep(seq, "步骤", 10, "watch")))

    private fun fake(vararg ids: String) = Templates(
        modules = ids.mapIndexed { i, id -> ModulePack(id, "包$id", "学完能做$id", listOf(t(1), t(2))) }
    )

    // ---------------------------------------------------------- 资产

    @Test
    fun `真实模板文件能读进来且有 11 个模块包`() {
        val t = tpl()
        assertEquals(11, t.modules.size)
        assertTrue("每个模块包都要有一句说明，否则用户没法选", t.modules.all { !it.desc.isNullOrBlank() })
        assertTrue("课程模板至少要有数学分析（I）和程序设计基础（B）",
            t.courses.keys.any { it.contains("数学分析") }
                && t.courses.keys.any { it.contains("程序设计") })
        assertEquals(6, t.generic.tasks.size)
    }

    @Test
    fun `模板里的资源 url 都是 http 开头`() {
        val t = tpl()
        val urls = t.modules.flatMap { m -> m.tasks.flatMap { it.resources.map { r -> r.url } } } +
                t.courses.values.flatMap { c -> c.tasks.flatMap { it.resources.map { r -> r.url } } }
        assertTrue(urls.isNotEmpty())
        assertTrue("只放行 http/https，别让 file:// 之类进列表", urls.all { it.startsWith("http") })
    }

    // ---------------------------------------------------------- 落库

    @Test
    fun `挑一个模块会把它的任务步骤资源都放进计划`() = runBlocking {
        val t = fake("tools")
        val r = Modules.apply(db, t, listOf("tools"))
        assertEquals(2, r.addedTasks)
        assertEquals(2, r.addedSteps)
        assertEquals(2, db.dao().tasks().first().size)
        assertEquals(2, db.dao().allSteps().first().size)
        assertEquals(listOf("tools"), Modules.picked(db))
    }

    @Test
    fun `重复应用同一模块不会产生重复任务`() = runBlocking {
        val t = fake("tools")
        Modules.apply(db, t, listOf("tools"))
        Modules.apply(db, t, listOf("tools"))
        Modules.apply(db, t, listOf("tools"))
        assertEquals("幂等：启动时重放已挑模块不能每跑一次多一份", 2, db.dao().tasks().first().size)
        assertEquals(listOf("tools"), Modules.picked(db))
    }

    @Test
    fun `挑多个模块互不撞号`() = runBlocking {
        val t = fake("tools", "dsa", "web")
        Modules.apply(db, t, listOf("tools", "dsa", "web"))
        assertEquals(6, db.dao().tasks().first().size)
        val steps = db.dao().allSteps().first()
        assertEquals("步骤 id 不能撞", steps.size, steps.map { it.id }.distinct().size)
        assertEquals(3, Modules.picked(db).size)
    }

    @Test
    fun `模块 id 段与 json 顺序无关`() {
        // 同一批模块换一下排列，派出来的 id 段必须一样
        val a = fake("tools", "dsa", "web")
        val b = Templates(modules = listOf(
            ModulePack("web", "包web"), ModulePack("tools", "包tools"), ModulePack("dsa", "包dsa"),
        ))
        for (id in listOf("tools", "dsa", "web")) {
            assertEquals("模块 $id 的 id 段必须稳定", Modules.baseFor(a, id), Modules.baseFor(b, id))
        }
    }

    @Test
    fun `不同模块的 id 段不重叠`() {
        val t = fake("tools", "dsa", "web")
        val bases = listOf("tools", "dsa", "web").map { Modules.baseFor(t, it) }
        assertEquals(3, bases.distinct().size)
        val sorted = bases.sorted()
        for (i in 1 until sorted.size) {
            assertTrue("相邻两段不能重叠", sorted[i] - sorted[i - 1] >= 100)
        }
    }

    @Test
    fun `撤掉模块会清掉它的任务`() = runBlocking {
        val t = fake("tools", "dsa")
        Modules.apply(db, t, listOf("tools", "dsa"))
        assertEquals(4, db.dao().tasks().first().size)
        Modules.remove(db, t, "tools")
        assertEquals("撤掉后应只剩另一个模块的任务", 2, db.dao().tasks().first().size)
        assertEquals(listOf("dsa"), Modules.picked(db))
    }

    @Test
    fun `撤掉模块不会碰用户记录`() = runBlocking {
        val t = fake("tools")
        Modules.apply(db, t, listOf("tools"))
        val tid = db.dao().tasks().first().first().id
        db.dao().markDone(TaskDone(task_id = tid, day = "2026-09-16", at = "10:00"))

        Modules.remove(db, t, "tools")

        assertEquals("完成记录必须还在（悬空引用是安全的，删记录不是）",
            1, db.dao().doneOn("2026-09-16").first().size)
    }

    @Test
    fun `未知模块 id 不报错`() = runBlocking {
        val t = fake("tools")
        val r = Modules.apply(db, t, listOf("not_a_module"))
        assertEquals(0, r.addedTasks)
        assertEquals(0, db.dao().tasks().first().size)
    }

    @Test
    fun `挑的模块与服务端计划里的任务能共存`() = runBlocking {
        // 服务端下发的行 id 在低位段，模块在高位段（100 万起），不会互相覆盖
        val t = fake("tools")
        Modules.apply(db, t, listOf("tools"))
        val moduleIds = db.dao().tasks().first().map { it.id }
        assertTrue("模块任务 id 应在高位段", moduleIds.all { it >= Modules.ID_BASE })
        assertNotEquals(0, moduleIds.size)
        assertNotNull(db.dao().metaGet(Modules.KEY))
    }

    @Test
    fun `资产文件带在包里`() {
        // Robolectric 读的就是打包进 APK 的 assets，读得到说明发布包里确实有
        assertTrue(ctx.assets.open("plan_templates.json").use { it.readBytes().size } > 10_000)
    }
}
