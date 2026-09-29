package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.Slot
import java.io.File

/**
 * 周视图（教务式网格）钉的几条**真实数据上的性质**。
 *
 * 前身是围绕 `periodAxis`（按单节排的行轴）写的用例 —— 那套行轴被证明是过度设计：
 * 教务的课表左边本来就是「节次段」（1-2 节 / 3-5 节），测试也要求能一眼看到「1-2 节」。
 * 行轴回到 `timeRows`（节次段）后，这里改成直接在**真实线上数据**上钉性质。
 *
 * 数据来源 `src/test/resources/live_plan_sample_1.json`：作者本人的真实课表
 * （11 门课 / 17 个时段），线上 `slots` 表的形状。
 */
class WeekGridLogicTest {

    private fun live(): Pair<List<Slot>, List<Course>> {
        val text = File("src/test/resources/live_plan_sample_1.json").readText()
        val slots = ArrayList<Slot>()
        val courses = ArrayList<Course>()
        // 极简 JSON 读取：只要 id / name / room / weekday / p_start / p_end / weeks / course_id
        val slotObjs = Regex("\\{[^{}]*\"p_start\"[^{}]*}").findAll(text).map { it.value }
        val courseObjs = Regex("\\{[^{}]*\"credits\"[^{}]*}").findAll(text).map { it.value }
        fun num(s: String, k: String): Int? =
            Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
        fun str(s: String, k: String): String? =
            Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(s)?.groupValues?.get(1)
        slotObjs.forEach { o ->
            slots.add(
                Slot(
                    id = num(o, "id") ?: 0,
                    course_id = num(o, "course_id"),
                    weekday = num(o, "weekday") ?: 1,
                    p_start = num(o, "p_start"),
                    p_end = num(o, "p_end"),
                    room = str(o, "room"),
                    time_text = str(o, "time_text"),
                    week_from = num(o, "week_from"),
                    week_to = num(o, "week_to"),
                    weeks = str(o, "weeks"),
                ),
            )
        }
        courseObjs.forEach { o ->
            courses.add(Course(id = num(o, "id") ?: 0, name = str(o, "name") ?: "?"))
        }
        return slots to courses
    }

    /**
     * 用户原话：「左边不要出现几节课连一块的情况，就一节一节地来」。
     * 所以行轴是**单个节次**（1、2、3…），左列写节号 + 该节自己的钟点。
     * 老形状是「1-2 节」这种段标签（一行一段），已被用户否掉。
     */
    @Test
    fun `行轴是一节一行_不是把几节并成一块`() {
        val (slots, _) = live()
        val units = unitRange(slots)
        assertEquals("行轴要从第 1 节起", 1, units.first)
        val covered = slots.flatMap { s -> (s.p_start ?: 0)..(s.p_end ?: 0) }.filter { it > 0 }
        assertEquals("行轴只画到用到的最大节次，不多画空行", covered.max(), units.last)
        assertTrue("线上数据要用到大半天的节次，实际 ${units.count()}", units.count() >= 8)
        units.forEach { u ->
            assertTrue("第 $u 节要有自己的钟点（左列第二行）", unitTimeLabel(u).isNotEmpty())
        }
    }

    @Test
    fun `跨节的课块从起始行往下占_中间行不重复起画`() {
        val (slots, _) = live()
        val s = slots.firstOrNull { (it.p_start ?: 0) == 3 && (it.p_end ?: 0) == 5 }
            ?: slots.first { ((it.p_end ?: 0) - (it.p_start ?: 0)) >= 1 }
        assertTrue("跨节时段至少占 2 行（实测里 3-5 节占 3 行），实际 ${spanUnits(s)}", spanUnits(s) >= 2)
        val start = s.p_start!!
        assertTrue("从起始节次能找到它", slotsStartingAt(listOf(s), s.weekday, start).isNotEmpty())
        assertTrue("跨节的课不该在中间行重复起画",
            slotsStartingAt(listOf(s), s.weekday, start + 1).isEmpty())
    }

    @Test
    fun `空白的星期不占列_有课的才画`() {
        val (slots, _) = live()
        val days = classDays(slots)
        assertTrue("至少要有 4 个有课的星期，实际 $days", days.size >= 4)
        days.forEach { d ->
            assertTrue("第 $d 天没有任何课时不该出现在列里", slots.any { it.weekday == d })
        }
    }

    /**
     * 这条钉的是一个真实存在的数据形状：同一门课在同一天有两套安排 ——
     *   `3-4 节（11:25-13:00）第 16 周` 与 `3-5 节（11:25-13:50）第 3-15 周`
     * 它们的**起始节次相同**，所以「行必须严格升序」这种断言是错的（会把合法数据判成 bug），
     * 但必须保证「同一周里同一天的同一节次段不会同时冒出两门课」。
     */
    @Test
    fun `同一格里同周不会冒出两条重叠时段`() {
        val (slots, _) = live()
        var overlapped = 0
        for (week in 1..20) {
            for (wd in 1..7) {
                val here = slotsInWeek(slots, week).filter { it.weekday == wd }
                for (a in here) for (b in here) {
                    if (a.id >= b.id) continue
                    val s1 = a.p_start ?: continue
                    val e1 = a.p_end ?: s1
                    val s2 = b.p_start ?: continue
                    val e2 = b.p_end ?: s2
                    if (s1 <= e2 && s2 <= e1) overlapped++
                }
            }
        }
        assertEquals("同一周同一天出现了重叠时段 —— 网格会画重", 0, overlapped)
    }

    @Test
    fun `翻周真的有变化_不是每周都一样`() {
        val (slots, _) = live()
        val w2 = slotsInWeek(slots, 2).size
        val w5 = slotsInWeek(slots, 5).size
        assertTrue("第 2 周和第 5 周的时段数应该不同（否则翻周没意义）：$w2 vs $w5", w2 != w5)
    }

    /**
     * 行轴必须**只由节次决定**。
     *
     * 老断言是「线上 time_text 里确实有『周一 1-2节』这种串」—— 前提来自当时的服务端
     * 文案。现在线上已经把 time_text 改成钟点串（`9:30-11:05`），前提作废，于是它假红。
     * 换成**变异式**断言，比原来更硬：把整个 time_text 抹掉，行轴一个字都不许变。
     * （源码级那条"网格绝不能拿 time_text 当行键"在 TabWiringTest。）
     */
    @Test
    fun `行轴只由节次决定_把 time_text 全抹掉也不变`() {
        val (slots, _) = live()
        val stripped = slots.map { it.copy(time_text = null) }
        assertEquals("行轴受 time_text 影响了（它是文案，不是节次）", unitRange(slots), unitRange(stripped))
        assertTrue("行轴不能是空的", unitRange(slots).count() > 5)
        stripped.forEach { s ->
            val a = s.p_start ?: 0
            val b = s.p_end ?: 0
            assertTrue("抹掉 time_text 后节次还得在：$a-$b", a >= 1 && b >= a)
        }
    }
}
