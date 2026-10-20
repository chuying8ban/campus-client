package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Course
import top.ccbase.campus.data.local.PeriodTableStore
import top.ccbase.campus.data.local.Slot

/**
 * 周视图的行轴：**一节一行**。
 *
 * 用户原话：「左边不要出现几节课连一块的情况，就一节一节地来」。
 * 早先左列写的是「1-2 节」「3-5 节」这种**时段**标签 —— 那时一行代表一段，
 * 于是「3-4 节」和「3-5 节」这种真实排课会挤在同一格，读起来别扭。
 * 现在一行 = 一个节次，课块跨几节就占几行（教务系统的表就是这么画的）。
 */

/** 这一屏要画哪些节次行：1 .. 用到的最大节次。 */
fun unitRange(slots: List<Slot>): IntRange {
    val max = slots.maxOfOrNull { s -> maxOf(s.p_end ?: 0, s.p_start ?: 0) } ?: 0
    return 1..max.coerceAtLeast(1)
}

/**
 * 某一节上开课的时段（可能多条：换机房、单双周、不同周次）。
 * 只有 `p_start == unit` 的算「从这一节开始」——跨节课块从起始行往下占。
 */
fun slotsStartingAt(slots: List<Slot>, weekday: Int, unit: Int): List<Slot> =
    slots.filter { it.weekday == weekday && (it.p_start ?: -1) == unit }
        .sortedWith(compareBy({ it.sort }, { it.id }))

/** 某个时段跨几节（至少 1） */
fun spanUnits(s: Slot): Int {
    val a = s.p_start ?: return 1
    val b = s.p_end ?: a
    return (b - a + 1).coerceAtLeast(1)
}

/**
 * 表里要画的星期 —— **永远是周一到周日 7 列**。
 *
 * 2026-10-18 用户："如果某一天没有课，课表不要把那一天删了"。
 * 以前只画有课的星期（常见 5 列），周末一没课整列就消失，看着像"这天不存在"；
 * 空列留着反而能一眼看出"周六是空的"，也不用靠数格子判断今天在哪。
 * 列变窄后表头改用单字「一…日」，见 [WeekGrid]。
 */
fun classDays(slots: List<Slot>): List<Int> = (1..7).toList()

/**
 * 节次的钟点文字（左列第二行）：来自**用户可改**的作息时间表。
 *
 * 读 [top.ccbase.campus.data.local.PeriodTableStore.table] 这个 Compose 状态是故意的：
 * 用户在「作息时间表」里改完回来，左列时间要当场变，不用杀进程。
 */
fun unitTimeLabel(unit: Int): String {
    val u = PeriodTableStore.unit(unit) ?: return ""
    return "${u.start}-${u.end}"
}

/** 课程名（取全名，窄格靠换行/省略号，不在这里截字） */
fun nameOf(courses: List<Course>, s: Slot): String =
    courses.firstOrNull { it.id == s.course_id }?.name ?: "?"

/** 教师名（可能为空） */
fun teacherOf(courses: List<Course>, s: Slot): String? =
    courses.firstOrNull { it.id == s.course_id }?.teacher?.takeIf { it.isNotBlank() }
