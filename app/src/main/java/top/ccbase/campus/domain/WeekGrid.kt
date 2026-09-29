package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Course
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

/** 有课的星期（按星期几升序）——空白列不画，课块才有宽度写全课程名 */
fun classDays(slots: List<Slot>): List<Int> =
    (1..7).filter { d -> slots.any { it.weekday == d } }

/** 节次的钟点文字（左列第二行）：官方课表的单节时间 */
fun unitTimeLabel(unit: Int): String {
    val (a, b) = UNIT_TIMES[unit] ?: return ""
    return "$a-$b"
}

/** 课程名（取全名，窄格靠换行/省略号，不在这里截字） */
fun nameOf(courses: List<Course>, s: Slot): String =
    courses.firstOrNull { it.id == s.course_id }?.name ?: "?"

/** 教师名（可能为空） */
fun teacherOf(courses: List<Course>, s: Slot): String? =
    courses.firstOrNull { it.id == s.course_id }?.teacher?.takeIf { it.isNotBlank() }
