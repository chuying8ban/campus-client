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
    fun `没课但有任务时 页脚报任务数`() {
        val m = buildWidgetModel(view(emptyList(), tasks = 2))
        assertEquals("还有 2 件任务", m.footer)
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
        assertTrue("超出的节数要写出来，不能默默截断", many.footer.contains("还有 1 节"))
    }
}
