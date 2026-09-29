package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.local.SelfStudy
import top.ccbase.campus.data.local.Slot
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒排程的**纯逻辑**测试（不需要 Robolectric，毫秒级）。
 *
 * 为什么单独一个文件：这几个函数决定"到底弹不弹、弹几条、说什么"，
 * 和数据库/系统无关，出问题时它是最小可复现面。
 *
 * 2026-09-19 的改动背景：用户要求"提醒要从 App 里来，不要经第三方机器人，
 * 这样别人也能用"。于是提醒（通知，人人可用）和自动静音（要系统权限）
 * 拆成两个开关 —— 四种组合都必须排对，尤其"两个都开时不能弹两条通知"。
 */
class NudgePlanTest {

    private fun slot(
        id: Int = 1,
        weekday: Int = 1,
        pStart: Int? = 1,
        pEnd: Int? = 2,
        timeText: String? = null,
        room: String? = "A楼406",
        weeks: String? = null,
    ) = Slot(
        id = id, weekday = weekday, course_id = 7, p_start = pStart, p_end = pEnd,
        time_text = timeText, room = room, week_from = 3, week_to = 19, weeks = weeks,
    )

    private fun window(body: String = "程序设计基础（B） · 第1-2节 · 09:30 · A楼406") = NudgeWindow(
        start = LocalDateTime.parse("2026-09-21T09:29"),
        end = LocalDateTime.parse("2026-09-21T11:05"),
        slotId = 1, courseId = 7, body = body,
    )

    // ------------------------------------------------------------ 四种开关组合

    @Test
    fun `只开提醒 —— 只排一个闹钟_不碰静音`() {
        val a = planActions(window(), remind = true, silence = false)
        assertEquals("一个窗口只该排一个闹钟", 1, a.size)
        assertEquals(NudgeKind.REMIND, a[0].kind)
        assertEquals(LocalDateTime.parse("2026-09-21T09:29"), a[0].at)
        assertTrue("提醒正文要带上这节课的信息", a[0].body.isNotEmpty())
    }

    @Test
    fun `只开静音 —— 课前静音_下课必须恢复`() {
        val a = planActions(window(), remind = false, silence = true)
        assertEquals("静音窗口就该是两条：静音 + 恢复", 2, a.size)
        assertEquals(NudgeKind.SILENCE, a[0].kind)
        assertEquals(LocalDateTime.parse("2026-09-21T09:29"), a[0].at)
        assertEquals(NudgeKind.UNSILENCE, a[1].kind)
        assertEquals("恢复必须在**下课**时刻，不是课前", LocalDateTime.parse("2026-09-21T11:05"), a[1].at)
    }

    @Test
    fun `两个都开 —— 课前只出一条通知_外加下课恢复`() {
        val a = planActions(window(), remind = true, silence = true)
        assertEquals("课前提醒 + 下课恢复 = 两条", 2, a.size)
        assertEquals(NudgeKind.REMIND, a[0].kind)
        assertEquals(NudgeKind.UNSILENCE, a[1].kind)
        assertTrue(
            "课前那一刻不能再排一条独立的静音通知 —— 锁屏上两条通知就是噪音（静音结果由提醒那条自己带上）",
            a.none { it.kind == NudgeKind.SILENCE },
        )
    }

    @Test
    fun `两个都关 —— 什么也不排`() {
        assertEquals(0, planActions(window(), remind = false, silence = false).size)
    }

    // ------------------------------------------------------------ 通知标题说实话

    @Test
    fun `标题按真实剩余时间说话_晚响了不能还说一分钟`() {
        assertEquals("还有一分钟", "1 分钟后上课", remindTitle(60))
        assertEquals("刚触发（约 1 分钟）说一分钟", "1 分钟后上课", remindTitle(45))
        assertEquals("只剩几秒了", "马上上课", remindTitle(20))
        assertEquals("已经过点了（晚响）", "马上上课", remindTitle(-90))
        assertEquals("提前 5 分钟", "5 分钟后上课", remindTitle(300))
        assertEquals("提前 2 分钟", "2 分钟后上课", remindTitle(100))
    }

    // ------------------------------------------------------------ 正文

    @Test
    fun `正文_线上形状只有节次也要说清哪门课第几节在哪`() {
        // 线上 time_text 是「周一 1-2节」，靠作息表补钟点
        val s = slot(timeText = "周一 1-2节")
        assertEquals("程序设计基础（B） · 第1-2节 · 09:30 · A楼406", remindBody("程序设计基础（B）", s, slotSpan(s)))
    }

    @Test
    fun `正文_缺教室就少一段_不是留一个空顿号`() {
        val s = slot(room = null, timeText = "09:30-11:05")
        assertEquals("数学分析（I） · 第1-2节 · 09:30", remindBody("数学分析（I）", s, slotSpan(s)))
    }

    @Test
    fun `正文_什么信息都没有时也要有话说`() {
        val s = Slot(id = 9, weekday = 3)
        assertEquals("该上课了", remindBody(null, s, null))
    }

    // ------------------------------------------------------------ 窗口把它们串起来

    @Test
    fun `窗口里就带上正文 —— 到点不再查数据库`() {
        val live = listOf(slot(id = 99, timeText = "周一 1-2节"))
        val w = nudgeWindows(
            live, "2026-08-31", LocalDateTime.parse("2026-09-17T08:00"),
            daysAhead = 7, leadMinutes = 1, nameOf = { "程序设计基础（B）" },
        )
        assertEquals(1, w.size)
        assertEquals("程序设计基础（B） · 第1-2节 · 09:30 · A楼406", w[0].body)
        assertEquals(LocalDateTime.parse("2026-09-21T09:29"), w[0].start)
        assertEquals("正文要能被拆成四段给人看", 4, w[0].body.split(" · ").size)
    }

