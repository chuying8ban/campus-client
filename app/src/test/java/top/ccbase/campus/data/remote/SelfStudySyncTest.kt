package top.ccbase.campus.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.*
import top.ccbase.campus.data.seed.SeedLoader

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SelfStudySyncTest {
 @Test fun `刷新重复导入不清空独立自习且不自动生成别人的作息`() = runBlocking {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  val seed=SeedLoader.load(ctx)
  db.dao().putSelfStudy(seed.selfstudy)
  val plan=seed.copy(selfstudy=emptyList())
  PlanApplier.apply(db,plan)
  assertEquals(seed.selfstudy,db.dao().selfStudy().first())
  PlanApplier.apply(db,plan)
  assertEquals(seed.selfstudy,db.dao().selfStudy().first())
  val acts="""[{"courseName":"测试课程","weekday":4,"startUnit":1,"endUnit":2,"weekIndexes":[6]}]"""
  EamsLocalImport.import(db,acts)
  assertEquals(seed.selfstudy,db.dao().selfStudy().first())
  assertEquals(2,db.dao().selfStudyOfDay(4).first().size)
  val blank=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  PlanApplier.apply(blank,plan)
  assertTrue(blank.dao().selfStudy().first().isEmpty())
 }
}
