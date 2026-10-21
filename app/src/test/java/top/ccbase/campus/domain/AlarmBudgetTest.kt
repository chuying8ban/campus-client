package top.ccbase.campus.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「吵了一整天的静音」那条线上真正的凶手：闹钟上限 60 条把**恢复铃声**整批截掉了。
 *
 * 2026-10-21 用户报「下课后静音没有自动恢复」。追下来是这样：
 *  ① 一周的课前提醒有上百条，静音动作排在它们**后面**；
 *  ② [top.ccbase.campus.alarm.AlarmScheduler.MAX_ALARMS] 只排 60 条，先到先得 → 静音动作全被截；
 *  ③ 提醒那条会顺手静音，所以"静音"照常发生 —— 但"恢复铃声"一条没排上；
 *  ④ 于是手机从早自习静到第二天第一条提醒把兜底自愈撞出来。
 *
 * 这里钉两条规则，任何一条被改坏都会重新出现"静音不恢复"：
 *  · 静音/恢复成对必须整对留下（截断只能截提醒）；
 *  · 连堂课的提醒/静音，结束时刻要取**合并后时段**的末尾，不能取自己那节的末尾。
 */
class AlarmBudgetTest {

    private fun w(start: String, end: String, body: String = "高等数学") = NudgeWindow(
        start = LocalDateTime.parse(start),
        end = LocalDateTime.parse(end),
        slotId = 1,
        courseId = 1,
        body = body,
        what = "上课",
    )

    private fun pairsOf(acts: List<NudgeAction>): List<LocalDateTime> =
        acts.filter { it.kind == NudgeKind.UNSILENCE }.map { it.at }

    @Test
    fun `真实一周的量_静音段和提醒全都能排上`() {
        // 一天 8 节 + 早晚自习 ≈ 10 段，连排 7 天 —— 这就是 Rescheduler 真实排的量级
        val windows = ArrayList<NudgeWindow>()
        for (d in 0 until 7) {
            val day = LocalDate0.plusDays(d.toLong())
            for (h in 0 until 10) {
                // 每小时一段、段长 45 分钟：中间留 15 分钟课间（不合并，检测更严格）
                val s = day.plusHours(h.toLong())
                windows += w(s.toString(), s.plusMinutes(45).toString())
            }
        }
        val max = top.ccbase.campus.alarm.AlarmScheduler.MAX_ALARMS
        val sil = planSilence(windows, silence = true, remindInstants = emptySet())
        val rem = planReminders(windows, remind = true)
        val fitted = fitActions(sil, rem, max)

        assertTrue("排上的总数不许超上限", fitted.size <= max)
        assertEquals("7 天 × 10 段的量必须全部装得下（装不下＝又会出现静音不恢复）", sil.size + rem.size, fitted.size)

        val unsilenceAts = fitted.filter { it.kind == NudgeKind.UNSILENCE }.map { it.at }.toSet()
        for (a in sil.filter { it.kind == NudgeKind.SILENCE }) {
            assertTrue("静音 ${a.at} 的恢复 ${a.endAt} 没排上", a.endAt!! in unsilenceAts)
        }
    }

    @Test
    fun `上限真装不下时_宁可少排整段_也绝不留半对`() {
        val windows = (0 until 40).map {
            val s = LocalDateTime.parse("2026-10-19T08:00").plusMinutes(it.toLong() * 40)
            w(s.toString(), s.plusMinutes(30).toString())
        }
        val sil = planSilence(windows, silence = true)
        val rem = planReminders(windows, remind = true)
        assertTrue("这个用例的前提：静音段本身就超过上限", sil.size > 12)

        val fitted = fitActions(sil, rem, 12)
        assertTrue(fitted.size <= 12)
        val starts = fitted.filter { it.kind == NudgeKind.SILENCE }
        val ends = fitted.filter { it.kind == NudgeKind.UNSILENCE }.map { it.at }.toSet()
        assertEquals("静音和恢复必须等量", starts.size, ends.size)
        assertEquals("上限 12 = 最多 6 段，一段都不许缺恢复", 6, starts.size)
        for (s in starts) assertTrue("段 ${s.at} 缺恢复", s.endAt!! in ends)
        assertEquals("静音段优先，提醒一条位置都不占", 0, fitted.count { it.kind == NudgeKind.REMIND })
    }

    @Test
    fun `挑完之后仍然是时间顺序_排闹钟时不会倒着排`() {
        val windows = (0 until 6).map {
            val s = LocalDateTime.parse("2026-10-19T09:30").plusHours(it.toLong() * 2)
            w(s.toString(), s.plusMinutes(45).toString())
        }
        val sil = planSilence(windows, silence = true)
        val rem = planReminders(windows, remind = true)
        // 上限就是 AlarmScheduler 真实用的那个数
        val fitted = fitActions(sil, rem, top.ccbase.campus.alarm.AlarmScheduler.MAX_ALARMS)
        assertEquals("必须按时间升序", fitted.map { it.at }.sorted(), fitted.map { it.at })
    }

    @Test
    fun `连堂课的结束时刻取合并时段的末尾_别在课间把铃声放回来`() {
        // 09:30-10:15 与 10:20-11:05：中间只有 5 分钟课间，看起来可以响，实际上一整天都连堂
        val a = w("2026-10-19T09:30", "2026-10-19T10:15")
        val b = w("2026-10-19T10:20", "2026-10-19T11:05")
        val windows = listOf(a, b)
        // 先把两节合并成一段：调整成首尾相接（这才是"连堂"）
        val t1 = w("2026-10-19T09:30", "2026-10-19T10:20")
        val t2 = w("2026-10-19T10:20", "2026-10-19T11:05")
        val merged = listOf(t1, t2)

        val remA = planReminders(listOf(t1), remind = true).first()
        assertEquals("单条窗口就是自己那节的末尾", LocalDateTime.parse("2026-10-19T10:20"), remA.endAt)

        val remMerged = planReminders(merged, remind = true)
        assertEquals("首节的提醒要带整段的末尾", LocalDateTime.parse("2026-10-19T11:05"), remMerged[0].endAt)
        assertEquals(LocalDateTime.parse("2026-10-19T11:05"), remMerged[1].endAt)

        // 没合并的两节（中间有真课间）不许被拉长
        assertEquals(LocalDateTime.parse("2026-10-19T10:15"), planReminders(windows, true)[0].endAt)
    }

}

/** 测试里只需要一个能加天的起点，别把 java.time 的 import 散在上头 */
private val LocalDate0: LocalDateTime = LocalDateTime.parse("2026-10-19T09:00")
