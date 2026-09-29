package top.ccbase.campus.domain

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.data.seed.SeedLoader
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 拿**真实线上课表**（`live_plan_20260917.json`，带请求头从 /api/v2/plan 取的原文）过一遍提醒排程。
 *
 * 为什么单独一个文件：用户等的是"我的早自习 08:45 到底会不会响"，
 * 而不是"我构造的那个假时段会不会响"。夹具里他一天有三件事叠在一起 ——
 * 早自习 08:45、图书馆自习 19:00-21:00、晚自习 20:30-22:05 —— 这些**真实重叠**
 * 才是会出错的地方（周一~周四晚上都有重叠），必须拿真数据对一遍。
 */
class NudgeRealPlanTest {

    private val seed: Seed by lazy {
        SeedLoader.json.decodeFromString<Seed>(
            javaClass.getResourceAsStream("/live_plan_20260917.json")!!.readBytes().decodeToString()
        )
    }

    /** 2026-09-14 = 第 3 周周一（学生课表从第 3 周开始排课） */
    private fun windows(from: String, days: Int = 7) = nudgeWindows(
        slots = seed.slots,
        semesterStart = seed.meta["semester_start"] ?: "2026-08-31",
        from = LocalDateTime.parse(from),
        daysAhead = days,
        leadMinutes = 1,
        selfstudy = seed.selfstudy,
    )

    @Test
    fun `真实课表：周一第一条提醒是早自习 08_44_不是课`() {
        val w = windows("2026-09-14T00:00")
        val first = w.first()
        assertEquals("最早的一件事是早自习", "早自习", first.what)
        assertEquals(LocalDateTime.parse("2026-09-14T08:44"), first.start)
        // 自习正文也报起止（用户 2026-09-18：「早自习和晚自习的具体时间也加上」）
        assertEquals("08:45-09:15 · A 楼 I 区 301", first.body)
        assertEquals("提醒要说到自习结束（09:15）", LocalDateTime.parse("2026-09-14T09:15"), first.end)
    }

    @Test
    fun `真实课表：一周五个早自习都排上_都是 08_44`() {
        val morning = windows("2026-09-14T00:00").filter { it.what == "早自习" }
        assertEquals("周一~周五五个早自习，一个都不能漏", 5, morning.size)
        assertTrue(
            "每个都该是 08:44 提醒：${morning.map { it.start }}",
            morning.all { it.start.toLocalTime() == LocalTime.parse("08:44") },
        )
    }

    @Test
    fun `真实课表：早自习半小时_晚自习到 22_05 共 95 分钟`() {
        // 用户 2026-09-18 先说「晚自习一小时四十五分钟」，次日更正「到十点零五」。
        // 线上报文里本来就是 20:30-22:05 = **95 分钟**（也正是官方作息表第 11 节下课，
        // 跟下面那条"静音到 22:05 恢复铃声"的断言咬合）。差这 10 分钟决定
        // "该散的时候手机还在不在静音"，所以拿线上报文直接算分钟数钉住，谁改数据都会红。
        fun minutes(a: String, b: String): Int {
            val (h1, m1) = a.split(":").map { it.toInt() }
            val (h2, m2) = b.split(":").map { it.toInt() }
            return (h2 * 60 + m2) - (h1 * 60 + m1)
        }
        val morning = seed.selfstudy.filter { it.kind == "早自习" }.distinctBy { it.start to it.end }
        val evening = seed.selfstudy.filter { it.kind == "晚自习" }.distinctBy { it.start to it.end }

        assertTrue("早自习得有", morning.isNotEmpty())
        assertTrue("晚自习得有", evening.isNotEmpty())
        assertEquals("早自习半小时（${morning[0].start}-${morning[0].end}）", 30, minutes(morning[0].start, morning[0].end))
        assertEquals("晚自习 20:30-22:05 共 95 分钟（${evening[0].start}-${evening[0].end}）", 95, minutes(evening[0].start, evening[0].end))
    }

    @Test
    fun `真实课表：课和三种自习都在提醒里`() {
        val kinds = windows("2026-09-14T00:00").map { it.what }.toSet()
        assertEquals(setOf("上课", "早自习", "自习", "晚自习"), kinds)
    }

    @Test
    fun `真实课表：晚上重叠的两个自习只静音一段_不能在晚自习中间恢复铃声`() {
        val w = windows("2026-09-14T18:00", days = 1)
        val spans = silenceSpans(w)
        assertEquals("周一一整天只该有一段静音", LocalDateTime.parse("2026-09-14T18:59"), spans[0].first)
        assertEquals("一直静到 22:05（晚自习结束）", LocalDateTime.parse("2026-09-14T22:05"), spans[0].second)
        assertTrue(
            "静音绝不能 21:00 就结束（那是晚自习中间）：${spans.map { it.second }}",
            spans.none { it.second.toLocalTime() == LocalTime.parse("21:00") },
        )
        // 19:00 图书馆自习和 20:30 晚自习都算进这段静音的说明里
        val sil = planSilence(w, true).first { it.kind == NudgeKind.SILENCE }
        assertTrue("静音通知要说清是哪两件事：${sil.body}", sil.body.contains("图书馆") && sil.body.contains("A 楼 I 区 301"))
    }
    @Test
    fun `真实课表：提醒和静音都开时_同一分钟不会弹两条通知`() {
        val w = windows("2026-09-14T18:00", days = 1)
        val rem = planReminders(w, true)
        val sil = planSilence(w, true, rem.map { it.at }.toSet())
        assertTrue(
            "18:59 那条自习提醒自己会静音（正文带「已静音」），不该再单独排一个静音闹钟",
            sil.none { it.at == LocalDateTime.parse("2026-09-14T18:59") },
        )
        assertTrue(
            "22:05 必须恢复铃声，否则手机一直静音",
            sil.any { it.kind == NudgeKind.UNSILENCE && it.at == LocalDateTime.parse("2026-09-14T22:05") },
        )
    }
}
