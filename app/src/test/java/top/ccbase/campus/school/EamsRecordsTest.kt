package top.ccbase.campus.school

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试/成绩的归类逻辑。用**真页面的形状**做夹具（表头中文、时间带星期、字段名 camelCase），
 * 因为这套解析的全部风险就在"教务改了列的顺序 / 多了一列" —— 所以专门测换列序。
 */
class EamsRecordsTest {

    @Test
    fun `考试表按表头归类 换列序也不错位`() {
        val harvest = """
        {"exams":{"arrays":[],"tables":[
          {"headers":["考场","课程名称","考试状态","考试时间","考试类型"],
           "rows":[["A301","高等数学","未开始","2027-01-05(Tue) 09:00-11:00","闭卷"]]}
        ]}}
        """.trimIndent()
        val r = parseRecords(harvest)!!
        assertEquals(1, r.exams.size)
        val e = r.exams[0]
        assertEquals("高等数学", e.name)
        assertEquals("A301", e.room)
        assertEquals("2027-01-05(Tue) 09:00-11:00", e.time)
        assertEquals("闭卷", e.type)
        assertEquals(LocalDate.of(2027, 1, 5), e.date)
    }

    @Test
    fun `内联 var 数组也能归类`() {
        val harvest = """
        {"exams":{"arrays":[{"name":"studentExamList","rows":[
          {"courseName":"大学物理","examTime":"2027-01-08(Thu) 14:00-16:00","room":"B204","examTypeName":"闭卷"},
          {"courseName":"大学物理","examTime":"2027-01-08(Thu) 14:00-16:00","room":"B204","examTypeName":"闭卷"}
        ]}],"tables":[]}}
        """.trimIndent()
        val r = parseRecords(harvest)!!
        // 同一条重复出现（两个数组都含它）→ 去重
        assertEquals(1, r.exams.size)
        assertEquals("B204", r.exams[0].room)
    }

    @Test
    fun `成绩表算出平均绩点与已修学分`() {
        val harvest = """
        {"grades":{"arrays":[],"tables":[
          {"headers":["课程名称","学分","绩点","成绩"],
           "rows":[["高等数学","5","3.7","88"],["大学英语","3","4.0","92"],["体育","1","--","通过"]]}
        ]}}
        """.trimIndent()
        val r = parseRecords(harvest)!!
        assertEquals(3, r.grades.size)
        // 三门都有学分 → 5+3+1
        assertEquals(9.0, r.totalCredits, 0.001)
        // 只有两门有绩点 → (3.7+4.0)/2，`--` 不参与
        assertEquals(3.85, r.averagePoint!!, 0.001)
        assertEquals("通过", r.grades.first { it.name == "体育" }.score)
    }

    @Test
    fun `空数据返回 null —— 空不等于出错 但也不许当成一份空快照存起来`() {
        assertNull(parseRecords(null))
        assertNull(parseRecords(""))
        assertNull(parseRecords("null"))
        assertNull(parseRecords("""{"exams":{"arrays":[],"tables":[]},"grades":{"arrays":[],"tables":[]}}"""))
        // 页面结构变了、根本不是 JSON → 也不能崩
        assertNull(parseRecords("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `下一场考试跳过已经过去的`() {
        val r = EamsRecords(
            exams = listOf(
                ExamEntry(name = "过去的", time = "2026-01-05 09:00"),
                ExamEntry(name = "下周", time = "2026-10-25(日) 09:00"),
                ExamEntry(name = "更远", time = "2026-12-01 09:00"),
            ),
        )
        val next = r.nextExam(LocalDate.of(2026, 10, 18))!!
        assertEquals("下周", next.name)
        assertEquals(7L, next.daysFrom(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `日期解析认三种格式 认不出就不猜`() {
        assertEquals(LocalDate.of(2027, 1, 5), parseDate("2027-01-05(Tue) 09:00-11:00"))
        assertEquals(LocalDate.of(2027, 1, 5), parseDate("2027/1/5 09:00"))
        assertEquals(LocalDate.of(2027, 1, 5), parseDate("2027年1月5日"))
        assertNull(parseDate("待定"))
        assertNull(parseDate(null))
        // 月份/日期非法 → null，不是抛异常
        assertNull(parseDate("2027-13-45"))
    }

    @Test
    fun `没名字的行直接丢掉 不塞空行`() {
        val harvest = """{"exams":{"arrays":[{"rows":[{"room":"A101"}]}],"tables":[]}}"""
        assertNull(parseRecords(harvest))
    }
}
