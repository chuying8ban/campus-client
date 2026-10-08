package top.ccbase.campus.ui.me
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performScrollTo
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
class ScheduleToolsTest {
 @get:Rule val rule=createComposeRule()
 @Test fun `从真实入口新增课程并写回本机`() {
  val ctx=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(ctx,CampusDb::class.java).allowMainThreadQueries().build()
  rule.setContent { CampusTheme { ScheduleEditor(ctx,db,{}) } }
  rule.waitUntil(10000) { rule.onAllNodesWithText("新增课程").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
  rule.onNodeWithText("新增课程").performClick()
  rule.onNodeWithText("课程名").performTextInput("测试课程")
  rule.onNodeWithText("保存安排").performScrollTo().performClick()
  rule.waitForIdle()
  rule.waitUntil(10000) {runBlocking {db.dao().slots().first().size==1}}
  runBlocking {assertEquals("测试课程",db.dao().courses().first().single().name);assertNotNull(db.dao().metaGet("manual_schedule_v1"))}
 }
}
