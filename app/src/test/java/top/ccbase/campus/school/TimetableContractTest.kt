package top.ccbase.campus.school
import org.junit.Assert.*
import org.junit.Test
class TimetableContractTest {
 private val calendar=SchoolCalendar("2026-09-07",20,listOf(SchoolPeriod(1,"08:00","08:45"),SchoolPeriod(2,"08:55","09:40")))
 @Test fun `单双周与散周精确保留`(){assertEquals(listOf(1,3,5,8),TimetableContract.weeks("1-5(单),8周",20));assertEquals(listOf(2,4,6),TimetableContract.weeks("2-6(双)",20))}
 @Test fun `不猜测含糊周次`(){listOf("","1-30","5-2","前半学期","1-3(双)单").forEach{s->assertThrows(IllegalArgumentException::class.java){TimetableContract.weeks(s,20)}}}
 @Test fun `每个学校使用自己的节次而不是克校时间`(){val s=TimetableContract.activities(listOf(NormalizedCourse("示例课",2,1,2,listOf(1,3))),calendar);assertTrue(s.contains("08:00"));assertTrue(s.contains("09:40"));assertFalse(s.contains("09:30"))}
 @Test fun `节次缺失和重叠均拒绝`(){assertThrows(IllegalArgumentException::class.java){TimetableContract.activities(listOf(NormalizedCourse("示例课",1,1,3,listOf(1))),calendar)};assertThrows(IllegalArgumentException::class.java){calendar.copy(periods=listOf(SchoolPeriod(1,"08:00","09:00"),SchoolPeriod(2,"08:30","09:30"))).validate()}}
 @Test fun `错误星期空课表超范围周次拒绝`(){listOf(NormalizedCourse("示例课",0,1,2,listOf(1)),NormalizedCourse("示例课",1,1,2,listOf(21)),NormalizedCourse("示例课",1,1,2,emptyList())).forEach{c->assertThrows(IllegalArgumentException::class.java){TimetableContract.activities(listOf(c),calendar)}};assertThrows(IllegalArgumentException::class.java){TimetableContract.activities(emptyList(),calendar)}}
 @Test fun `学期起点必须周一`(){assertThrows(IllegalArgumentException::class.java){calendar.copy(firstMonday="2026-09-08").validate()}}
}
