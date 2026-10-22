package top.ccbase.campus.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.seed.Seed

/**
 * 「一个课占两节课的要分开」—— 2026-10-22 用户要求：编辑课表里每一节都要能单独改。
 *
 * 一条 Slot 的 p_start/p_end 描述的是**整段**：教室、周次、课程都是整段共用的，
 * 所以在一条记录上根本表达不出「第 1 节这门课、第 2 节那门课」。
 * 拆分就是把这个表达解出来：课程/教室/周次/星期照抄，只是每节一条。
 *
 * 这里钉的是**不许悄悄多出课、也不许悄悄少课**：拆出来的条数、节次、共用字段都要对得上。
 */
class SplitSpanTest {

    private fun course(id: Int = 1, name: String = "大学英语") = Course(id, name, teacher = "王")

    @Test
    fun `占两节的课拆成两条单节_课程教室周次照抄`() {
        val plan = Seed(
            courses = listOf(course()),
            slots = listOf(Slot(11, weekday = 1, course_id = 1, p_start = 1, p_end = 2, room = "I区212", weeks = "1,2,3")),
        )

        val (next, n) = ManualSchedule.splitSpan(plan, 11)

        assertEquals("第 1-2 节 = 两条", 2, n)
        assertEquals(2, next.slots.size)
        assertTrue("原记录必须消失，否则同一天会有两条课压在第 1 节", next.slots.none { it.id == 11 })
        assertEquals("两条各占一节，按顺序", listOf(1, 2), next.slots.sortedBy { it.p_start }.map { it.p_start })
        next.slots.forEach { s ->
            assertEquals("每节都是独立一条", s.p_start, s.p_end)
            assertEquals("课程不许变", 1, s.course_id)
            assertEquals("教室不许变", "I区212", s.room)
            assertEquals("周次不许变", "1,2,3", s.weeks)
            assertEquals("星期不许变", 1, s.weekday)
        }
        assertEquals("课程只有一门，别复制出重复的课", 1, next.courses.size)
        assertEquals("拆出来的 id 不许互相撞", next.slots.size, next.slots.map { it.id }.toSet().size)
        assertTrue("新 id 不许占用原来那条的号段", next.slots.none { it.id <= 11 })
    }

    @Test
    fun `占三节的课拆成三条`() {
        val plan = Seed(slots = listOf(Slot(11, 1, 1, p_start = 3, p_end = 5)))
        val (next, n) = ManualSchedule.splitSpan(plan, 11)
        assertEquals(3, n)
        assertEquals(listOf(3, 4, 5), next.slots.sortedBy { it.p_start }.map { it.p_start })
    }

    @Test
    fun `只占一节_没什么可拆`() {
        val plan = Seed(slots = listOf(Slot(11, 1, 1, p_start = 4, p_end = 4)))
        val (next, n) = ManualSchedule.splitSpan(plan, 11)
        assertEquals(0, n)
        assertEquals("原样返回，别动数据", plan, next)
    }

    @Test
    fun `自定义时间的课不按节次_不拆`() {
        val plan = Seed(slots = listOf(Slot(11, 1, 1, p_start = null, p_end = null, time_text = "19:00-20:30")))
        val (next, n) = ManualSchedule.splitSpan(plan, 11)
        assertEquals(0, n)
        assertEquals(plan, next)
    }

    @Test
    fun `中间那节已被别的课占着_只拆空出来的节次`() {
        val plan = Seed(
            slots = listOf(
                Slot(11, 1, 1, p_start = 1, p_end = 3),          // 想拆这条
                Slot(12, 1, 2, p_start = 2, p_end = 2),          // 第 2 节是另一门课
            ),
        )
        val (next, n) = ManualSchedule.splitSpan(plan, 11)
        assertEquals("只拆得到第 1、3 节", 2, n)
        assertEquals("第 2 节不许出现两条", 1, next.slots.count { it.p_start == 2 })
        assertEquals("别人家那条不许被动", 2, next.slots.first { it.id == 12 }.course_id)
        assertNull("原记录不留残影", next.slots.firstOrNull { it.id == 11 })
    }

    @Test
    fun `别的时间段一条都不许动`() {
        val plan = Seed(
            slots = listOf(
                Slot(11, 1, 1, p_start = 1, p_end = 2),
                Slot(12, 2, 1, p_start = 1, p_end = 2),
                Slot(13, 1, 1, p_start = 6, p_end = 7),
            ),
        )
        val (next, n) = ManualSchedule.splitSpan(plan, 11)
        assertEquals(2, n)
        assertEquals("周二那条原封不动", Slot(12, 2, 1, p_start = 1, p_end = 2), next.slots.first { it.id == 12 })
        assertEquals("同一天的另一段原封不动", Slot(13, 1, 1, p_start = 6, p_end = 7), next.slots.first { it.id == 13 })
    }

    @Test
    fun `找不到这条就原样返回`() {
        val plan = Seed(slots = listOf(Slot(11, 1, 1, p_start = 1, p_end = 2)))
        assertEquals(plan to 0, ManualSchedule.splitSpan(plan, 999))
    }
}
