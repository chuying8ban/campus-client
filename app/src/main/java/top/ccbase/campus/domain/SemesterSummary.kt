package top.ccbase.campus.domain

import top.ccbase.campus.data.local.Course

/**
 * 学期概览 —— 课表页顶部那一行数字（`12 门 · 22 学分 · 第 3~20 周`）。
 *
 * 为什么单独抽出来：这三个数以前只有一个（门数），而学分/必修选修其实**早就在
 * 教务课表 JSON 里**（`credits` / `courseType`），只是没人把它摆到界面上。
 * 抽成纯函数是为了能钉死进位规则：教务给的学分可能是 2.5，也可能是 0（没过手）；
 * 一门课都没有时不能显示"0 学分"这种像是坏了的东西。
 */
data class SemesterSummary(
    /** 门数（去重后的课程行数） */
    val courses: Int,
    /** 有学分数据时是 "22 学分" / "22.5 学分"；全都没填就是 null */
    val creditsText: String?,
    val requiredCourses: Int,
    val electiveCourses: Int,
) {
    /** 只有必修/选修都认得出来才给分类（只认得一半时给出来会误导）。 */
    val categoryText: String?
        get() = when {
            requiredCourses > 0 && electiveCourses > 0 -> "必修 $requiredCourses · 选修 $electiveCourses"
            else -> null
        }

    /** 课表页右上角那一行。周次由调用方拼（它来自 slot 而不是课程）。 */
    fun headline(weekRange: String): String = buildList {
        add("$courses 门")
        creditsText?.let { add(it) }
        if (weekRange.isNotBlank()) add(weekRange)
    }.joinToString(" · ")
}

/** 学分显示：整数不加 ".0"，小数保留一位（教务给 2.5 就显示 2.5）。 */
internal fun formatCredits(total: Double): String {
    val rounded = Math.round(total * 10.0) / 10.0
    val text = if (rounded == Math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
    return "$text 学分"
}

fun semesterSummary(courses: List<Course>): SemesterSummary {
    var credits = 0.0
    var withCredits = 0
    var required = 0
    var elective = 0
    for (c in courses) {
        val cr = c.credits
        if (cr > 0) {
            credits += cr
            withCredits++
        }
        val cat = c.category.orEmpty()
        if (cat.contains("必修")) required++ else if (cat.contains("选修")) elective++
    }
    return SemesterSummary(
        courses = courses.size,
        // 一门课都没填学分时宁可不显示，也不显示 "0 学分"（那看着像坏了）
        creditsText = if (withCredits == 0) null else formatCredits(credits),
        requiredCourses = required,
        electiveCourses = elective,
    )
}
