package top.ccbase.campus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.ccbase.campus.data.local.Course

/**
 * 学期概览那行数字的规则 —— 钉死三件容易出错的事：
 *  ① 学分是 2.5 这种小数时不能变成 "2.5.0"；
 *  ② 教务没填学分（0）时不能显示 "0 学分"（那看着像坏了）；
 *  ③ 一门课都没有时那行不能只显示 "0 门 · 第 3~20 周" 这种诡异组合。
 */
class SemesterSummaryTest {

    private fun course(id: Int, credits: Double = 0.0, category: String? = null) = Course(
        id = id, name = "课$id", credits = credits, category = category,
    )

    @Test
    fun `整数学分不带小数尾巴`() {
        val s = semesterSummary(listOf(course(1, 4.0), course(2, 3.5), course(3, 2.0)))
        assertEquals(3, s.courses)
        assertEquals("9.5 学分", s.creditsText)
    }

    @Test
    fun `全是整数时显示整数`() {
        val s = semesterSummary(listOf(course(1, 12.0), course(2, 10.0)))
        assertEquals("22 学分", s.creditsText)
    }

    @Test
    fun `一门课都没填学分就不显示学分`() {
        val s = semesterSummary(listOf(course(1), course(2)))
        assertNull(s.creditsText)
        assertEquals("2 门 · 第 3~20 周", s.headline("第 3~20 周"))
    }

    @Test
    fun `必修选修按教务课程性质归类`() {
        val s = semesterSummary(
            listOf(
                course(1, 4.0, "必修"),
                course(2, 3.0, "专业必修课"),
                course(3, 2.0, "选修"),
                course(4, 1.0, null),
            ),
        )
        assertEquals(2, s.requiredCourses)
        assertEquals(1, s.electiveCourses)
        assertEquals("必修 2 · 选修 1", s.categoryText)
    }

    @Test
    fun `只认得一边时不给分类`() {
        val s = semesterSummary(listOf(course(1, 3.0, "必修"), course(2, 2.0, null)))
        assertNull("只认得必修，说出来会让人以为剩下那门也是必修", s.categoryText)
    }

    @Test
    fun `没有课也不崩`() {
        val s = semesterSummary(emptyList())
        assertEquals(0, s.courses)
        assertNull(s.creditsText)
        assertEquals("0 门 · 第 1~1 周", s.headline("第 1~1 周"))
    }

    @Test
    fun `周次为空时不留下多余的分隔点`() {
        val s = semesterSummary(listOf(course(1, 2.0)))
        assertEquals("1 门 · 2 学分", s.headline(""))
    }
}
