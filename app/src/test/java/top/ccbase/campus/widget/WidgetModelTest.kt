package top.ccbase.campus.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.domain.ClassNow
import top.ccbase.campus.domain.TodayView

/**
 * 桌面小组件显示什么 —— 纯函数，直接验文案。
 *
 * 断言的着力点：**桌面那一小块不能空着也不能骗人**。
 * 没课要说"今天没有课"，课上完了要说"都上完了"，超过三节要写"还有 N 节"，
 * 正在上的那节要在最大一行里。
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
    fun `正在上课时大字显示正在上`() {
        val m = buildWidgetModel(
            view(
                listOf(
                    cls("高等数学", "08:00-09:35", "now", "A301"),
                    cls("大学英语", "10:00-11:35", "todo"),
                ),
            ),
        )
        assertEquals("10月19日 周一 · 第8周", m.dateLine)
        assertTrue(m.headline.startsWith("正在上"))
        assertTrue(m.headline.contains("高等数学"))
        assertEquals(2, m.rows.size)
        assertEquals("A301", m.rows[0].room)
        assertEquals("点开看完整课表", m.footer)
    }

    @Test
    fun `没课时说清楚没有课_不显示空白`() {
        val m = buildWidgetModel(view(emptyList()))
        assertEquals("今天没有课", m.headline)
        assertTrue(m.rows.isEmpty())
    }

    @Test
    fun `课上完了与还有更多节要说出来`() {
        val done = buildWidgetModel(view(listOf(cls("高数", "08:00-09:35", "done"))))
        assertEquals("今天的课都上完了", done.headline)

        val many = buildWidgetModel(
            view(
                (1..5).map { cls("课$it", "0$it:00-0$it:45", if (it == 1) "now" else "todo") },
            ),
        )
        assertEquals(3, many.rows.size)
        assertTrue("超出的节数要写出来，不能默默截断", many.footer.contains("还有 2 节"))
    }
}
