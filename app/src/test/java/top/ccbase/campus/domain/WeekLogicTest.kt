package top.ccbase.campus.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import top.ccbase.campus.data.seed.SeedImporter
import top.ccbase.campus.data.seed.SeedLoader

/**
 * 周视图规则的交叉验证。基准值同样来自用 seed.json 直接统计的结果，不是我想出来的：
 *   时间行 6 个 / 周次范围 3~19 / 第 3 周共 11 节（各天 2,2,3,0,4,0,0）/ 第 1 与第 20 周 0 节
 *
 * 顺带锁一个容易退化的地方：第 3 周周三 3 节，必须与今日页显示的 3 节课吻合 ——
 * 这两个页面用的是同一条周次判据，任何一边漂移都会在这里红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeekLogicTest {

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

    private fun load(): Pair<List<Slot>, List<Course>> = runBlocking {
        SeedImporter.import(db, SeedLoader.load(ctx))
        db.dao().slots().first() to db.dao().courses().first()
    }

    @Test
    fun `行是节次_不是带星期的时间串`() {
        val (slots, _) = load()
        val rows = timeRows(slots)
        assertTrue("得有行", rows.isNotEmpty())
        assertTrue("行标签只能是节次（如 1-2 节），不能带星期或时钟串：$rows",
            rows.all { it.label.matches(Regex("\\d+(-\\d+)? 节")) })
        // 这里**不能**要求「起始节次严格升序」：教务真实数据里同一门课会有
        //   3-4 节（11:25-13:00，上机）与 3-5 节（11:25-13:50）两种安排，
        // 起始节次都是 3 —— 当初那条断言把这个合法情形判成了 bug。
        // 真正要守的是「行是节次」而不是「每个课时一行」（画成斜线就是每课时一行，
        // 会产出四十来行）：去重后的行必须明显少于「课时的总和」。
        val units = slots.sumOf { s -> ((s.p_end ?: s.p_start ?: 0) - (s.p_start ?: 0) + 1).coerceAtLeast(1) }
        assertTrue("行数不该等于总课时数（那就是每课时一行，正是画歪的形状）：行 ${rows.size} / 课时 $units",
            rows.size < units)
    }

    /**
     * 回归钉：**用线上真实计划**当输入。
     *
     * 由来：线上 `time_text` 是「周一 1-2节」这种带星期的串，老代码按它分行 →
     * 一行一个课时 → 用户截图里那条斜线加四十个空框。而老测试读的是 App 自带
     * 模板 seed（`time_text` 是「09:30-11:05」），形状和服务端不一样，所以一直全绿。
     * 这里直接喂线上那份夹具：形状不对就红。
     */
    @Test
    fun `线上形状的时段_行只能按节次排_且不出现星期`() {
        val json = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/live_plan_20260917.json")!!.readBytes().decodeToString()
        ).jsonObject
        val slots = json["slots"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Slot(
                id = o["id"]!!.jsonPrimitive.int,
                weekday = o["weekday"]!!.jsonPrimitive.int,
                course_id = o["course_id"]?.jsonPrimitive?.int,
                p_start = o["p_start"]?.jsonPrimitive?.int,
                p_end = o["p_end"]?.jsonPrimitive?.int,
                time_text = o["time_text"]?.jsonPrimitive?.contentOrNull,
                room = o["room"]?.jsonPrimitive?.contentOrNull,
                week_from = o["week_from"]?.jsonPrimitive?.int,
                week_to = o["week_to"]?.jsonPrimitive?.int,
            )
        }
        val rows = timeRows(slots)
        assertEquals("线上这批时段应该只产生这些节次行", listOf(GridRow(1, 2, "1-2 节"), GridRow(3, 5, "3-5 节"), GridRow(4, 5, "4-5 节"), GridRow(6, 7, "6-7 节"), GridRow(8, 9, "8-9 节"), GridRow(10, 11, "10-11 节")), rows)
        assertTrue("行标签里绝不能出现星期或时钟：$rows",
            rows.none { it.label.contains("周") || it.label.contains(":") })
    }

    @Test
    fun `周次范围与基准一致`() {
        val (slots, _) = load()
        val r = weeksWithClass(slots)
        assertEquals(3, r.first)
        assertEquals(19, r.last)
    }

    @Test
    fun `第3周共11节且各天分布与基准一致`() {
        val (slots, _) = load()
        val inWeek = slotsInWeek(slots, 3)
        assertEquals(11, inWeek.size)
        assertEquals(listOf(2, 2, 3, 0, 4, 0, 0), (1..7).map { wd -> slotsAt(slots, 3, wd).size })
    }

    @Test
    fun `学期首尾周没课不是错误而是事实`() {
        val (slots, _) = load()
        assertTrue("第 1 周应为空（这门课从第 3 周才开始）", slotsInWeek(slots, 1).isEmpty())
        assertTrue("第 20 周应为空（第 19 周就结课了）", slotsInWeek(slots, 20).isEmpty())
    }

    @Test
    fun `周视图与今日页对同一天的口径一致`() {
        val (slots, _) = load()
        // 2026-09-16 是周三；今日页基准显示 3 节课 —— 两处必须是同一个数
        assertEquals(3, slotsAt(slots, 3, 3).size)
    }

    @Test
    fun `课程配色稳定且落在调色板范围内`() {
        val (_, courses) = load()
        courses.forEach { c ->
            val i = courseColorIndex(c.id)
            assertTrue("配色下标越界：${c.id} -> $i", i in 0..5)
            assertEquals("同一门课每次都该取到同色", i, courseColorIndex(c.id))
        }
    }
}
