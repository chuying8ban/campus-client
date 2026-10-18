package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「课和自习结束后自动取消静音」（2026-10-18 用户要求）。
 *
 * 恢复这件事有三条命：① 静音动作自己带着"这一段几点结束"，下课闹钟照它排；
 * ② 恢复失败/闹钟丢了时，App 起来后 `Silence.healIfStuck` 能补一次；
 * ③ 提醒那条会顺手静音，所以它也得带结束时间 —— 否则补恢复会在课中途放铃声。
 *
 * 这里钉的是 ① 和 ③ 的**数据**（纯 JVM）；② 依赖系统铃声状态，只能在真机上验。
 */
class SilenceRestoreTest {

    private fun w(start: String, end: String) = NudgeWindow(
        start = LocalDateTime.parse(start),
        end = LocalDateTime.parse(end),
        slotId = 1,
        courseId = 1,
        body = "高等数学",
        what = "上课",
    )

    @Test
    fun `静音动作要带结束时刻_否则无从恢复`() {
        val w0 = w("2026-10-19T09:40", "2026-10-19T11:20")
        val acts = planSilence(listOf(w0), silence = true)
        val sil = acts.first { it.kind == NudgeKind.SILENCE }
        assertEquals("静音开始 = 上课时刻", LocalDateTime.parse("2026-10-19T09:40"), sil.at)
        assertEquals("静音结束 = 下课时刻", LocalDateTime.parse("2026-10-19T11:20"), sil.endAt)
        val un = acts.first { it.kind == NudgeKind.UNSILENCE }
        assertEquals("恢复闹钟就排在下课那一刻", LocalDateTime.parse("2026-10-19T11:20"), un.at)
    }

    @Test
    fun `提醒顺手静音也要带结束时刻`() {
        val w0 = w("2026-10-19T09:40", "2026-10-19T11:20")
        val rem = planReminders(listOf(w0), remind = true)
        assertEquals(1, rem.size)
        assertEquals(LocalDateTime.parse("2026-10-19T11:20"), rem[0].endAt)
    }

    @Test
    fun `晚自习和图书馆自习叠在一起_只恢复一次`() {
        val a = w("2026-10-19T19:00", "2026-10-19T21:00")
        val b = w("2026-10-19T20:30", "2026-10-19T22:05")
        val acts = planSilence(listOf(a, b), silence = true)
        assertEquals("合并后只有一段：一次静音一次恢复", 2, acts.size)
        val un = acts.first { it.kind == NudgeKind.UNSILENCE }
        assertEquals("要等最晚的那件事结束", LocalDateTime.parse("2026-10-19T22:05"), un.at)
        val sil = acts.first { it.kind == NudgeKind.SILENCE }
        assertEquals("静音段的结束时间也取最晚的", LocalDateTime.parse("2026-10-19T22:05"), sil.endAt)
    }

    @Test
    fun `没开静音就不排任何恢复`() {
        val acts = planSilence(listOf(w("2026-10-19T09:40", "2026-10-19T11:20")), silence = false)
        assertTrue("开关关着不该碰系统铃声", acts.isEmpty())
    }
}
