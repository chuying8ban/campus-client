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
import java.time.LocalDateTime

/**
 * 「今晚到底有没有晚自习」—— 端到端。
 *
 * ## 为什么要单独一条
 *
 * `HolidaysTest` 只验规则本身算得对，**验不到"有没有真的接上"**。
 * 真实情况里出问题的从来不是规则，是接线：客户端原来压根没调这个规则
 * （`Today.kt` 写死"只看星期几"），规则再对也没用。
 *
 * 所以这条测试走**完整路径**：真种子入库 → `computeToday()`（今天页）
 * 和 `nudgeWindows()`（闹钟）→ 断言结果。改坏了接线这里就红。
 *
 * 种子的星期表（`seed.json`）：晚自习只在**周一~周四 + 周日**，
 * 早自习在**周一~周五**。所以「周四 2026-09-24」这个日期特别值钱：
 * 星期表说它**有**晚自习，假期规则说它**没有** —— 两套规则在这里正面撞，
 * 装配错了立刻现形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HolidaySelfStudyTest {

    private lateinit var db: CampusDb
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, CampusDb::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { SeedImporter.import(db, SeedLoader.load(ctx)) }
    }

    @After
    fun tearDown() = db.close()

    private fun at(s: String): LocalDateTime = LocalDateTime.parse(s)

    /** 走真实路径算「今天」（与生产代码 TodayLoader 取数一致）。 */
    private fun today(now: String) = runBlocking {
        computeToday(
            slots = db.dao().slots().first(),
            courses = db.dao().courses().first(),
            selfstudy = db.dao().selfStudy().first(),
            tasks = emptyList(),
            done = emptyList(),
            semesterStart = db.dao().metaGet("semester_start") ?: "2026-08-31",
            semesterName = db.dao().metaGet("semester_name"),
            termWeeks = db.dao().metaGet("term_weeks")?.toIntOrNull() ?: 20,
            now = at(now),
        )
    }

    /** 走真实路径排闹钟。 */
    private fun windows(from: String, days: Int = 4) = runBlocking {
        nudgeWindows(
            slots = db.dao().slots().first(),
            semesterStart = db.dao().metaGet("semester_start") ?: "2026-08-31",
            from = at(from),
            daysAhead = days,
            selfstudy = db.dao().selfStudy().first(),
        )
    }

    private fun eveningKinds(now: String) =
        today(now).selfstudy.filter { Holidays.isEvening(it.kind) }

    // ---------------------------------------------------------------- 今天页

    /**
     * **这就是用户报的那条**：「现在晚自习还在，应该已经没了」。
     * 2026-09-24 是中秋前一天 —— 周四，星期表上有晚自习，但必须被假期规则拿掉。
     */
    @Test
    fun `假期前一天 今天页不显示晚自习_但早自习照常`() {
        val v = today("2026-09-24T10:00")
        assertEquals("2026-09-24 是中秋前一天，今天页不该有晚自习",
            0, eveningKinds("2026-09-24T10:00").size)
        // 早自习一个字节都不能动
        assertTrue("早自习必须照常显示",
            v.selfstudy.any { it.kind.contains("早") })
        // 而且要**说出来为什么**，不能静默消失 —— 文案与服务端同一句
        assertEquals("中秋节假期的前一天（当晚没有晚自习）", v.selfStudyOff)
    }

    /** 照常的日子不许挂着"今晚没有晚自习"的说明，否则自己打自己脸。 */
    @Test
    fun `照常的日子不显示晚自习说明`() {
        assertEquals("", today("2026-09-22T10:00").selfStudyOff)   // 平常周二
        assertEquals("", today("2026-09-27T10:00").selfStudyOff)   // 中秋最后一天
    }

    /** 假期**最后一天**要有晚自习（用户原话）。9/27 是周日，正是他点名的那种。 */
    @Test
    fun `假期最后一天 今天页照样显示晚自习`() {
        assertEquals("2026-09-27 是中秋最后一天，次日开学，必须有晚自习",
            1, eveningKinds("2026-09-27T10:00").size)
    }

    /** 平常日不能误伤（周二有晚自习，规则不该碰它）。 */
    @Test
    fun `平常日照常有晚自习`() {
        assertEquals("2026-09-22 是平常周二，必须有晚自习",
            1, eveningKinds("2026-09-22T10:00").size)
    }

    /** 假期中间、以及国庆这种长假的两端，都要对。 */
    @Test
    fun `国庆首日无晚自习_末日有晚自习`() {
        assertEquals("10/1 是国庆首日，不该有晚自习", 0, eveningKinds("2026-10-01T10:00").size)
        assertEquals("10/7 是国庆末日，必须有晚自习", 1, eveningKinds("2026-10-07T10:00").size)
    }

    // ---------------------------------------------------------------- 闹钟

    /**
     * 闹钟这边出错的代价比显示更大：显示错了只是看着不对，
     * 闹钟错了是**手机在放假那天晚上 20:30 真的响**。
     */
    @Test
    fun `假期前一天 不排晚自习闹钟`() {
        val ws = windows("2026-09-24T00:00")
        // 注意：窗口往后覆盖到 9/27 —— 那天是中秋**最后一天**，本来就该有晚自习。
        // 所以不能断言"一条都没有"，要按日期看：**只有 9/24 当天**不许有。
        val on24 = ws.filter {
            it.what == "晚自习" && it.start.toLocalDate().toString() == "2026-09-24"
        }
        assertTrue("9/24（中秋前一天）当晚不该排晚自习闹钟，实际排了 $on24", on24.isEmpty())
    }

    /** 反过来：假期最后一天**必须**排上，别修过头。 */
    @Test
    fun `假期最后一天 照样排晚自习闹钟`() {
        val ws = windows("2026-09-27T00:00", days = 2)
        assertTrue("9/27 是中秋最后一天，必须排晚自习闹钟",
            ws.any { it.what == "晚自习" && it.start.toLocalDate().toString() == "2026-09-27" })
    }

    /** 整段中秋假期的闹钟逐天对账：只有 9/27（末日）该响。 */
    @Test
    fun `中秋整段假期只有最后一天排晚自习闹钟`() {
        val ws = windows("2026-09-24T00:00", days = 6)
        val days = ws.filter { it.what == "晚自习" }.map { it.start.toLocalDate().toString() }.toSet()
        val onHoliday = days.filter { it in setOf("2026-09-24", "2026-09-25", "2026-09-26") }
        assertTrue("中秋前一天与假期中间都不该有晚自习闹钟，实际有：$onHoliday", onHoliday.isEmpty())
        assertTrue("9/27（中秋最后一天）必须有晚自习闹钟", "2026-09-27" in days)
    }
}
