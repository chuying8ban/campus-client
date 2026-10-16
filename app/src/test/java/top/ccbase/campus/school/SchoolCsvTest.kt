package top.ccbase.campus.school
import org.junit.Test
import org.junit.Assert.*
class SchoolCsvTest {
 @Test fun `CSV实际转换精确周次时间`(){val raw=SchoolCsv.parse(SchoolCsv.HEADER+"\n示例课,1,08:00,09:40,1-5(单);8,教室,老师");val p=top.ccbase.campus.data.local.EamsLocalImport.build(raw);assertEquals("1,3,5,8",p.slots.single().weeks);assertEquals("08:00-09:40",p.slots.single().time_text)}
 @Test fun `空文件错误列超范围及时间均拒绝`(){listOf("",SchoolCsv.HEADER,SchoolCsv.HEADER+"\n课,8,08:00,09:00,1,楼,师",SchoolCsv.HEADER+"\n课,1,10:00,09:00,1,楼,师",SchoolCsv.HEADER+"\n课,1,08:00,09:00,31,楼,师").forEach{s->assertThrows(IllegalArgumentException::class.java){SchoolCsv.parse(s)}}}
}
