package top.ccbase.campus.school

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用表格导入的回归测试。
 *
 * 样本不是编的漂亮例子，是三种真实来源的形态：
 *  ① 教务课表页的 HTML（带 rowspan 跨节、<br> 分行、教师/地点标签）；
 *  ② 浏览器里全选复制出来的**制表符表格**；
 *  ③ Excel 一行一课导出的文字。
 */
class ScheduleGridImportTest {

    private val html = """
        <html><body>
        <table class="Nsb_r_table">
          <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td></tr>
          <tr>
            <td>第1-2节</td>
            <td>高等数学A(1)<br>教师：张三<br>地点：逸夫楼201<br>1-16周</td>
            <td></td><td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <td>第3-4节</td>
            <td rowspan="2">线性代数<br>教师：李四<br>地点：C3-105<br>1-5,7-11单周</td>
            <td>大学英语<br>教师：王五<br>地点：文成楼125<br>2-16双周</td>
            <td></td><td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <td>第5-6节</td>
            <td>体育<br>教师：赵六<br>地点：田径场<br>1-16周</td>
            <td></td><td></td><td></td><td></td><td></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `html 网格：rowspan 的第5-6节也能认出星期一那门课`() {
        val r = ScheduleGridImport.parse(html)
        assertEquals("html", r.source)
        assertTrue("不该有认不出的格子：${r.warnings}", r.warnings.isEmpty())

        val lin = r.courses.filter { it.courseName.contains("线性代数") }
        // rowspan=2 → 第3-4 和 第5-6 各一段
        assertEquals(2, lin.size)
        assertEquals(setOf(3 to 4, 5 to 6), lin.map { it.startUnit to it.endUnit }.toSet())
        assertTrue(lin.all { it.weekday == 1 })
        assertEquals("李四", lin.first().teacher)
        assertEquals("C3-105", lin.first().room)
        // 1-5,7-11 单周：按整段筛奇数（与 WakeUp 同一口径）
        assertEquals(listOf(1, 3, 5, 7, 9, 11), lin.first().weeks)

        val eng = r.courses.first { it.courseName.contains("大学英语") }
        assertEquals(2, eng.weekday)
        assertEquals(3, eng.startUnit)
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), eng.weeks)

        val math = r.courses.first { it.courseName.contains("高等数学") }
        assertEquals("逸夫楼201", math.room)
        assertEquals(1, math.weekday)
        assertEquals((1..16).toList(), math.weeks)
    }

    @Test
    fun `纯文本表格：制表符粘贴也能还原`() {
        val text = "节次\t星期一\t星期二\t星期三\n" +
            "第1-2节\t高等数学\t大学物理\t\n" +
            "第3-4节\t\t数据结构 1-15单周\t英语"

        val r = ScheduleGridImport.parse(text)
        assertEquals("text", r.source)
        assertTrue("不该有认不出的格子：${r.warnings}", r.warnings.isEmpty())

        val math = r.courses.first { it.courseName == "高等数学" }
        assertEquals(1, math.weekday)
        assertEquals(1, math.startUnit)
        assertEquals(2, math.endUnit)
        // 格子里没写周次 → 用整学期默认周（1-16）
        assertEquals((1..16).toList(), math.weeks)

        val ds = r.courses.first { it.courseName.contains("数据结构") }
        assertEquals(2, ds.weekday)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), ds.weeks)
        assertEquals(3, ds.startUnit)

        val eng = r.courses.first { it.courseName == "英语" }
        assertEquals(3, eng.weekday)
        assertEquals(3, eng.startUnit)
    }

    @Test
    fun `一行一课：星期和节次写在文字里`() {
        val text = "课程名称\t教师\t地点\t时间\n" +
            "高等数学A(1)\t张三\t逸夫楼201\t周一 第1-2节 1-16周\n" +
            "大学英语\t王五\t文成楼125\t周三 第3-4节 2-16双周 除8"

        val r = ScheduleGridImport.parse(text)
        val math = r.courses.first { it.courseName.contains("高等数学") }
        assertEquals(1, math.weekday)
        assertEquals(1, math.startUnit)
        assertEquals(2, math.endUnit)
        assertEquals((1..16).toList(), math.weeks)
        assertEquals("逸夫楼201", math.room)

        val eng = r.courses.first { it.courseName.contains("大学英语") }
        assertEquals(3, eng.weekday)
        assertTrue("除8 必须被排除", 8 !in eng.weeks)
        assertEquals(listOf(2, 4, 6, 10, 12, 14, 16), eng.weeks)
    }

    @Test
    fun `地点里的数字不会被误当成周次`() {
        // 这是真踩过的坑：「逸夫楼201 \t 周一」曾把 `01\t周` 当成第 1 周
        val r = ScheduleGridImport.parse("高等数学 周一 第1-2节 1-16周 逸夫楼201")
        val c = r.courses.single()
        assertEquals((1..16).toList(), c.weeks)
        assertEquals("逸夫楼201", c.room)
    }

    @Test
    fun `周次解析覆盖各校写法`() {
        assertEquals((1..16).toList(), ScheduleGridImport.parseWeeks("1-16周"))
        assertEquals((1..16).toList(), ScheduleGridImport.parseWeeks("1~16"))
        assertEquals(listOf(1, 3, 5), ScheduleGridImport.parseWeeks("1-5单周"))
        assertEquals(listOf(2, 4, 6), ScheduleGridImport.parseWeeks("1-6周(双)"))
        assertEquals(listOf(2, 5, 8), ScheduleGridImport.parseWeeks("2、5、8周"))
        assertEquals((1..10).toList() - 5, ScheduleGridImport.parseWeeks("1-10周除5"))
        assertEquals((1..20).toList(), ScheduleGridImport.parseWeeks("全周", maxWeek = 20))
    }

    @Test
    fun `认不出的格子会报出来，不会静默丢掉`() {
        val bad = """
            <table>
              <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td></tr>
              <tr><td>第1-2节</td><td>第7节</td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        val r = ScheduleGridImport.parse(bad)
        assertTrue(r.isEmpty)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun `activities 输出能被导入白名单整份放行`() {
        val json = ScheduleGridImport.parse(html).activitiesJson()
        val parsed = Json.parseToJsonElement(json).jsonArray
        val cleaned = Json.parseToJsonElement(
            top.ccbase.campus.net.SchoolImportPolicy.activityAllowlist(json),
        ).jsonArray

        assertEquals(parsed.size, cleaned.size)
        assertEquals(
            parsed.map { it.jsonObject.mapValues { (_, v) -> v.toString() } },
            cleaned.map { it.jsonObject.mapValues { (_, v) -> v.toString() } },
        )
    }

    @Test
    fun `浏览器复制带 rowspan 的表格：第二节次左边是空的，不能自己加一`() {
        // 「第1-2节」那一格跨了两行，复制出来第二行首列是空的；
        // 星期二那门课仍然属于第1-2节，不能被顺延成第3节。
        val text = "节次\t星期一\t星期二\n" +
            "第1-2节\t高等数学\t\n" +
            "\t\t大学物理"

        val r = ScheduleGridImport.parse(text)
        val math = r.courses.first { it.courseName == "高等数学" }
        val phys = r.courses.first { it.courseName == "大学物理" }
        assertEquals(1, math.startUnit); assertEquals(2, math.endUnit)
        assertEquals(2, phys.weekday) // 星期二那一列
        assertEquals(1, phys.startUnit); assertEquals(2, phys.endUnit)
    }
}
