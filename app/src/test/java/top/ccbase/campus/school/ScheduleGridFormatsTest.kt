package top.ccbase.campus.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 别校格式回归样本。
 *
 * 产品口径仍然是「只支持中石大克校区」（[SchoolRegistry] 里只有一条），
 * 但粘贴导入是通用表格通道 —— 学生真把手上的课表贴进来时，不会先问自己是哪个学校的。
 * 这几份样本只做**回归**：挡住「为了修某一种皮肤把另一种弄坏」。
 *
 * 三种都是真实存在的页面形态，不是编的：
 *  ① 正方新版（eams5/UWP）：`<div>` 逐行、周次带方括号、表头有「上午/下午」整行分隔；
 *  ② 旧版正方：节次列 `1-2`、课程格 `rowspan` 跨两行、`<br>` 分隔字段；
 *  ③ 强智/URP 复制出来的制表符文本：首列「第一大节/第二大节」，一行里空格分隔字段。
 */
class ScheduleGridFormatsTest {

    // ─────────── ① 正方新版：分隔行不能被当成课 ───────────

    @Test
    fun `正方新版_上午下午分隔行不会变成一门课`() {
        val html = """
            <table class="Nsb_r_list">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td colspan="4">上午</td></tr>
              <tr><td>第1-2节</td>
                  <td><div>高等数学A(1)</div><div>张三</div><div>逸夫楼201</div><div>[01-16周]</div></td>
                  <td></td><td></td></tr>
              <tr><td colspan="4">下午</td></tr>
              <tr><td>第5-6节</td><td></td>
                  <td><div>大学物理</div><div>李四</div><div>C3-105</div><div>[02-16双周]</div></td>
                  <td></td></tr>
            </table>
        """.trimIndent()

        val r = ScheduleGridImport.parse(html)
        val names = r.courses.map { it.courseName }
        assertTrue("「上午/下午」被当成课了：$names", names.none { it.contains("上午") || it.contains("下午") })
        assertEquals(2, r.courses.size)

        val math = r.courses.first { it.courseName == "高等数学A(1)" }
        assertEquals(1, math.weekday); assertEquals(1, math.startUnit); assertEquals(2, math.endUnit)
        assertEquals((1..16).toList(), math.weeks)
        assertEquals("逸夫楼201", math.room)
        assertEquals("张三", math.teacher)

        val phys = r.courses.first { it.courseName == "大学物理" }
        assertEquals(2, phys.weekday); assertEquals(5, phys.startUnit); assertEquals(6, phys.endUnit)
        assertEquals("双周只该有偶数周", (2..16 step 2).toList(), phys.weeks)
        assertEquals("C3-105", phys.room)
        assertEquals("李四", phys.teacher)
    }

    // ─────────── ② 旧版正方：rowspan 不能写成两条 ───────────

    @Test
    fun `旧版正方_rowspan 跨行只落一条`() {
        val html = """
            <table>
              <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
              <tr><td rowspan="2">1-2</td><td rowspan="2">线性代数<br>王五<br>1-8周<br>文成楼125</td><td></td><td></td></tr>
              <tr><td></td><td>大学英语<br>赵六<br>1-16周<br>文成楼201</td></tr>
            </table>
        """.trimIndent()

        val r = ScheduleGridImport.parse(html)
        assertEquals("rowspan 展开后重复了：${r.courses}", 2, r.courses.size)

        val lin = r.courses.first { it.courseName == "线性代数" }
        assertEquals(1, lin.weekday); assertEquals(1, lin.startUnit); assertEquals(2, lin.endUnit)
        assertEquals((1..8).toList(), lin.weeks)
        assertEquals("文成楼125", lin.room); assertEquals("王五", lin.teacher)

        val eng = r.courses.first { it.courseName == "大学英语" }
        assertEquals(3, eng.weekday); assertEquals(1, eng.startUnit); assertEquals(2, eng.endUnit)
        assertEquals((1..16).toList(), eng.weeks)
        assertEquals("赵六", eng.teacher)
    }

    // ─────────── ③ 强智/URP：大节编号 + 空格分隔 ───────────

    @Test
    fun `强智第一大节按两小节算_行尾汉字当老师`() {
        val text = "节次\t星期一\t星期二\t星期三\n" +
            "第一大节\t马克思主义基本原理 张三 1-16周 逸夫楼201\t\t\n" +
            "第二大节\t\t大学英语 王五 2-16双周 文成楼125\t"

        val r = ScheduleGridImport.parse(text)
        assertEquals(2, r.courses.size)

        val marx = r.courses.first { it.courseName.contains("马克思主义") }
        assertEquals("第一大节 = 第1-2节", 1, marx.startUnit)
        assertEquals(2, marx.endUnit)
        assertEquals(1, marx.weekday)
        assertEquals("老师没从行尾认出来", "张三", marx.teacher)
        assertEquals("课名把老师一起吞了：${marx.courseName}", "马克思主义基本原理", marx.courseName)

        val eng = r.courses.first { it.courseName.contains("大学英语") }
        assertEquals("第二大节 = 第3-4节", 3, eng.startUnit)
        assertEquals(4, eng.endUnit)
        assertEquals(2, eng.weekday)
        assertEquals((2..16 step 2).toList(), eng.weeks)
        assertEquals("王五", eng.teacher)
    }

    // ─────────── ④ 中文节次写法 ───────────

    @Test
    fun `第三节_第五大节 这类中文编号也认`() {
        val text = "节次\t星期一\t星期二\n" +
            "第三节\t体育 陈七 1-16周 田径场\t\n" +
            "第五大节\t\t形势与政策 周八 3-9周 明理楼301"

        val r = ScheduleGridImport.parse(text)
        assertEquals(2, r.courses.size)
        val pe = r.courses.first { it.courseName.contains("体育") }
        assertEquals(3, pe.startUnit); assertEquals(3, pe.endUnit)
        val policy = r.courses.first { it.courseName.contains("形势与政策") }
        assertEquals("第五大节 = 9-10", 9, policy.startUnit); assertEquals(10, policy.endUnit)
    }

    // ─────────── ⑤ 认不出来时不能瞎猜 ───────────

    @Test
    fun `不是课表的内容不产出课程`() {
        val junk = "<table><tr><td>姓名</td><td>学号</td></tr><tr><td>张三</td><td>2026015842</td></tr></table>"
        val r = ScheduleGridImport.parse(junk)
        assertTrue(r.isEmpty)
        assertTrue("没找到表头要说话", r.warnings.isNotEmpty())
        assertNotNull(r.activitiesJson())
        assertEquals("[]", r.activitiesJson())
    }
}
