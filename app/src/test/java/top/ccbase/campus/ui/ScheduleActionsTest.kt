package top.ccbase.campus.ui
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.ccbase.campus.data.local.CampusDb
import top.ccbase.campus.ui.schedule.ScheduleScreen
import top.ccbase.campus.ui.theme.CampusTheme
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ScheduleActionsTest {
 @get:Rule val rule=createComposeRule()
 @Test fun `课表顶部更新编辑接线且没有刷新`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  var update=0;var edit=0
  rule.setContent {CampusTheme {ScheduleScreen(db,{update++},{edit++})}}
  rule.onNodeWithText("更新课表").performClick()
  rule.onNodeWithText("编辑课表").performClick()
  assertEquals(1,update);assertEquals(1,edit)
  rule.onNodeWithText("刷新").assertDoesNotExist()
 }
}