    // ------------------------------------------------------------ 静音结果的一句话

    @Test
    fun `静音成功与失败的说法不同_失败要带上缺哪一项`() {
        assertEquals("已静音", silenceNote(true))
        assertTrue("失败必须说清缺什么，否则用户在设置里翻不到：${silenceNote(false)}",
            silenceNote(false).contains("静音未生效") && silenceNote(false).contains("修改系统设置"))
    }

    // ------------------------------------------- 到点那一刻（含 Robolectric 复现不出的失败分支）

    @Test
    fun `到点_通知权限没给_内容照旧但对账要记成没送达`() {
        val r = fireRemind(60, "上课", "程序设计基础（B） · 第1-2节", wantSilence = false, silFailReason = null, notifOk = false)
        assertEquals("1 分钟后上课", r.title)
        assertEquals("程序设计基础（B） · 第1-2节", r.body)
        assertFalse("通知根本没弹出来，不许记成功：$r", r.ok)
        assertEquals("no_notification_permission", r.reason)
    }

    @Test
    fun `到点_顺手静音失败_写在同一条提醒里并记原因`() {
        val r = fireRemind(60, "上课", "程序设计基础（B）", wantSilence = true, silFailReason = "no_write_settings", notifOk = true)
        assertTrue("静音没成必须当场说：${r.body}", r.body.contains("静音未生效"))
        assertTrue("课名不能被挤掉：${r.body}", r.body.startsWith("程序设计基础（B）"))
        assertFalse(r.ok)
        assertEquals("no_write_settings", r.reason)
    }

    @Test
    fun `到点_静音成功_正文带一句已静音`() {
        val r = fireRemind(60, "上课", "程序设计基础（B）", wantSilence = true, silFailReason = null, notifOk = true)
        assertEquals("程序设计基础（B） · 已静音", r.body)
        assertTrue(r.ok)
        assertEquals("", r.reason)
    }

    @Test
    fun `到点_两个都不顺_原因要都记上`() {
        val r = fireRemind(60, "上课", "程序设计基础（B）", wantSilence = true, silFailReason = "no_write_settings", notifOk = false)
        assertEquals("no_notification_permission,no_write_settings", r.reason)
        assertFalse(r.ok)
    }
    // ------------------------------------------------------- 早晚自习（同样是"到点提醒"）

    private fun selfStudy(id: Int, weekday: Int, kind: String, start: String, end: String, place: String?) =
        SelfStudy(id = id, weekday = weekday, kind = kind, start = start, end = end, place = place)

    /**
     * 用户截图里最早的一条提醒就是"1 分钟后早自习 08:45 A楼 I区301" ——
     * 只排正课、漏掉自习，等于漏掉他一天的头一件事。
     */
    @Test
    fun `早自习排在当天的课之前_正文给起止时刻和地点`() {
        val ss = listOf(selfStudy(1, 1, "早自习", "08:45", "09:15", "A 楼 I 区 301"))
        // 2026-09-14 = 第 3 周周一（正课从第 3 周开始）
        val w = nudgeWindows(
            listOf(slot(weekday = 1)), "2026-08-31",
            LocalDateTime.parse("2026-09-14T00:00"), daysAhead = 1, leadMinutes = 1, selfstudy = ss,
        )
        assertEquals("周一：早自习 + 第1-2节 = 两条窗口", 2, w.size)
        assertEquals("早自习", w[0].what)
        assertEquals(LocalDateTime.parse("2026-09-14T08:44"), w[0].start)
        assertEquals("静音要持续到自习结束", LocalDateTime.parse("2026-09-14T09:15"), w[0].end)
        // 用户 2026-09-18 追加要求：自习也要把**具体时间**（起止）说全，
        // 只报「08:45」看不出要上到几点
        assertEquals("08:45-09:15 · A 楼 I 区 301", w[0].body)
        assertEquals("课排在自习之后", "上课", w[1].what)
    }

    @Test
    fun `自习不受周次限制 —— 第2周正课还没开_自习照常有`() {
        val ss = listOf(selfStudy(1, 1, "早自习", "08:45", "09:15", "A 楼 I 区 301"))
        val w = nudgeWindows(
            listOf(slot(weekday = 1)), "2026-08-31",
            LocalDateTime.parse("2026-09-07T00:00"), daysAhead = 1, leadMinutes = 1, selfstudy = ss,
        )
        assertEquals("第 2 周正课（3 周起）没开，但自习每天都有", 1, w.size)
        assertEquals(LocalDateTime.parse("2026-09-07T08:44"), w[0].start)
    }

    @Test
    fun `标题里的名词要跟着项走 —— 不能把自习说成上课`() {
        assertEquals("1 分钟后早自习", remindTitle(60, "早自习"))
        assertEquals("马上晚自习", remindTitle(20, "晚自习"))
        assertEquals("迟到地响起来时也要说对", "5 分钟后早自习", remindTitle(299, "早自习"))
        val r = fireRemind(
            60, "晚自习", "20:30 · A 楼 I 区 301",
            wantSilence = false, silFailReason = null, notifOk = true,
        )
        assertEquals("1 分钟后晚自习", r.title)
        assertEquals("20:30 · A 楼 I 区 301", r.body)
    }
}
