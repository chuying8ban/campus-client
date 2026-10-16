package top.ccbase.campus.school
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GenericSchoolImportTest {
 private lateinit var db:CampusDb
 private val cal=SchoolCalendar("2026-09-07",20,listOf(SchoolPeriod(1,"08:00","08:45"),SchoolPeriod(2,"08:55","09:40")))
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),CampusDb::class.java).allowMainThreadQueries().build()}
 @After fun close(){db.close()}
 @Test fun `通用课程实际落库精确时间周次且不凭空生成自习`()=runBlocking {
  GenericSchoolImport.import(db,listOf(NormalizedCourse("合成学校课程",3,1,2,listOf(1,3,7),teacher="示例教师")),cal)
  val slot=db.dao().slots().first().single()
  assertEquals("08:00-09:40",slot.time_text);assertEquals("1,3,7",slot.weeks)
  assertEquals("示例教师",db.dao().courses().first().single().teacher)
  assertTrue(db.dao().selfStudy().first().isEmpty())
 }
 @Test fun `批次任一课程错误原课表完整保留`()=runBlocking {
  val old=NormalizedCourse("原课程",1,1,2,listOf(1,2))
  GenericSchoolImport.import(db,listOf(old),cal)
  val before=db.dao().slots().first()
  try {GenericSchoolImport.import(db,listOf(old.copy(name="新课程"),old.copy(weekday=8)),cal);fail("必须拒绝错误批次")}catch(expected:IllegalArgumentException){}
  assertEquals(before,db.dao().slots().first());assertEquals("原课程",db.dao().courses().first().single().name)
 }
}
