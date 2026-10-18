package top.ccbase.campus.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.domain.ClassNow
import top.ccbase.campus.domain.TodayView

/**
 * 桌面小组件显示什么 —— 纯函数，直接验文案。
 *
 * 断言的着力点：**桌面那一小块不能空着也不能骗人**。
 * 2.3.0 起文案分四级（status / headline / meta / rows），所以这里连"哪个字段说什么"一起钉住：
 * 状态词只出现在 status 里，大字只有课程名，时间和地点在 meta —— 别再把三样糊进一行。
 */
class WidgetModelTest {

    private fun view(classes: List<ClassNow>, tasks: Int = 0): TodayView = TodayView(
        date = "2026-10-19",
        weekday = 1,
        weekdayCn = "周一",
        weekNo = 8,
        semesterName = "2026 秋季",
        termWeeks = 20,
        classes = classes,
        selfstudy = emptyList(),
        tasks = List(tasks) { i ->
            top.ccbase.campus.domain.TaskNow(
                id = i, title = "任务$i", detail = null, deliverable = null,
                track = null, priority = 0, why = "每日", doneToday = false,
            )
        },
        standing = emptyList(),
    )

    private fun cls(name: String, time: String, status: String, room: String? = null) = ClassNow(
        slotId = name.hashCode(), courseId = 1, name = name, short = null, teacher = null,
        isFocus = false, timeText = time, room = room, status = status,
    )

    @Test
    fun `正在上课时 状态说正在上 大字只有课名`() {
        val m = buildWidgetModel(
            view(
                listOf(
                    cls("高等数学", "08:00-09:35", "now", "A301"),
                    cls("大学英语", "10:00-11:35", "todo"),
                ),
            ),
        )
        assertEquals("10月19日 周一", m.dateLine)
        assertEquals("第8周", m.week)
        assertEquals("正在上课", m.status)
        assertEquals("高等数学", m.headline)
        assertEquals("08:00-09:35 · A301", m.meta)
        assertEquals(1, m.rows.size)
        assertEquals("大学英语", m.rows[0].name)
        assertEquals("点开看完整课表", m.footer)
    }

    @Test
    fun `没课时说清楚没有课_不显示空白`() {
        val m = buildWidgetModel(view(emptyList()))
        assertEquals("今天没有课", m.headline)
        assertEquals("今天", m.status)
        assertTrue("没课也不许留一句空白", m.meta.isNotBlank())
        assertTrue(m.rows.isEmpty())
    }

    @Test
    fun `任务系统取消后 没课也不许再报任务数`() {
        // 2026-10-18：用户已经取消任务系统，但库里还留着旧任务记录，
        // 小组件曾因此显示"还有 19 件任务"。这条钉住：不管库里有几条，都不许出现。
        val m = buildWidgetModel(view(emptyList(), tasks = 19))
        assertNotEquals("还有 19 件任务", m.footer)
        assertTrue("页脚不许提任务", !m.footer.contains("任务"))
    }

    @Test
    fun `早晚自习进同一条时间线`() {
        val v = view(listOf(cls("高等数学", "08:00-09:35", "done"))).copy(
            selfstudy = listOf(
                top.ccbase.campus.domain.SelfStudyNow("morning", "07:20", "08:00", "操场", "todo"),
                top.ccbase.campus.domain.SelfStudyNow("evening", "19:00", "21:00", "图书馆", "todo"),
            ),
        )
        val m = buildWidgetModel(v, nowClock = java.time.LocalTime.of(6, 0))
        assertEquals("早自习", m.headline)
        assertEquals("07:20–08:00 · 操场", m.meta)
        assertTrue("晚自习也要在后几行里", m.rows.any { it.name == "晚自习" })
        assertTrue("自习得标出来是 self，不能当课程", m.rows.all { it.kind == WidgetRow.KIND_SELF })
    }

    @Test
    fun `自建日程和课程分开标_且按时间插队`() {
        val v = view(listOf(cls("高等数学", "08:00-09:35", "todo")))
        val plans = listOf(
            top.ccbase.campus.domain.PlanNow(
                id = 1, day = "2026-10-19", start = "07:00", end = "",
                title = "交实验报告", note = "教A301", done = false,
            ),
        )
        val m = buildWidgetModel(v, plans = plans, nowClock = java.time.LocalTime.of(6, 0))
        assertEquals("交实验报告", m.headline)
        assertEquals("下一个日程", m.status)
        assertEquals(WidgetRow.KIND_COURSE, m.rows.first { it.name == "高等数学" }.kind)
    }

    @Test
    fun `课上完了与还有更多节要说出来`() {
        val done = buildWidgetModel(view(listOf(cls("高数", "08:00-09:35", "done"))))
        assertEquals("今天的课都上完了", done.headline)
        assertEquals("已上完", done.status)

        val many = buildWidgetModel(
            view(
                (1..5).map { cls("课$it", "0$it:00-0$it:45", if (it == 1) "now" else "todo") },
            ),
        )
        assertEquals(3, many.rows.size)
        // 2026-10-18：时间线上现在混着课/自习/日程，单位从"节"改成"项"
        assertTrue("超出的条数要写出来，不能默默截断", many.footer.contains("还有 1 项"))
    }
}
