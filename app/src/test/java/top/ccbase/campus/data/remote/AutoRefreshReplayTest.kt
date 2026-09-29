package top.ccbase.campus.data.remote

import android.content.Context
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
import top.ccbase.campus.data.library.Library
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Meta
import top.ccbase.campus.data.plan.Modules
import top.ccbase.campus.data.plan.TplLoader
import top.ccbase.campus.net.ApiResult
import top.ccbase.campus.net.CampusApi
import top.ccbase.campus.net.CatalogItem
import top.ccbase.campus.net.HttpReply
import top.ccbase.campus.net.Transport
import java.io.File

/**
 * 「App 自己发现课表变了」（打开 App / 回到前台自动核对）那条路上的**重放**。
 *
 * 为什么单开一个类：这条路和后端返回的课表版本号绑在一起，而它触发的是
 * **整体替换**（`Content.replace` 会把不在服务端计划里的行 prune 掉）。
 * 本机自己生成的那两段（用户挑的学习库资料、挑过的内置模块包）就活在这条替换的刀口上：
 * 重放点少一处，用户的表现就是"我挑的技能包每次自动更新就没了"——
 * 界面不报错、日志不吭声，只能靠这里的用例钉住。
 *
 * 内存库、**不在收尾里 close**：界面上的 LaunchedEffect 还在收 Room 的 Flow，
 * 关库抛的异常会漏到后面跑的另一测试类里（技能里的坑，踩过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoRefreshReplayTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        // 模板缓存是进程级的：留着"冷/热"两种状态进下一个类，会让别人的用例结果依赖执行顺序
        TplLoader.clearCache()
    }

    // ------------------------------------------------------------ 夹具

    /** 真接口返回落盘的样本（版本号 9f3c1a2b4d5e6f70），不是手写的近似物 */
    private fun body(): String =
        javaClass.getResourceAsStream("/plan_with_timetable.json")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("测试样本缺失：src/test/resources/plan_with_timetable.json")

    private fun api(b: String = body()) = CampusApi(
        base = "https://example.invalid",
        transport = Transport { _, _, _, _ -> HttpReply(200, b) },
    )

    /** 内置模块包落下的任务（1_000_000+ 段；学习库在 3_000_000+ 段，两者分开数） */
    private fun moduleTasks() =
        runBlocking { db.dao().tasks().first().filter { it.id >= Modules.ID_BASE && it.id < Library.TASK_BASE } }

    private fun libraryTasks() =
        runBlocking { db.dao().tasks().first().filter { Library.isLibraryTask(it.id) } }

    private fun item(url: String) = CatalogItem(
        url = url, title = "资料 $url", kind = "video", kindLabel = "视频",
        source = "B站", course = "", note = "视频 · 来源 B站",
    )

    /** 起一个"课表真的变了"的现场：本机记着旧版本号，服务端给的是新版本号 */
    private fun withOldTimetableVersion() =
        runBlocking { db.dao().putMeta(listOf(Meta(PlanApplier.K_TT_VER, "old-version-0000"))) }

    // ------------------------------------------------------------ 自动刷新那条路

    @Test
    fun 自动刷新那次整体替换要保住已挑的模块包() = runBlocking {
        val tpl = TplLoader.load(ctx)
        val mid = tpl.modules.first().id
        Modules.apply(db, tpl, listOf(mid))
        val before = moduleTasks().size
        assertTrue("前提：模块任务应该已经进库", before > 0)

        withOldTimetableVersion()
        val r = RemoteSync.checkAndRefresh(ctx, db, api(), "tok")
        assertTrue("自动核对必须成功：$r", r is ApiResult.Ok)
        assertTrue("前提：这次确实落地了新计划", (r as ApiResult.Ok).value.applied)

        assertEquals(
            "自动刷新把用户挑的模块包清掉了 —— 重放只挂在 sync 上，这条路没人重放",
            before, moduleTasks().size,
        )
        assertEquals("挑选记录（meta）必须原样留着", listOf(mid), Modules.picked(db))
    }

    @Test
    fun 自动刷新也要保住学习库挑的资料() = runBlocking {
        Library.add(db, listOf(Library.pickOf(item("https://a/1"), null)))
        val tid = Library.taskIdFor(null)
        assertEquals("前提：学习库任务进了库", 1, libraryTasks().size)

        withOldTimetableVersion()
        val r = RemoteSync.checkAndRefresh(ctx, db, api(), "tok")
        assertTrue("自动核对必须成功：$r", r is ApiResult.Ok)
        assertTrue("前提：这次确实落地了新计划", (r as ApiResult.Ok).value.applied)

        assertTrue("自动刷新后学习库任务还得在", libraryTasks().any { it.id == tid })
        assertEquals(
            "它的步骤也得跟着回来（空任务 = 点进详情什么都看不到）",
            1, db.dao().allSteps().first().count { it.task_id == tid },
        )
    }

    // ------------------------------------------- 唯一出口：谁调 apply 都得重放

    @Test
    fun 没有模板参数的老调用链也要重放_靠进程里的模板缓存() = runBlocking {
        // 引导页（Onboard.kt）与等待页（OnboardWait.kt）手里只有 db / token，没有模板对象。
        // 真机上模板在这些界面渲染时就读过一次、被缓存下来了，所以它们也得能重放。
        val tpl = TplLoader.load(ctx)
        val mid = tpl.modules.first().id
        Modules.apply(db, tpl, listOf(mid))
        val before = moduleTasks().size
        assertTrue(before > 0)

        val plan = (api().plan("tok") as ApiResult.Ok).value
        PlanApplier.apply(db, plan, at = "2026-09-19T10:00:00")   // 故意不传 templates

        assertEquals("没有 ctx 的调用点也必须重放", before, moduleTasks().size)
    }

    @Test
    fun 模板拿不到时只跳过模块包_绝不把整次同步带崩() = runBlocking {
        val tpl = TplLoader.load(ctx)
        val mid = tpl.modules.first().id
        Modules.apply(db, tpl, listOf(mid))
        TplLoader.clearCache()                       // 模拟资产读不出来 / 将来换了打包方式
        Library.add(db, listOf(Library.pickOf(item("https://a/1"), null)))

        val plan = (api().plan("tok") as ApiResult.Ok).value
        val r = PlanApplier.apply(db, plan, at = "2026-09-19T10:00:00")   // 老调用链：既没模板也没缓存

        assertTrue("计划本身必须照常落地（主角不许被配角带崩）", r.counts.isNotEmpty())
        assertEquals("新课表真的进来了", 2, db.dao().courses().first().size)
        assertEquals("学习库不依赖模板，必须照常重放", 1, libraryTasks().size)
        assertEquals("模块包这次确实会丢（没模板就重建不出行来）", 0, moduleTasks().size)
        assertEquals("但挑选记录必须留着 —— 下一次能拿到模板的同步会把它补回来", listOf(mid), Modules.picked(db))
    }

    /**
     * 结构性守卫：重放点**只能有一处**。
     *
     * 这条不是洁癖：1.71 就是在 `sync()` 里单独重放了一次，自动刷新那条路漏掉，
     * 用户在「打开 App 自动更新课表」时丢了挑过的技能包，而两种写法各自都"看着没错"。
     * 名单/位置来自源码本身，不来自记性。
     */
    @Test
    fun 模块包重放点只能有一处_就是整体替换的唯一出口() {
        val src = code("src/main/java/top/ccbase/campus/data/remote/Remote.kt")
        val syncPart = src.substringAfter("object RemoteSync")
        assertFalse(
            "RemoteSync 里又出现了模块包重放 —— 它只能留在 PlanApplier.apply（整体替换的唯一出口）",
            syncPart.contains("Modules.apply("),
        )
        assertTrue(
            "PlanApplier.apply 里必须有模块包重放，否则用户挑的技能包会被整体替换清掉",
            src.substringBefore("object RemoteSync").contains("Modules.apply("),
        )
    }

    /** 读源码：先剥注释（注释里提一句关键字不该让守卫假红），路径相对模块目录解析。 */
    private fun code(path: String): String {
        val f = File(path)
        assertTrue("找不到源码：${f.absolutePath}（用例的工作目录是模块目录吗？）", f.exists())
        return f.readLines()
            .map { it.substringBefore("//") }
            .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }
}
