package top.ccbase.campus.ui.me
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.ui.theme.CampusTheme
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SelfStudyRepairTest {
 @get:Rule val rule=createComposeRule()
 @Test fun `取消不写数据_确认才恢复`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  rule.setContent { CampusTheme { SelfStudyRepair(ctx,db) } }
  rule.onNodeWithText("恢复旧版早晚自习").performClick()
  rule.onNodeWithText("取消").performClick()
  runBlocking { assertTrue(db.dao().selfStudy().first().isEmpty()) }
  rule.onNodeWithText("恢复旧版早晚自习").performClick()
  rule.onNodeWithText("确认恢复").performClick()
  rule.waitUntil(10000) { runBlocking { db.dao().selfStudy().first().size==10 } }
  runBlocking { assertEquals(2,db.dao().selfStudyOfDay(4).first().size) }
 }
}
