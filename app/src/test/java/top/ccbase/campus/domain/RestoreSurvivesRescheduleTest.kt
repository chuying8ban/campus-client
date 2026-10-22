package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import java.time.LocalDateTime

/**
 * 「课/自习还在进行时重排，下课恢复铃声被撤掉」—— 2026-10-22 用户报「铃声恢复有延迟，慢了一刻钟」。
 *
 * 现场（周四）：
 *  · 08:45 早自习开始，提醒那条顺手静音（正文「马上早自习 · 已静音」）；
 *  · 09:15 该有一条"下课恢复"；
 *  · 但 09:1x 看门狗判定"该响的闹钟没响"→ 全量重排。重排的窗口从 `now` 起算，
 *    09:15 的这条恢复跟着消失（`AlarmScheduler.schedule` 每次都先 cancelAll）；
 *  · 下一次看门狗巡检（09:28）走 healIfStuck 才把铃声还回来 → 用户听到"晚了十几分钟"。
 *
 * 这里钉的是"重排必须保住这一段的恢复闹钟"这条**数据保证**：
 * 不依赖课表里还有没有这节课，只看静音标记里的结束时刻。
 */
class RestoreSurvivesRescheduleTest {

    private fun selfStudy(start: String, end: String) = SelfStudy(
        id = 1, weekday = 4, kind = "早自习", start = start, end = end, place = "C6楼 I区401",
    )

    private fun slotStart(hhmm: String) = Slot(
        id = 9, weekday = 4, course_id = 1, time_text = "$hhmm-11:05", room = "A楼406",
    )

    /** 重排时真正会发生的事：窗口从 now 起算 → 排动作 → 按上限挑 → 补恢复保险 */
    private fun replanAt(now: LocalDateTime, until: LocalDateTime?, max: Int = 260): List<NudgeAction> {
        val fitted = fittedWithoutBelt(now, max)
        return ensureRestore(fitted, until, now, max)
    }

    /** 只是"按当前课表排 + 按上限挑"，不加恢复保险 —— 也就是修之前的真实行为 */
    private fun fittedWithoutBelt(now: LocalDateTime, max: Int = 260): List<NudgeAction> {
        val windows = nudgeWindows(
            slots = listOf(slotStart("09:30")),
            semesterStart = "2026-08-31",
            from = now,
            daysAhead = 7,
            leadMinutes = 1,
            selfstudy = listOf(selfStudy("08:45", "09:15")),
        )
        val sil = planSilence(windows, silence = true, remindInstants = emptySet())
        val rem = planReminders(windows, remind = true)
        return fitActions(sil, rem, max)
    }

    @Test
    fun `课还在上时重排_下课恢复必须是唯一一条落在09点15分的动作`() {
        val now = LocalDateTime.parse("2026-10-22T09:13")
        val until = LocalDateTime.parse("2026-10-22T09:15")

        // ★ 先钉住"病"：只按课表排，这节课早在过去 → 09:15 的恢复根本不在列表里。
        // 看一眼这一步就明白用户为什么听到"铃声晚了十几分钟"。
        assertFalse(
            "前提变了：从 now 起算的窗口本来就不该包含 09:15 的恢复",
            fittedWithoutBelt(now).any { it.kind == NudgeKind.UNSILENCE && it.at == until },
        )

        val acts = replanAt(now, until)

        val restore = acts.filter { it.kind == NudgeKind.UNSILENCE }
        assertTrue(
            "下课恢复（09:15）被重排弄丢了 —— 手机就会一直静着，只能等看门狗补：${acts.map { it.kind to it.at }}",
            restore.any { it.at == until },
        )
        // 这节课还能从课表里推出来的部分（09:30 那节课）不许被这条补的挤掉
        assertTrue("09:30 那节课的恢复也必须在", restore.any { it.at == LocalDateTime.parse("2026-10-22T11:05") })
        assertEquals("同一时刻不许出现两条恢复", restore.size, restore.map { it.at }.toSet().size)
    }

    @Test
    fun `没在静音时不许凭空补一条恢复`() {
        val now = LocalDateTime.parse("2026-10-22T09:13")
        val acts = replanAt(now, until = null)
        assertFalse(
            "没按静音还补恢复 → 会在课堂中途把铃声放回来",
            acts.any { it.kind == NudgeKind.UNSILENCE && it.at == LocalDateTime.parse("2026-10-22T09:15") },
        )
    }

    @Test
    fun `结束时刻已经过去_不再补`() {
        val now = LocalDateTime.parse("2026-10-22T09:20")
        val acts = replanAt(now, until = LocalDateTime.parse("2026-10-22T09:15"))
        assertTrue(
            "已经过点的恢复不该再排（排了也排不上，且掩盖了真的漏响）",
            acts.none { it.kind == NudgeKind.UNSILENCE && it.at == LocalDateTime.parse("2026-10-22T09:15") },
        )
    }

    @Test
    fun `课表里本来就有这条恢复_不许补第二次`() {
        val now = LocalDateTime.parse("2026-10-22T08:30")     // 还没到下课，正常排程
        val until = LocalDateTime.parse("2026-10-22T09:15")
        val acts = replanAt(now, until)
        assertEquals(
            "09:15 的恢复只有一条",
            1,
            acts.count { it.kind == NudgeKind.UNSILENCE && it.at == until },
        )
    }

    @Test
    fun `补恢复撞上闹钟上限_砍的是提醒而不是这段恢复`() {
        val now = LocalDateTime.parse("2026-10-22T09:13")
        val until = LocalDateTime.parse("2026-10-22T09:15")
        val acts = replanAt(now, until, max = 6)
        assertTrue("上限再紧，正在静音那段的恢复也不许丢", acts.any { it.kind == NudgeKind.UNSILENCE && it.at == until })
        assertTrue("总数不许超上限", acts.size <= 6)
    }
}
