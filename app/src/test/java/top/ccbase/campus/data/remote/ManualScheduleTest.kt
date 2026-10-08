package top.ccbase.campus.data.remote
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.*
import top.ccbase.campus.data.seed.Seed
import top.ccbase.campus.alarm.DailyTimetableCheck
import java.time.LocalDateTime
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ManualScheduleTest {
 @Test fun `手动改名时间自习刷新保留_重导入可选择替换`() = runBlocking {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  val p=Seed(courses=listOf(Course(1500000001,"手动课程",teacher="老师")),slots=listOf(Slot(1500000001,4,1500000001,time_text="10:00-11:00",weeks="6,8",room="A")),selfstudy=listOf(SelfStudy(1500000001,4,"早自习","08:45","09:15","B")))
  ManualSchedule.save(db,p)
  PlanApplier.apply(db,Seed(courses=listOf(Course(1,"云端课程"))))
  assertEquals(p.slots,db.dao().slots().first());assertEquals(p.selfstudy,db.dao().selfStudy().first())
  val acts="""[{"courseName":"官方课程","weekday":4,"startUnit":1,"endUnit":2,"weekIndexes":[6]}]"""
  EamsLocalImport.import(db,acts)
  assertEquals(p.slots,db.dao().slots().first())
  EamsLocalImport.import(db,acts,keepManual=false)
  assertNull(db.dao().metaGet(ManualSchedule.KEY));assertNotEquals(p.slots,db.dao().slots().first())
  assertEquals(p.selfstudy,db.dao().selfStudy().first())
 }
 @Test fun `输入校验与每日跨午夜`() {
  assertNotNull(ManualSchedule.validate("",1,"08:00","09:00","1"))
  assertNotNull(ManualSchedule.validate("课程",1,"10:00","09:00","1"))
  assertNotNull(ManualSchedule.validate("课程",1,"08:00","09:00","31"))
  assertNull(ManualSchedule.validate("课程",4,"08:00","09:00","6,8"))
  val now=LocalDateTime.of(2026,10,8,20,0)
  assertEquals(now.plusDays(1),DailyTimetableCheck.next(now,"20:00"))
 }
}
